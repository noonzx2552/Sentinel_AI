package com.sentinel.ai.security

import android.content.Context
import com.sentinel.ai.BuildConfig
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
    val issues: List<String>
)

class NumberChecker(context: Context) {

    private val phoneNumberUtil: PhoneNumberUtil = PhoneNumberUtil.createInstance(context)
    private val httpClient = OkHttpClient()

    suspend fun check(raw: String): NumberCheckResult = withContext(Dispatchers.Default) {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) throw IllegalArgumentException("Empty phone number")

        val defaultRegion = Locale.getDefault().country
        val number = runCatching { phoneNumberUtil.parse(cleaned, defaultRegion) }
            .getOrElse { throw IllegalArgumentException("Invalid phone number format") }

        val valid = phoneNumberUtil.isValidNumber(number)
        val region = phoneNumberUtil.getRegionCodeForNumber(number)
        val type = phoneNumberUtil.getNumberType(number)
        val formattedE164 = phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)

        var score = 55
        val notes = mutableListOf<String>()
        var carrier: String? = null
        var countryName: String? = null
        var externalLineType: String? = null

        if (valid) {
            score += 15
        } else {
            score -= 25
            notes.add("Number format is not valid for region $region")
        }

        when (type) {
            PhoneNumberUtil.PhoneNumberType.MOBILE -> score += 5
            PhoneNumberUtil.PhoneNumberType.FIXED_LINE -> score += 3
            PhoneNumberUtil.PhoneNumberType.TOLL_FREE -> {
                score -= 5
                notes.add("Toll-free numbers can be spoofed")
            }
            PhoneNumberUtil.PhoneNumberType.PREMIUM_RATE -> {
                score -= 25
                notes.add("Premium-rate numbers are risky for scams")
            }
            PhoneNumberUtil.PhoneNumberType.PERSONAL_NUMBER -> score -= 8
            PhoneNumberUtil.PhoneNumberType.VOIP -> score -= 6
            PhoneNumberUtil.PhoneNumberType.UNKNOWN -> score -= 5
            else -> {}
        }

        val external = fetchExternalInfo(formattedE164)
        external?.let { info ->
            carrier = info.carrier ?: carrier
            countryName = info.countryName ?: countryName
            externalLineType = info.lineType ?: externalLineType
            if (info.valid == false) {
                score -= 25
                notes.add("External validation: number is invalid")
            }
            when (info.lineType?.lowercase(Locale.getDefault())) {
                "premium rate" -> {
                    score -= 25
                    notes.add("External check: premium-rate number")
                }
                "toll-free" -> {
                    score -= 5
                    notes.add("External check: toll-free number")
                }
                "voip" -> {
                    score -= 6
                    notes.add("External check: VOIP number")
                }
                else -> {}
            }
        }

        val finalScore = score.coerceIn(0, 100)
        val status = when {
            finalScore >= 75 -> SafetyLevel.SAFE
            finalScore >= 50 -> SafetyLevel.CAUTION
            else -> SafetyLevel.DANGER
        }

        NumberCheckResult(
            rawInput = cleaned,
            formattedE164 = formattedE164,
            displayNumber = phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL),
            region = countryName ?: region,
            carrier = carrier,
            countryName = countryName,
            externalLineType = externalLineType,
            numberType = type,
            score = finalScore,
            status = status,
            issues = notes
        )
    }

    private fun fetchExternalInfo(formattedE164: String): ExternalNumberInfo? {
        val apiKey = BuildConfig.PHONE_REP_API_KEY
        if (apiKey.isBlank()) return null
        val encoded = URLEncoder.encode(formattedE164, "UTF-8")
        val url = "https://phonevalidation.abstractapi.com/v1/?api_key=$apiKey&phone=$encoded"
        val request = Request.Builder().url(url).get().build()
        return runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) return null
                val json = JSONObject(body)
                ExternalNumberInfo(
                    valid = json.optBoolean("valid", false),
                    carrier = json.optString("carrier").takeIf { it.isNotBlank() },
                    countryName = json.optJSONObject("country")?.optString("name").takeIf { !it.isNullOrBlank() },
                    countryCode = json.optJSONObject("country")?.optString("code").takeIf { !it.isNullOrBlank() },
                    lineType = json.optString("line_type").takeIf { it.isNotBlank() }
                )
            }
        }.getOrNull()
    }
}

private data class ExternalNumberInfo(
    val valid: Boolean?,
    val carrier: String?,
    val countryName: String?,
    val countryCode: String?,
    val lineType: String?
)
