package com.sentinel.ai.security

import android.content.Context
import com.sentinel.ai.BuildConfig
import com.sentinel.ai.R
import com.sentinel.ai.ui.DebugSettings
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

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
            PhoneNumberUtil.PhoneNumberType.VOIP -> {
                score -= 6
                notes.add(context.getString(R.string.number_note_voip_risky))
            }
            PhoneNumberUtil.PhoneNumberType.UNKNOWN -> score -= 5
            else -> {}
        }

        // Thai-specific prefix analysis
        if (region == "TH") {
            val thaiDigits = nationalDigits
            when {
                thaiDigits.startsWith("06") -> {
                    score -= 8
                    notes.add(context.getString(R.string.number_note_th_06x_prefix))
                }
                thaiDigits.startsWith("09") -> {
                    // 09x: mixed carriers, slightly elevated spoofing risk
                    score -= 3
                }
                thaiDigits.startsWith("02") || thaiDigits.startsWith("03") ||
                thaiDigits.startsWith("04") || thaiDigits.startsWith("05") ||
                thaiDigits.startsWith("07") -> {
                    // Landline prefixes — lower scam risk but can be spoofed via CLI
                    score -= 2
                }
                else -> {}
            }
        }

        // International number from high-risk region
        val highRiskRegions = setOf("NG", "GH", "CM", "SN", "KE", "ZA", "PK", "BD", "IN", "PH", "RU", "UA", "BY", "MD")
        if (region != null && region in highRiskRegions) {
            score -= 10
            notes.add(context.getString(R.string.number_note_high_risk_region, region))
        }

        val queryNumber = nationalDigits.ifBlank { formattedE164.filter { it.isDigit() } }
        val mocksEnabled = BuildConfig.DEBUG
        val isMockScammer = mocksEnabled && (digitsOnly == "0999999999" || digitsOnly == "66999999999")
        val isMockSafe = mocksEnabled && (digitsOnly == "0812345678" || digitsOnly == "66812345678")
        val isForcedScammer = mocksEnabled && (digitsOnly == "0616581564" || digitsOnly == "66616581564")
        
        val external = if (isMockScammer) {
            // Mock External Info for True carrier
            ExternalLookupResult(
                info = ExternalNumberInfo(
                    valid = true,
                    carrier = "True",
                    countryName = "Thailand",
                    countryCode = "TH",
                    lineType = "Mobile"
                ),
                raw = "Mocked"
            )
        } else if (isMockSafe) {
            // Mock External Info for Safe number (10-digit)
            ExternalLookupResult(
                info = ExternalNumberInfo(
                    valid = true,
                    carrier = "AIS",
                    countryName = "Thailand",
                    countryCode = "TH",
                    lineType = "Mobile"
                ),
                raw = "Mocked"
            )
        } else if (isForcedScammer) {
            ExternalLookupResult(
                info = ExternalNumberInfo(
                    valid = true,
                    carrier = "AIS",
                    countryName = "Thailand",
                    countryCode = "TH",
                    lineType = "Mobile"
                ),
                raw = "forced_scammer"
            )
        } else if (enableExternalLookups) {
            fetchExternalInfo(queryNumber, region)
        } else null
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

        val forcedReports = if (isForcedScammer) ReportLookupResult(count = 2, details = listOf("Fraud attempt via voice call", "Reported by community"), rawHtml = "forced") else null
        val reports = forcedReports ?: if (enableExternalLookups) fetchReports(digitsOnly) else null
        if (isForcedScammer) {
            score = 0
            notes.add("User-flagged scammer")
        } else if (reports != null && reports.count > 0) {
            val reportPenalty = when {
                reports.count >= 10 -> 50
                reports.count >= 5  -> 42
                reports.count >= 3  -> 35
                reports.count == 2  -> 25
                else                -> 15
            }
            score = (score - reportPenalty).coerceAtLeast(0)
            notes.add(context.getString(R.string.checknumber_reports_found_format, reports.count))
            if (reports.count >= 5) {
                notes.add(context.getString(R.string.number_note_many_reports, reports.count))
            }
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
            region = region,
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
        val digitsOnly = queryNumber.filter { it.isDigit() }
        val isMock = BuildConfig.DEBUG && (
            digitsOnly == "0999999999" || digitsOnly == "66999999999" ||
            digitsOnly == "0812345678" || digitsOnly == "66812345678"
        )
        if (isMock) return null // Handled in check()

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
        // Note: numverify free tier only allows HTTP (not HTTPS).
        val url = "http://apilayer.net/api/validate?access_key=$apiKey&number=$encodedNumber&country_code=$countryCode&format=1"
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
        val digitsOnly = rawNumber.filter { it.isDigit() }

        if (BuildConfig.DEBUG) {
            // TEST CASE: Fake Scammer Number for UI Testing (099-999-9999)
            if (digitsOnly == "0999999999" || digitsOnly == "66999999999") {
            val fakeJson = JSONObject().apply {
                put("count", 3)
                put("results", JSONArray().apply {
                    put(JSONObject().apply {
                        put("index", "1")
                        put("seller_info", "สินค้า: iPhone 15 Pro Max | เพจขายของ: Mobile Shop Thailand | วันที่: 10 Jan 2026")
                        put("amount", "25,000 THB")
                    })
                    put(JSONObject().apply {
                        put("index", "2")
                        put("seller_info", "สินค้า: บัตรคอนเสิร์ต Taylor Swift | เพจขายของ: Ticket Resell BKK | วันที่: 05 Jan 2026")
                        put("amount", "8,500 THB")
                    })
                    put(JSONObject().apply {
                        put("index", "3")
                        put("seller_info", "สินค้า: เครื่องใช้ไฟฟ้า | เพจขายของ: Clearance Sale | วันที่: 28 Dec 2025")
                        put("amount", "12,000 THB")
                    })
                })
            }
            return parseReports(fakeJson.toString())
        }

            // TEST CASE: Fake Safe Number for UI Testing (081-234-5678)
            if (digitsOnly == "0812345678" || digitsOnly == "66812345678") {
                return ReportLookupResult(count = 0, details = emptyList(), rawHtml = "Mocked Safe")
            }
        }

        val apiKey = BuildConfig.BLACKLIST_API_KEY
        val apiUrl = BuildConfig.BLACKLIST_API_URL.ifBlank { "https://api.thammasorn.dev/api/search" }
        val response = BlacklistSellerClient.request(
            number = rawNumber,
            apiKey = apiKey,
            apiUrl = apiUrl,
            client = httpClient
        ).getOrNull() ?: return null
        return parseReports(response)
    }

    private fun parseReports(raw: String): ReportLookupResult? {
        val envelope = JSONObject(raw)
        val json = envelope.optJSONObject("result") ?: envelope
        val count = json.optInt("count", 0)
        val total = json.optDouble("total", 0.0).takeIf { it > 0.0 }
        val currency = json.optString("currency").takeIf { it.isNotBlank() } ?: "THB"
        val results = json.optJSONArray("results")
            ?: json.optJSONArray("reports")
        val details = mutableListOf<String>()
        if (results != null) {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val index = item.optString("index").trim()
                val sellerInfo = item.optString("seller_info")
                    .ifBlank { buildThammasornReportInfo(item) }
                    .trim()
                val amount = item.optString("amount").trim()
                if (sellerInfo.isBlank() && amount.isBlank()) continue
                val formatted = formatReportDetail(index, sellerInfo, formatAmount(amount, currency))
                if (formatted.isNotBlank()) details.add(formatted)
            }
        }
        if (total != null && count > 0) {
            details.add(0, "ยอดเสียหายรวม: ${formatAmount(total, currency)}")
        }
        return ReportLookupResult(count = count, details = details, rawHtml = raw)
    }

    private fun buildThammasornReportInfo(item: JSONObject): String {
        val parts = mutableListOf<String>()
        item.optString("report_number").takeIf { it.isNotBlank() }?.let { parts.add("เลขรายงาน: $it") }
        item.optString("seller_name").takeIf { it.isNotBlank() }?.let { parts.add("ชื่อผู้ขาย: $it") }
        item.optString("product").takeIf { it.isNotBlank() }?.let { parts.add("สินค้า: $it") }
        item.optString("seller_page").takeIf { it.isNotBlank() }?.let { parts.add("เพจขายของ: $it") }
        item.optString("date").takeIf { it.isNotBlank() }?.let { parts.add("วันที่: $it") }
        return parts.joinToString(" | ")
    }

    private fun formatAmount(raw: String, currency: String): String {
        if (raw.isBlank()) return raw
        val trimmed = raw.trim()
        return if (trimmed.contains(Regex("[A-Za-zก-ฮ]"))) trimmed else "$trimmed $currency"
    }

    private fun formatAmount(raw: Double, currency: String): String {
        val amountText = if (raw % 1.0 == 0.0) raw.toInt().toString() else raw.toString()
        return "$amountText $currency"
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
        val tokens = listOf("เลขรายงาน", "ชื่อผู้ขาย", "สินค้า", "เพจขายของ", "วันที่", "ดูรายละเอียด")
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
