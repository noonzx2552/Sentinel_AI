package com.sentinel.ai.utils

/**
 * In-memory cache for the most recent incoming/outgoing call number and direction.
 * Helps when TelephonyManager delivers null/empty numbers in call state callbacks.
 */
object LastCallStore {
    data class CallInfo(val number: String, val outgoing: Boolean, val timestamp: Long = System.currentTimeMillis())

    @Volatile private var lastCall: CallInfo? = null

    fun setIncoming(number: String) {
        val sanitized = number.ifBlank { return }
        lastCall = CallInfo(sanitized, outgoing = false)
    }

    fun setOutgoing(number: String) {
        val sanitized = number.ifBlank { return }
        lastCall = CallInfo(sanitized, outgoing = true)
    }

    fun get(): CallInfo? = lastCall
}
