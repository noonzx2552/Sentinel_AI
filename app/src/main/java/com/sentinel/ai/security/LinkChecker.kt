package com.sentinel.ai.security

import android.util.Log
import com.sentinel.ai.ui.DebugSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.URI
import java.net.InetAddress
import java.security.cert.X509Certificate
import java.security.cert.CertificateParsingException
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.net.ssl.HostnameVerifier

data class LinkCheckResult(
    val normalizedUrl: String,
    val domain: String,
    val score: Int,
    val status: SafetyLevel,
    val https: Boolean,
    val domainAgeDays: Long?,
    val country: String?,
    val issues: List<String>,
    val tlsVersion: String?,
    val certSubject: String?,
    val certIssuer: String?,
    val certNotBefore: Long?,
    val certNotAfter: Long?,
    val deductions: List<Deduction>
)

enum class SafetyLevel { SAFE, CAUTION, DANGER }

class LinkChecker(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun check(rawInput: String): LinkCheckResult = withContext(Dispatchers.IO) {
        val candidates = buildCandidateUrls(rawInput)
        val initial = candidates.first()
        val uri = runCatching { URI(initial) }.getOrElse {
            throw IllegalArgumentException("Invalid URL")
        }
        val host = uri.host ?: throw IllegalArgumentException("Missing host")
        val normalizedHost = IDN.toASCII(host)
        val domain = normalizedHost.removePrefix("www.")

        var score = 60
        val notes = mutableListOf<String>()
        val deductions = mutableListOf<Deduction>()
        val initialResult = performHead(candidates)
        var headResult = initialResult

        // If HTTPS returns a placeholder page, prefer retrying HTTP as primary
        if (headResult?.isPlaceholder == true) {
            val httpCandidate = candidates.firstOrNull { it.startsWith("http://", ignoreCase = true) }
            val httpResult = httpCandidate?.let { performHead(listOf(it)) }
            if (httpResult != null) {
                headResult = httpResult
                notes.add("HTTPS returned placeholder content; using HTTP response instead")
                score -= 5
            }
        }

        val normalized = headResult?.finalUrl ?: initial
        val https = normalized.startsWith("https://", ignoreCase = true)
        if (https) {
            score += 10
        } else {
            score -= 15
            notes.add("Connection is not using HTTPS")
            if (headResult?.usedUrl?.startsWith("https://") == true && !https) {
                notes.add("HTTPS attempt failed; fell back to HTTP")
            }
            deductions.add(Deduction("Not using HTTPS", 15))
        }

        val code = headResult?.code
        val hadHandshake = headResult?.handshake == true
        if (hadHandshake) score += 5 else if (!https) score -= 5
        if (code in 200..399) score += 5 else if (code != null) score -= 5

        val ageDays = fetchDomainAgeDays(domain)
        if (ageDays != null) {
            when {
                ageDays > 365 -> score += 15
                ageDays > 90 -> score += 8
                ageDays < 30 -> {
                    score -= 15
                    notes.add("Very new domain ($ageDays days old)")
                    deductions.add(Deduction("Very new domain", 15))
                }
                ageDays < 90 -> {
                    score -= 8
                    notes.add("New domain ($ageDays days old)")
                    deductions.add(Deduction("New domain", 8))
                }
            }
        }

        val country = fetchCountry(domain)
        if (country != null && country.isNotBlank()) {
            score += 2
        }

        if (domain.endsWith(".xyz") || domain.endsWith(".top") || domain.endsWith(".click")) {
            score -= 8
            notes.add("Suspicious top-level domain")
            deductions.add(Deduction("Suspicious TLD", 8))
        }
        if (normalized.contains("@") || normalized.contains("\\", ignoreCase = true)) {
            score -= 5
            notes.add("URL contains unusual characters")
            deductions.add(Deduction("Unusual characters in URL", 5))
        }

        val certInfo = headResult?.certInfo ?: initialResult?.certInfo
        if (https && certInfo == null) {
            score -= 20
            notes.add("No TLS certificate presented")
            deductions.add(Deduction("No TLS certificate presented", 20))
        }
        if (certInfo != null) {
            val now = System.currentTimeMillis()
            certInfo.notAfter?.let {
                if (now > it) {
                    score -= 20
                    notes.add("Certificate expired")
                    deductions.add(Deduction("Certificate expired", 20))
                }
            }
            certInfo.notBefore?.let {
                if (now < it) {
                    score -= 10
                    notes.add("Certificate not yet valid")
                    deductions.add(Deduction("Certificate not yet valid", 10))
                }
            }
            val hostMatch = matchesHost(certInfo.hosts, domain, normalizedHost)
            if (!hostMatch) {
                score -= 15
                notes.add("Certificate hostname mismatch")
                deductions.add(Deduction("Certificate hostname mismatch", 15))
            }
        }

        val finalScore = score.coerceIn(0, 100)
        val status = when {
            finalScore >= 75 -> SafetyLevel.SAFE
            finalScore >= 50 -> SafetyLevel.CAUTION
            else -> SafetyLevel.DANGER
        }

        LinkCheckResult(
            normalizedUrl = normalized,
            domain = domain,
            score = finalScore,
            status = status,
            https = https,
            domainAgeDays = ageDays,
            country = country,
            issues = notes,
            tlsVersion = certInfo?.tlsVersion,
            certSubject = certInfo?.subject,
            certIssuer = certInfo?.issuer,
            certNotBefore = certInfo?.notBefore,
            certNotAfter = certInfo?.notAfter,
            deductions = deductions
        )
    }

    private fun buildCandidateUrls(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Empty URL")
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            listOf(trimmed)
        } else {
            listOf("https://$trimmed", "http://$trimmed")
        }
    }

