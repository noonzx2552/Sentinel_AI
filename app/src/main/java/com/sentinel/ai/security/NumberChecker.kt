package com.sentinel.ai.security

import android.content.Context
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class NumberCheckResult(
    val rawInput: String,
    val formattedE164: String,
    val displayNumber: String,
    val region: String?,
    val carrier: String?,
    val numberType: PhoneNumberUtil.PhoneNumberType,
    val score: Int,
    val status: SafetyLevel,
    val issues: List<String>
)

class NumberChecker(context: Context) {

    private val phoneNumberUtil: PhoneNumberUtil = PhoneNumberUtil.createInstance(context)

    suspend fun check(raw: String): NumberCheckResult = withContext(Dispatchers.Default) {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) throw IllegalArgumentException("Empty phone number")

        val defaultRegion = Locale.getDefault().country
        val number = runCatching { phoneNumberUtil.parse(cleaned, defaultRegion) }
            .getOrElse { throw IllegalArgumentException("Invalid phone number format") }

        val valid = phoneNumberUtil.isValidNumber(number)
        val region = phoneNumberUtil.getRegionCodeForNumber(number)
        val type = phoneNumberUtil.getNumberType(number)

        var score = 55
        val notes = mutableListOf<String>()

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

        val finalScore = score.coerceIn(0, 100)
        val status = when {
            finalScore >= 75 -> SafetyLevel.SAFE
            finalScore >= 50 -> SafetyLevel.CAUTION
            else -> SafetyLevel.DANGER
        }

        NumberCheckResult(
            rawInput = cleaned,
            formattedE164 = phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164),
            displayNumber = phoneNumberUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL),
            region = region,
            carrier = null,
            numberType = type,
            score = finalScore,
            status = status,
            issues = notes
        )
    }
}
