package com.sentinel.ai.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.IDN

object DomainAnalyzer {

    private val client = OkHttpClient()

    suspend fun getDomainRegistrationDate(rawInput: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val registrableDomain = extractRegistrableDomain(rawInput)
                val rdapUrl = getRdapEndpoint(registrableDomain)
                val jsonResponse = fetchRdapData(rdapUrl)
                val registrationDate = parseRegistrationDate(jsonResponse)
                Result.success(registrationDate)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun extractRegistrableDomain(rawInput: String): String {
        var domain = rawInput.lowercase().trim()
        if (domain.startsWith("http://")) domain = domain.substring(7)
        if (domain.startsWith("https://")) domain = domain.substring(8)
        if (domain.contains("/")) domain = domain.substringBefore("/")
        domain = IDN.toASCII(domain)

        val parts = domain.split(".")
        if (parts.size < 2) return domain

        val lastPart = parts.last()
        val secondLastPart = parts[parts.size - 2]

        val thaiSlds = setOf("ac", "co", "go", "mi", "or", "net", "in")

        return if (lastPart == "th" && thaiSlds.contains(secondLastPart)) {
            if (parts.size >= 3) {
                "${parts[parts.size - 3]}.${secondLastPart}.${lastPart}"
            } else {
                domain
            }
        } else {
            "${secondLastPart}.${lastPart}"
        }
    }

    private fun getRdapEndpoint(domain: String): String {
        return when {
            domain.endsWith(".com") || domain.endsWith(".net") ->
                "https://rdap.verisign.com/com/v1/domain/$domain"
            domain.endsWith(".th") ->
                "https://rdap.thains.co.th/domain/$domain"
            else ->
                "https://rdap.org/domain/$domain"
        }
    }

    private fun fetchRdapData(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected code $response")
            }
            return response.body?.string() ?: throw IOException("Empty response body")
        }
    }

    private fun parseRegistrationDate(jsonString: String): String {
        val jsonObject = JSONObject(jsonString)
        val eventsArray = jsonObject.optJSONArray("events")
            ?: throw Exception("No 'events' field found in RDAP response.")

        for (i in 0 until eventsArray.length()) {
            val eventObj = eventsArray.optJSONObject(i) ?: continue
            if (eventObj.optString("eventAction") == "registration") {
                val fullDate = eventObj.optString("eventDate")
                if (fullDate.isNotBlank()) return fullDate
            }
        }
        throw Exception("Registration event not found.")
    }
}