private fun performHead(urls: List<String>): HeadResult? {
        urls.forEach { url ->
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "SentinelLinkChecker/1.0")
                .build()
            val response: Response? = runCatching { client.newCall(request).execute() }.getOrNull()
            response?.use {
                val finalUrl = it.request.url.toString()
                val bodySnippet = it.body?.string()?.take(256).orEmpty()
                val handshake = it.handshake
                val cert = handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                val certInfo = cert?.let { c ->
                    val hosts = extractHosts(c)
                    CertInfo(
                        subject = extractCn(c.subjectX500Principal?.name) ?: c.subjectX500Principal?.name,
                        issuer = extractCn(c.issuerX500Principal?.name) ?: c.issuerX500Principal?.name,
                        notBefore = c.notBefore?.time,
                        notAfter = c.notAfter?.time,
                        tlsVersion = handshake.tlsVersion?.javaName,
                        hosts = hosts
                    )
                }
                return HeadResult(
                    usedUrl = url,
                    finalUrl = finalUrl,
                    code = it.code,
                    handshake = it.handshake != null,
                    bodySnippet = bodySnippet,
                    isPlaceholder = bodySnippet.contains("webserver is functioning normally", ignoreCase = true),
                    certInfo = certInfo
                )
            }
            response?.closeQuietly()
        }
        return null
    }

    private fun fetchDomainAgeDays(domain: String): Long? {
        val rdapUrls = listOf(
            "https://rdap.org/domain/$domain",
            "https://rdap.verisign.com/com/v1/domain/$domain",
            "https://rdap.verisign.com/net/v1/domain/$domain",
            "https://rdap.cloudflare.com/rdap/v1/domain/$domain"
        )
        val body = rdapUrls.firstNotNullOfOrNull { url ->
            val request = Request.Builder().url(url).get().build()
            runCatching { client.newCall(request).execute().use { it.body?.string() } }.getOrNull()
        } ?: return null

        return runCatching {
            val events = JSONObject(body).optJSONArray("events") ?: return null
            (0 until events.length())
                .mapNotNull { idx ->
                    val obj = events.optJSONObject(idx) ?: return@mapNotNull null
                    if (obj.optString("eventAction") == "registration") obj.optString("eventDate") else null
                }
                .firstOrNull()
                ?.let { Instant.parse(it) }
                ?.until(Instant.now(), ChronoUnit.DAYS)
        }.getOrNull()
    }

    private fun fetchCountry(domain: String): String? {
        // Try RDAP (registrant country) first
        val rdapUrls = listOf(
            "https://rdap.org/domain/$domain",
            "https://rdap.cloudflare.com/rdap/v1/domain/$domain",
            "https://rdap.verisign.com/com/v1/domain/$domain",
            "https://rdap.verisign.com/net/v1/domain/$domain"
        )
        rdapUrls.forEach { url ->
            val body = runCatching {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { it.body?.string() }
            }.getOrNull()
            if (!body.isNullOrBlank()) {
                parseCountryFromRdap(body)?.let { return it }
            }
        }

        val ipApi = Request.Builder()
            .url("http://ip-api.com/json/$domain?fields=status,country")
            .get()
            .build()
        val ipApiBody = runCatching { client.newCall(ipApi).execute().use { it.body?.string() } }.getOrNull()
        val country = ipApiBody?.let {
            runCatching {
                val json = JSONObject(it)
                if (json.optString("status") == "success") json.optString("country", null) else null
            }.getOrNull()
        }
        if (!country.isNullOrBlank()) return country

        val ipwhois = Request.Builder()
            .url("https://ipwho.is/$domain?fields=country")
            .get()
            .build()
        val ipwhoisBody = runCatching { client.newCall(ipwhois).execute().use { it.body?.string() } }.getOrNull()
        if (ipwhoisBody.isNullOrBlank()) return null
        val ipwhoisCountry = runCatching { JSONObject(ipwhoisBody).optString("country", null) }.getOrNull()
        if (!ipwhoisCountry.isNullOrBlank()) return ipwhoisCountry

        val ipapi = Request.Builder()
            .url("https://ipapi.co/$domain/json/")
            .get()
            .build()
        val ipapiBody = runCatching { client.newCall(ipapi).execute().use { it.body?.string() } }.getOrNull()
        if (!ipapiBody.isNullOrBlank()) {
            runCatching { JSONObject(ipapiBody).optString("country_name", null) }.getOrNull()?.let { return it }
        }

        resolveIpCountry(domain)?.let { return it }

        return fetchCountryFromSite24x7(domain, client)
    }
}

