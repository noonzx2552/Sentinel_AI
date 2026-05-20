package com.sentinel.ai.security

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

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
            return Result.failure(IllegalStateException("Missing Blacklist bearer token"))
        }

        var lastError: String? = null
        repeat(retries.coerceAtLeast(1)) {
            val body = JSONObject()
                .put("bank_number", digitsOnly)
                .toString()
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url(apiUrl)
                .post(body)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
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

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
