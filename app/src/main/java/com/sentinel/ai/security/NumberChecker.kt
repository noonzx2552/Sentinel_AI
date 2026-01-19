package com.sentinel.ai.security

import android.content.Context
import com.sentinel.ai.BuildConfig
import com.sentinel.ai.R
import com.sentinel.ai.ui.DebugSettings
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale

data class NumberCheckResult(
    val rawInput: String,
    val formattedE164: String,
    val displayNumber: String,
    val region: String?,
    val carrier: String?,
    val countryName: String?,
    val externalLineType: String?,
    val numberType: PhoneNumberUtil.PhoneNumberType,
    val score: Int,
    val status: SafetyLevel,
    val issues: List<String>,
    val reportCount: Int,
    val reportDetails: List<String>,
    val debugExternalRaw: String?,
    val debugReportRaw: String?
)

class NumberChecker(
    private val context: Context,
    private val enableExternalLookups: Boolean = true
) {

    private val phoneNumberUtil: PhoneNumberUtil = PhoneNumberUtil.createInstance(context)
    private val httpClient = OkHttpClient()

    suspend fun check(raw: String): NumberCheckResult = withContext(Dispatchers.IO) {
        val trimmed = raw.trim()
        val normalized = normalizeInput(trimmed)
        val digitsOnly = normalized.filter { it.isDigit() }
        if (digitsOnly.isEmpty()) throw IllegalArgumentException(context.getString(R.string.error_empty_phone_number))

        val defaultRegion = Locale.getDefault().country
        val regionCandidates = buildList {
            if (defaultRegion.isNotBlank()) add(defaultRegion)
            if (!contains("TH")) add("TH")
        }

        val number = regionCandidates.asSequence()
            .mapNotNull { region -> runCatching { phoneNumberUtil.parse(normalized, region) }.getOrNull() }
            .firstOrNull()
            ?: throw IllegalArgumentException(context.getString(R.string.error_invalid_number_format))
        val valid = phoneNumberUtil.isValidNumber(number)
        val region = phoneNumberUtil.getRegionCodeForNumber(number)
        val type = phoneNumberUtil.getNumberType(number)
        val formattedE164 = phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
        val nationalDigits = phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
            .filter { it.isDigit() }

        var score = 55
        val notes = mutableListOf<String>()
        var carrier: String? = null
        var countryName: String? = null
        var externalLineType: String? = null

        if (valid) {
            score += 15
        } else {
            score -= 25
            notes.add(context.getString(R.string.number_note_invalid_region_format, region))
        }

        when (type) {
            PhoneNumberUtil.PhoneNumberType.MOBILE -> score += 5
            PhoneNumberUtil.PhoneNumberType.FIXED_LINE -> score += 3
            PhoneNumberUtil.PhoneNumberType.TOLL_FREE -> {
                score -= 5
                notes.add(context.getString(R.string.number_note_toll_free_spoof))
            }
            PhoneNumberUtil.PhoneNumberType.PREMIUM_RATE -> {
                score -= 25
                notes.add(context.getString(R.string.number_note_premium_risky))
            }
            PhoneNumberUtil.PhoneNumberType.PERSONAL_NUMBER -> score -= 8
            PhoneNumberUtil.PhoneNumberType.VOIP -> score -= 6
            PhoneNumberUtil.PhoneNumberType.UNKNOWN -> score -= 5
            else -> {}
        }

        val queryNumber = nationalDigits.ifBlank { formattedE164.filter { it.isDigit() } }
        val external = if (enableExternalLookups) fetchExternalInfo(queryNumber, region) else null
        external?.info?.let { info ->
            carrier = info.carrier ?: carrier
            countryName = info.countryName ?: countryName
            externalLineType = info.lineType ?: externalLineType
            if (info.valid == false) {
                score -= 25
                notes.add(context.getString(R.string.number_note_external_invalid))
            }
            when (info.lineType?.lowercase(Locale.getDefault())) {
                "premium rate" -> {
                    score -= 25
                    notes.add(context.getString(R.string.number_note_external_premium))
                }
                "toll-free" -> {
                    score -= 5
                    notes.add(context.getString(R.string.number_note_external_toll_free))
                }
                "voip" -> {
                    score -= 6
                    notes.add(context.getString(R.string.number_note_external_voip))
                }
                else -> {}
            }
        }

        val reports = if (enableExternalLookups) fetchReports(digitsOnly) else null
        if (reports != null && reports.count > 0) {
            score = (score - 35).coerceAtLeast(0)
            notes.add(context.getString(R.string.checknumber_reports_found_format, reports.count))
        } else {
            // No reports found - set score to 100 (completely safe)
            score = 100
        }

        val finalScore = score.coerceIn(0, 100)
        val status = when {
            finalScore >= 75 -> SafetyLevel.SAFE
            finalScore >= 50 -> SafetyLevel.CAUTION
            else -> SafetyLevel.DANGER
        }

        NumberCheckResult(
            rawInput = raw,
            formattedE164 = formattedE164,
            displayNumber = phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL),
            region = countryName ?: region,
            carrier = carrier,
            countryName = countryName,
            externalLineType = externalLineType,
            numberType = type,
            score = finalScore,
            status = status,
            issues = notes,
            reportCount = reports?.count ?: 0,
            reportDetails = reports?.details ?: emptyList(),
            debugExternalRaw = if (DebugSettings.isDebugEnabled.value) external?.raw else null,
            debugReportRaw = if (DebugSettings.isDebugEnabled.value) reports?.rawHtml else null
        )
    }

    private fun normalizeInput(raw: String): String {
        if (raw.isBlank()) return raw
        val cleaned = raw.replace(Regex("[^0-9+]+"), "")
        return if (cleaned.startsWith("+")) cleaned else cleaned.replace("+", "")
    }

    private fun fetchExternalInfo(queryNumber: String, region: String?): ExternalLookupResult? {
        val number = queryNumber.ifBlank { return null }
        val countryCode = region?.takeIf { it.isNotBlank() } ?: "TH"
        val primaryKey = BuildConfig.PHONE_REP_API_KEY
        val fallbackKey = BuildConfig.PHONE_REP_API_KEY_FALLBACK
        val primary = fetchApilayerInfo(primaryKey, number, countryCode)
        if (primary != null) return primary
        return fetchApilayerInfo(fallbackKey, number, countryCode)
    }

    private fun fetchApilayerInfo(apiKey: String, number: String, countryCode: String): ExternalLookupResult? {
        if (apiKey.isBlank()) return null
        val encodedNumber = URLEncoder.encode(number, "UTF-8")
        val url = "https://apilayer.net/api/validate?access_key=$apiKey&number=$encodedNumber&country_code=$countryCode&format=1"
        val request = Request.Builder().url(url).get().build()
        return runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) return null
                val json = JSONObject(body)
                val success = json.optBoolean("success", true)
                if (!success) {
                    val error = json.optJSONObject("error")
                    val type = error?.optString("type").orEmpty()
                    val code = error?.optString("code").orEmpty()
                    val info = error?.optString("info").orEmpty()
                    val combined = "$type $code $info".lowercase(Locale.getDefault())
                    if (combined.contains("usage") || combined.contains("quota") || combined.contains("limit")) {
                        return null
                    }
                    return null
                }
                val carrierRaw = json.optString("carrier").takeIf { it.isNotBlank() }
                val info = ExternalNumberInfo(
                    valid = json.optBoolean("valid", false),
                    carrier = carrierRaw?.trim(),
                    countryName = json.optString("country_name").takeIf { it.isNotBlank() },
                    countryCode = json.optString("country_code").takeIf { it.isNotBlank() },
                    lineType = json.optString("line_type").takeIf { it.isNotBlank() }
                )
                ExternalLookupResult(info = info, raw = body)
            }
        }.getOrNull()
    }

    /**
     * Fetch blacklist reports from the smarthomeus3r blacklist API.
     */
    private fun fetchReports(rawNumber: String): ReportLookupResult? {
        val apiKey = BuildConfig.BLACKLIST_API_KEY
        val apiUrl = BuildConfig.BLACKLIST_API_URL.ifBlank { "https://blacklist.smarthomeus3r.space/search" }
        val response = BlacklistSellerClient.request(
            number = rawNumber,
            apiKey = apiKey,
            apiUrl = apiUrl,
            client = httpClient
        ).getOrNull() ?: return null
        return parseReports(response)
    }

    private fun parseReports(raw: String): ReportLookupResult? {
        val json = JSONObject(raw)
        val count = json.optInt("count", 0)
        val results = json.optJSONArray("results")
        val details = mutableListOf<String>()
        if (results != null) {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val index = item.optString("index").trim()
                val sellerInfo = item.optString("seller_info").trim()
                val amount = item.optString("amount").trim()
                if (sellerInfo.isBlank() && amount.isBlank()) continue
                val formatted = formatReportDetail(index, sellerInfo, amount)
                if (formatted.isNotBlank()) details.add(formatted)
            }
        }
        return ReportLookupResult(count = count, details = details, rawHtml = raw)
    }

    private fun formatReportDetail(index: String, sellerInfo: String, amount: String): String {
        val cleaned = sellerInfo.replace(Regex("\\s+"), " ").trim()
        val parts = mutableListOf<String>()
        if (cleaned.isNotBlank()) {
            val tokens = splitReportTokens(cleaned)
            tokens.forEach { token ->
                val pieces = token.split(":", limit = 2).map { it.trim() }
                if (pieces.size == 2) {
                    val label = pieces[0]
                    val value = pieces[1]
                    if (label == "ดูรายละเอียด") return@forEach
                    if (value.isBlank()) {
                        parts.add(label)
                    } else {
                        parts.add("$label: $value")
                    }
                } else {
                    parts.add(token)
                }
            }
        }
        if (parts.isEmpty() && cleaned.isNotBlank()) {
            parts.add(cleaned)
        }
        if (amount.isNotBlank()) {
            parts.add("จำนวนเงิน: $amount")
        }
        if (index.isNotBlank() && parts.isNotEmpty()) {
            parts.add(0, "ลำดับ: $index")
        }
        return parts.joinToString("\n").trim()
    }

    private fun splitReportTokens(text: String): List<String> {
        val tokens = listOf("เลขรายงาน", "สินค้า", "เพจขายของ", "วันที่", "ดูรายละเอียด")
        var normalized = text
        tokens.forEach { token ->
            normalized = normalized.replace(token, "|$token")
        }
        return normalized.split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}

private data class ExternalNumberInfo(
    val valid: Boolean?,
    val carrier: String?,
    val countryName: String?,
    val countryCode: String?,
    val lineType: String?
)

private data class ExternalLookupResult(
    val info: ExternalNumberInfo?,
    val raw: String?
)

private data class ReportLookupResult(
    val count: Int,
    val details: List<String>,
    val rawHtml: String?
)