private data class HeadResult(
    val usedUrl: String,
    val finalUrl: String,
    val code: Int?,
    val handshake: Boolean,
    val bodySnippet: String,
    val isPlaceholder: Boolean,
    val certInfo: CertInfo?
)

private data class CertInfo(
    val subject: String?,
    val issuer: String?,
    val notBefore: Long?,
    val notAfter: Long?,
    val tlsVersion: String?,
    val hosts: List<String>
)

private fun extractCn(dn: String?): String? {
    dn ?: return null
    return dn.split(",")
        .map { it.trim() }
        .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
        ?.substringAfter("=")
}

data class Deduction(
    val reason: String,
    val points: Int
)

private fun extractHosts(cert: X509Certificate): List<String> {
    val sans = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
    val sanHosts = sans.mapNotNull { entry ->
        if (entry.size >= 2 && entry[0] == 2) entry[1]?.toString() else null
    }
    val cn = extractCn(cert.subjectX500Principal?.name)
    return (sanHosts + listOfNotNull(cn)).distinct()
}

private fun matchesHost(hosts: List<String>, domain: String, fullHost: String): Boolean {
    if (hosts.isEmpty()) return false
    val candidates = listOf(domain, fullHost)
    return hosts.any { pattern ->
        candidates.any { host ->
            if (pattern.startsWith("*.")) {
                val suffix = pattern.removePrefix("*.")
                host == suffix || host.endsWith(".$suffix")
            } else {
                host.equals(pattern, ignoreCase = true)
            }
        }
    }
}

private fun parseCountryFromRdap(body: String): String? {
    return runCatching {
        val json = JSONObject(body)
        val entities = json.optJSONArray("entities") ?: return@runCatching null
        extractCountryFromEntities(entities) ?: json.optString("country", null)
    }.getOrNull()
}

private fun extractCountryFromEntities(entities: JSONArray): String? {
    for (i in 0 until entities.length()) {
        val entity = entities.optJSONObject(i) ?: continue
        val vcard = entity.optJSONArray("vcardArray")?.optJSONArray(1) ?: continue
        for (j in 0 until vcard.length()) {
            val entry = vcard.optJSONArray(j) ?: continue
            val key = entry.optString(0)
            if (key.equals("country-name", ignoreCase = true) || key.equals("country", ignoreCase = true)) {
                val value = entry.optString(3, null)
                if (!value.isNullOrBlank()) return value
            }
            if (key.equals("adr", ignoreCase = true)) {
                val adrValues = entry.optJSONArray(3)
                val country = adrValues?.optString(6, null)
                if (!country.isNullOrBlank()) return country
            }
        }
    }
    return null
}

