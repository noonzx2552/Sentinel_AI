package com.sentinel.ai.security

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

object BlacklistSellerClient {

    fun request(
        number: String,
        apiKey: String,
        apiUrl: String,
        client: OkHttpClient,
        retries: Int = 2
    ): Result<String> {
        val digitsOnly = number.filter { it.isDigit() }
        if (digitsOnly.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing phone number"))
        }
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Missing Blacklist API key"))
        }

        var lastError: String? = null
        repeat(retries.coerceAtLeast(1)) {
            val body = FormBody.Builder()
                .add("phone_number", digitsOnly)
                .build()
            val request = Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("X-API-Key", apiKey)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()
            try {
                client.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && raw.isNotBlank()) {
                        return Result.success(raw)
                    }
                    lastError = "HTTP ${resp.code}: $raw"
                }
            } catch (e: Exception) {
                lastError = e.message ?: "request failed"
            }
        }

        return Result.failure(IllegalStateException(lastError ?: "request failed"))
    }
}