private fun fetchCountryFromSite24x7(domain: String, client: OkHttpClient): String? {
    // JSON endpoint
    val jsonBody = FormBody.Builder()
        .add("hostname", domain)
        .add("fromTab", "false")
        .build()
    val jsonRequest = Request.Builder()
        .url("https://www.site24x7.com/tools/find-location")
        .post(jsonBody)
        .header("User-Agent", "SentinelLinkChecker/1.0")
        .header("Accept", "application/json")
        .build()
    val jsonCountry = runCatching {
        client.newCall(jsonRequest).execute().use { resp ->
            val text = resp.body?.string()
            debugLog("site24x7 json status=${resp.code} len=${text?.length}")
            if (text.isNullOrBlank()) return@use null
            val obj = JSONObject(text)
            val respObj = obj.optJSONObject("response")
            val name = respObj?.optString("countryname").orEmpty()
            val code = respObj?.optString("countrycode").orEmpty()
            val combined = when {
                name.isNotBlank() && code.isNotBlank() -> "$name ($code)"
                name.isNotBlank() -> name
                code.isNotBlank() -> code
                else -> null
            }
            combined
        }
    }.getOrNull()
    if (!jsonCountry.isNullOrBlank()) return jsonCountry

    // Fallback HTML scrape
    val formBody = FormBody.Builder()
        .add("hostName", domain)
        .build()
    val request = Request.Builder()
        .url("https://www.site24x7.com/tools/find-website-location.html")
        .post(formBody)
        .header("User-Agent", "SentinelLinkChecker/1.0")
        .build()
    val body = runCatching {
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string()
            val snippet = text?.take(200)?.replace(Regex("\\s+"), " ") ?: ""
            debugLog("site24x7 html status=${resp.code} snippet='${snippet.take(120)}'")
            text
        }
    }.getOrNull()
    if (body.isNullOrBlank()) return null
    debugLog("site24x7 html response length=${body.length}")
    val regex = Regex(
        pattern = """<td[^>]*data-title=["']Country Name["'][^>]*>(.*?)</td>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    val match = regex.find(body)
    val country = match?.groupValues?.getOrNull(1)?.replace(Regex("<.*?>"), "")?.trim()?.takeIf { it.isNotEmpty() }
    debugLog("site24x7 parsed country=$country")
    return country
}

private fun debugLog(msg: String) {
    if (DebugSettings.isDebugEnabled.value) {
        Log.d("LinkChecker", msg)
    }
}

private fun resolveIpCountry(host: String): String? {
    val ip = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull() ?: return null
    debugLog("DNS resolved $host -> $ip")

    val ipApi = Request.Builder()
        .url("http://ip-api.com/json/$ip?fields=status,country")
        .get()
        .build()
    val ipApiBody = runCatching { defaultClient().newCall(ipApi).execute().use { it.body?.string() } }.getOrNull()
    if (!ipApiBody.isNullOrBlank()) {
        val country = runCatching {
            val json = JSONObject(ipApiBody)
            if (json.optString("status") == "success") json.optString("country", null) else null
        }.getOrNull()
        if (!country.isNullOrBlank()) return country
    }

    val ipwhois = Request.Builder()
        .url("https://ipwho.is/$ip?fields=country")
        .get()
        .build()
    val ipwhoisBody = runCatching { defaultClient().newCall(ipwhois).execute().use { it.body?.string() } }.getOrNull()
    if (!ipwhoisBody.isNullOrBlank()) {
        val country = runCatching { JSONObject(ipwhoisBody).optString("country", null) }.getOrNull()
        if (!country.isNullOrBlank()) return country
    }

    val ipapi = Request.Builder()
        .url("https://ipapi.co/$ip/json/")
        .get()
        .build()
    val ipapiBody = runCatching { defaultClient().newCall(ipapi).execute().use { it.body?.string() } }.getOrNull()
    if (!ipapiBody.isNullOrBlank()) {
        val country = runCatching { JSONObject(ipapiBody).optString("country_name", null) }.getOrNull()
        if (!country.isNullOrBlank()) return country
    }

    return null
}

private fun defaultClient(): OkHttpClient = OkHttpClient()
