package com.sentinel.ai.security

import android.content.Context
import android.util.Log
import com.sentinel.ai.R
import com.sentinel.ai.ui.DebugSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LinkCheckResult(
    val normalizedUrl: String,
    val domain: String,
    val resolvedIp: String?,
    val score: Int,
    val status: SafetyLevel,
    val https: Boolean,
    val domainAgeDays: Long?,
    val registrationDate: String?,
    val country: String?,
    val issues: List<String>,
    val tlsVersion: String?,
    val certSubject: String?,
    val certIssuer: String?,
    val certNotBefore: Long?,
    val certNotAfter: Long?,
    val certFingerprint: String?,
    val certError: CertErrorReason?,
    val certErrorDetail: String?,
    val deductions: List<Deduction>
)

enum class SafetyLevel { SAFE, CAUTION, DANGER, UNKNOWN }

enum class CertErrorReason {
    TLS_HANDSHAKE_FAILED,
    REDIRECTED_TO_HTTP,
    TIMEOUT,
    DNS_FAIL
}

data class Deduction(
    val reason: String,
    val points: Int
)

data class CertInfo(
    val subject: String?,
    val issuer: String?,
    val notBefore: Long?,
    val notAfter: Long?,
    val tlsVersion: String?,
    val hosts: List<String>,
    val fingerprintSha256: String?
)

private data class HeadResult(
    val usedUrl: String,
    val finalUrl: String,
    val finalHost: String?,
    val code: Int?,
    val handshake: Boolean,
    val bodySnippet: String,
    val isPlaceholder: Boolean,
    val certInfo: CertInfo?,
    val redirectCount: Int,
    val crossDomainRedirect: Boolean,
    val contentType: String?,
    val securityHeaders: Map<String, String>
)

class LinkChecker(
    private val context: Context,
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(rawInput: String): LinkCheckResult = withContext(Dispatchers.IO) {
        val candidates = buildCandidateUrls(rawInput)
        val initial = candidates.first()
        val uri = runCatching { URI(initial) }.getOrElse {
            throw IllegalArgumentException(context.getString(R.string.error_invalid_url))
        }
        val host = uri.host ?: throw IllegalArgumentException(context.getString(R.string.error_missing_host))
        val normalizedHost = IDN.toASCII(host)
        val domain = normalizedHost.removePrefix("www.")
        val resolvedIp = resolveIpAddress(normalizedHost)
        val registrationDate = runCatching { DomainAnalyzer.getDomainRegistrationDate(domain).getOrNull() }.getOrNull()

        var score = 100
        val notes = mutableListOf<String>()
        val deductions = mutableListOf<Deduction>()
        fun deduct(points: Int, reason: String, note: String? = null) {
            score -= points
            deductions.add(Deduction(reason, points))
            if (!note.isNullOrBlank()) {
                notes.add(note)
            }
        }
        val initialResult = performHead(candidates)
        var headResult = initialResult

        // If HTTPS returns a placeholder page, prefer retrying HTTP as primary
        if (headResult?.isPlaceholder == true) {
            val httpCandidate = candidates.firstOrNull { it.startsWith("http://", ignoreCase = true) }
            val httpResult = httpCandidate?.let { performHead(listOf(it)) }
            if (httpResult != null) {
                headResult = httpResult
                deduct(
                    points = 10,
                    reason = context.getString(R.string.link_deduction_https_placeholder),
                    note = context.getString(R.string.link_note_https_placeholder)
                )
            }
        }

        val normalized = headResult?.finalUrl ?: initial
        val certHost = hostFromUrl(normalized) ?: normalizedHost
        val certDomain = certHost.removePrefix("www.")
        val https = normalized.startsWith("https://", ignoreCase = true)
        val normalizedUri = runCatching { URI(normalized) }.getOrNull()
        if (!https) {
            deduct(
                points = 30,
                reason = context.getString(R.string.link_deduction_not_https),
                note = context.getString(R.string.link_note_not_https)
            )
            if (headResult?.usedUrl?.startsWith("https://") == true && !https) {
                notes.add(context.getString(R.string.link_note_https_fallback))
            }
        }

        val code = headResult?.code
        val hadHandshake = headResult?.handshake == true
        if (!hadHandshake && https) {
            deduct(points = 10, reason = context.getString(R.string.link_deduction_tls_handshake_failed))
        }
        if (code in 200..399) {
            // Good HTTP status yields no penalty.
        } else if (code != null) {
            deduct(points = 10, reason = context.getString(R.string.link_deduction_http_status))
        }
        if ((headResult?.redirectCount ?: 0) >= 3) {
            deduct(
                points = 8,
                reason = context.getString(R.string.link_deduction_many_redirects),
                note = context.getString(R.string.link_note_many_redirects)
            )
        }
        if (headResult?.crossDomainRedirect == true) {
            deduct(
                points = 10,
                reason = context.getString(R.string.link_deduction_cross_domain_redirect),
                note = context.getString(R.string.link_note_cross_domain_redirect_format, headResult.finalHost ?: certHost)
            )
        }

        val ageDays = runCatching { registrationDate?.let { Instant.parse(it).until(Instant.now(), ChronoUnit.DAYS) } }
            .getOrNull()
            ?: fetchDomainAgeDays(domain)
        if (ageDays != null) {
            when {
                ageDays < 30 -> {
                    deduct(
                        points = 10,
                        reason = context.getString(R.string.link_deduction_domain_very_new),
                        note = context.getString(R.string.link_note_domain_very_new_format, ageDays)
                    )
                }
                ageDays < 90 -> {
                    deduct(
                        points = 10,
                        reason = context.getString(R.string.link_deduction_domain_new),
                        note = context.getString(R.string.link_note_domain_new_format, ageDays)
                    )
                }
                else -> {
                    // Older domains yield no penalty.
                }
            }
        } else {
            deduct(
                points = 10,
                reason = context.getString(R.string.link_deduction_domain_age_unknown),
                note = context.getString(R.string.link_note_domain_age_unknown)
            )
        }

        val country = fetchCountry(domain)

        val suspiciousTlds = listOf(
            ".xyz", ".top", ".click", ".tk", ".ml", ".ga", ".cf", ".gq",
            ".buzz", ".icu", ".cyou", ".fun", ".live", ".online",
            ".monster", ".sbs", ".vip", ".work", ".loan", ".win"
        )
        if (suspiciousTlds.any { domain.endsWith(it) }) {
            deduct(
                points = 10,
                reason = context.getString(R.string.link_deduction_suspicious_tld),
                note = context.getString(R.string.link_note_suspicious_tld)
            )
        }
        if (looksLikeMicrosoftImpersonation(domain)) {
            deduct(
                points = 10,
                reason = context.getString(R.string.link_deduction_impersonation),
                note = context.getString(R.string.link_note_impersonation)
            )
        }
        if (looksLikeBrandImpersonation(domain)) {
            deduct(
                points = 15,
                reason = context.getString(R.string.link_deduction_brand_impersonation),
                note = context.getString(R.string.link_note_brand_impersonation)
            )
        }
        if (containsGamblingKeywords(domain)) {
            deduct(
                points = 20,
                reason = context.getString(R.string.link_deduction_gambling_keywords),
                note = context.getString(R.string.link_note_gambling_keywords)
            )
        }
        if (containsScamKeywords(domain)) {
            deduct(
                points = 15,
                reason = context.getString(R.string.link_deduction_scam_keywords),
                note = context.getString(R.string.link_note_scam_keywords)
            )
        }
        if (hasSuspiciousSubdomainKeyword(certHost)) {
            deduct(
                points = 8,
                reason = context.getString(R.string.link_deduction_suspicious_subdomain),
                note = context.getString(R.string.link_note_suspicious_subdomain)
            )
        }
        if (containsKnownShortener(domain)) {
            deduct(
                points = 15,
                reason = context.getString(R.string.link_deduction_url_shortener),
                note = context.getString(R.string.link_note_url_shortener)
            )
        }
        if (normalized.contains("@") || normalized.contains("\\", ignoreCase = true)) {
            deduct(
                points = 10,
                reason = context.getString(R.string.link_deduction_unusual_chars),
                note = context.getString(R.string.link_note_unusual_chars)
            )
        }
        if (isIpLiteralHost(certHost)) {
            deduct(
                points = 20,
                reason = context.getString(R.string.link_deduction_ip_literal),
                note = context.getString(R.string.link_note_ip_literal)
            )
        }
        if (certHost.contains("xn--", ignoreCase = true)) {
            deduct(
                points = 10,
                reason = context.getString(R.string.link_deduction_punycode_host),
                note = context.getString(R.string.link_note_punycode_host)
            )
        }
        if (isVeryDeepSubdomain(certHost)) {
            deduct(
                points = 6,
                reason = context.getString(R.string.link_deduction_deep_subdomain),
                note = context.getString(R.string.link_note_deep_subdomain)
            )
        }
        if (normalized.length > 140) {
            deduct(
                points = 6,
                reason = context.getString(R.string.link_deduction_very_long_url),
                note = context.getString(R.string.link_note_very_long_url)
            )
        }
        val port = normalizedUri?.port ?: -1
        if (port > 0 && port != 80 && port != 443) {
            deduct(
                points = 10,
                reason = context.getString(R.string.link_deduction_unusual_port),
                note = context.getString(R.string.link_note_unusual_port_format, port)
            )
        }
        val querySignals = evaluateQuerySignals(normalizedUri)
        if (querySignals.paramCountHigh) {
            deduct(
                points = 5,
                reason = context.getString(R.string.link_deduction_query_too_many_params),
                note = context.getString(R.string.link_note_query_too_many_params)
            )
        }
        if (querySignals.hasSensitiveKeys) {
            deduct(
                points = 10,
                reason = context.getString(R.string.link_deduction_sensitive_query_params),
                note = context.getString(R.string.link_note_sensitive_query_params)
            )
        }
        if (looksLikeCredentialHarvestPath(normalizedUri?.path.orEmpty())) {
            val corroborating = (ageDays != null && ageDays < 90) ||
                headResult?.crossDomainRedirect == true ||
                isIpLiteralHost(certHost) ||
                !https
            if (corroborating) {
                deduct(
                    points = 10,
                    reason = context.getString(R.string.link_deduction_suspicious_path),
                    note = context.getString(R.string.link_note_suspicious_path)
                )
            }
        }

        val missingSecurityHeaders = evaluateMissingSecurityHeaders(headResult?.securityHeaders.orEmpty())
        val successfulResponse = (headResult?.code ?: 0) in 200..299
        if (https && successfulResponse && missingSecurityHeaders.isNotEmpty()) {
            val headerPenalty = (missingSecurityHeaders.size * 2).coerceAtMost(8)
            deduct(
                points = headerPenalty,
                reason = context.getString(R.string.link_deduction_missing_security_headers),
                note = context.getString(
                    R.string.link_note_missing_security_headers_format,
                    missingSecurityHeaders.joinToString(", ")
                )
            )
        }
        val contentType = headResult?.contentType.orEmpty()
        if (looksLikeDownloadPayload(contentType)) {
            deduct(
                points = 8,
                reason = context.getString(R.string.link_deduction_suspicious_content_type),
                note = context.getString(R.string.link_note_suspicious_content_type_format, contentType)
            )
        }
        if (containsPhishingContent(headResult?.bodySnippet.orEmpty())) {
            deduct(
                points = 12,
                reason = context.getString(R.string.link_deduction_phishing_content),
                note = context.getString(R.string.link_note_phishing_content)
            )
        }

        var certInfo = headResult?.certInfo ?: initialResult?.certInfo
        var certError: CertErrorReason? = null
        var certErrorDetail: String? = null

        if (https && certInfo == null) {
            debugLog("Fallback CertProbe started for $domain")
            val probeResult = CertProbeClient.probe(domain)
            if (probeResult.certInfo != null) {
                certInfo = probeResult.certInfo
                debugLog("CertProbe handshake success tls=${probeResult.certInfo.tlsVersion}")
            } else {
                certError = probeResult.errorReason ?: CertErrorReason.TLS_HANDSHAKE_FAILED
                certErrorDetail = probeResult.errorMessage
                debugLog("CertProbe failed: ${certError.name} detail=$certErrorDetail")
            }
        }

        if (certInfo != null) {
            val now = System.currentTimeMillis()
            certInfo.notAfter?.let {
                if (now > it) {
                    deduct(
                        points = 10,
                        reason = context.getString(R.string.link_deduction_cert_expired),
                        note = context.getString(R.string.link_note_cert_expired)
                    )
                }
            }
            certInfo.notBefore?.let {
                if (now < it) {
                    deduct(
                        points = 10,
                        reason = context.getString(R.string.link_deduction_cert_not_yet_valid),
                        note = context.getString(R.string.link_note_cert_not_yet_valid)
                    )
                }
            }
            val hostMatch = matchesHost(certInfo.hosts, certDomain, certHost)
            if (!hostMatch) {
                deduct(
                    points = 10,
                    reason = context.getString(R.string.link_deduction_cert_hostname_mismatch),
                    note = context.getString(R.string.link_note_cert_hostname_mismatch)
                )
            }
            if (certInfo.tlsVersion.equals("TLSv1", ignoreCase = true) ||
                certInfo.tlsVersion.equals("TLSv1.1", ignoreCase = true)
            ) {
                deduct(
                    points = 10,
                    reason = context.getString(R.string.link_deduction_weak_tls),
                    note = context.getString(R.string.link_note_weak_tls)
                )
            }
        } else if (https && certError == CertErrorReason.REDIRECTED_TO_HTTP) {
            deduct(
                points = 15,
                reason = context.getString(R.string.link_deduction_https_downgrade),
                note = context.getString(R.string.link_note_https_downgrade)
            )
        }

        val finalScore = if (deductions.isEmpty()) 100 else score.coerceIn(0, 100)
        val status = when {
            finalScore >= 75 -> SafetyLevel.SAFE
            finalScore >= 50 -> SafetyLevel.CAUTION
            else -> SafetyLevel.DANGER
        }

        LinkCheckResult(
            normalizedUrl = normalized,
            domain = domain,
            resolvedIp = resolvedIp,
            score = finalScore,
            status = status,
            https = https,
            domainAgeDays = ageDays,
            registrationDate = registrationDate,
            country = country,
            issues = notes,
            tlsVersion = certInfo?.tlsVersion,
            certSubject = certInfo?.subject,
            certIssuer = certInfo?.issuer,
            certNotBefore = certInfo?.notBefore,
            certNotAfter = certInfo?.notAfter,
            certFingerprint = certInfo?.fingerprintSha256,
            certError = certError,
            certErrorDetail = certErrorDetail,
            deductions = deductions
        )
    }

    private fun buildCandidateUrls(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException(context.getString(R.string.error_empty_url))
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
                        tlsVersion = handshake.tlsVersion.javaName,
                        hosts = hosts,
                        fingerprintSha256 = fingerprintSha256(c)
                    )
                }
                if (handshake == null) {
                    debugLog("performHead no handshake url=$url code=${it.code}")
                } else {
                    debugLog("performHead handshake ok tls=${handshake.tlsVersion} cipher=${handshake.cipherSuite}")
                }
                return HeadResult(
                    usedUrl = url,
                    finalUrl = finalUrl,
                    finalHost = it.request.url.host,
                    code = it.code,
                    handshake = it.handshake != null,
                    bodySnippet = bodySnippet,
                    isPlaceholder = bodySnippet.contains("webserver is functioning normally", ignoreCase = true),
                    certInfo = certInfo,
                    redirectCount = countRedirects(it),
                    crossDomainRedirect = isCrossDomainRedirect(url, finalUrl),
                    contentType = it.header("Content-Type"),
                    securityHeaders = mapOf(
                        "Strict-Transport-Security" to it.header("Strict-Transport-Security").orEmpty(),
                        "Content-Security-Policy" to it.header("Content-Security-Policy").orEmpty(),
                        "X-Frame-Options" to it.header("X-Frame-Options").orEmpty(),
                        "X-Content-Type-Options" to it.header("X-Content-Type-Options").orEmpty(),
                        "Referrer-Policy" to it.header("Referrer-Policy").orEmpty()
                    )
                )
            }
            response?.closeQuietly()
        }
        return null
    }

    private fun hostFromUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        return runCatching { IDN.toASCII(host) }.getOrNull() ?: host
    }

    private fun looksLikeMicrosoftImpersonation(domain: String): Boolean {
        val lower = domain.lowercase(Locale.getDefault())
        return lower.contains("rnicrosoft") ||
            lower.contains("micros0ft") ||
            lower.contains("microsof1") ||
            lower.contains("m1crosoft")
    }

    private fun looksLikeBrandImpersonation(domain: String): Boolean {
        val lower = domain.lowercase(Locale.getDefault())
        val brands = listOf(
            "g00gle", "go0gle", "g0ogle", "gogle", "googl3", "googIe",
            "app1e", "appl3", "apppe", "icloud-", "-icloud",
            "faceb00k", "facebok", "facbook", "fb-login",
            "paypa1", "paypaI", "paypai",
            "amaz0n", "arnazon", "arnaz0n",
            "netflix-", "-netflix",
            "line-th", "lineth-", "line-official-",
            "kasikorn-", "kbank-", "-kbank",
            "scb-", "-scb", "scbthai",
            "krungsri-", "-krungsri",
            "bbl-", "-bbl", "bangkokbank-",
            "ttb-", "-ttb",
            "truemoney-", "-truemoney",
            "promptpay-", "-promptpay"
        )
        return brands.any { lower.contains(it) }
    }

    private fun containsGamblingKeywords(domain: String): Boolean {
        val lower = domain.lowercase(Locale.getDefault())
        val labelPattern = Regex("""(^|[.\-])(ufa\d*|888\d*|777\d*|666\d*)([.\-]|$)""")
        return labelPattern.containsMatchIn(lower)
    }

    private fun containsScamKeywords(domain: String): Boolean {
        val lower = domain.lowercase(Locale.getDefault())
        val keywords = listOf(
            "sagame", "sexy-baccarat", "sa-gaming",
            "baccarat", "casino", "poker", "slot-",
            "betflix", "betflip", "betwin",
            "lotto", "huay", "หวย",
            "pgslot", "pgslots", "pgsoft-",
            "joker123", "jokerslot",
            "ambbet", "ambslot",
            "winbet", "winslot",
            "richbet", "richslot"
        )
        return keywords.any { lower.contains(it) }
    }

    private fun containsKnownShortener(domain: String): Boolean {
        val known = setOf(
            "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "rb.gy", "rebrand.ly", "ow.ly", "shorturl.at",
            "tiny.cc", "cutt.ly", "short.io", "bl.ink", "t2m.io", "yourls.org",
            "snip.ly", "buff.ly", "clk.sh", "lnkd.in", "fb.me", "youtu.be",
            "qr.ae", "bit.do", "adf.ly", "bc.vc", "za.gl"
        )
        return domain.lowercase(Locale.getDefault()) in known
    }

    private fun isIpLiteralHost(host: String): Boolean {
        val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        return ipv4.matches(host) || (host.contains(":") && host.count { it == ':' } >= 2)
    }

    private fun isVeryDeepSubdomain(host: String): Boolean {
        return host.split(".").size >= 5
    }

    private data class QuerySignals(
        val paramCountHigh: Boolean,
        val hasSensitiveKeys: Boolean
    )

    private fun evaluateQuerySignals(uri: URI?): QuerySignals {
        val query = uri?.rawQuery ?: return QuerySignals(paramCountHigh = false, hasSensitiveKeys = false)
        val params = query.split("&").filter { it.isNotBlank() }
        val sensitiveKeys = setOf(
            "redirect", "url", "next", "return", "continue", "dest", "destination",
            "token", "session", "password", "pass", "login", "signin", "verify",
            "auth", "access_token", "id_token", "refresh_token", "code", "state",
            "api_key", "apikey", "secret", "otp", "pin", "card", "cvv", "account"
        )
        val hasSensitive = params.any { pair ->
            val key = pair.substringBefore("=").lowercase(Locale.getDefault())
            key in sensitiveKeys
        }
        return QuerySignals(paramCountHigh = params.size >= 8, hasSensitiveKeys = hasSensitive)
    }

    private fun looksLikeCredentialHarvestPath(path: String): Boolean {
        val lower = path.lowercase(Locale.getDefault())
        val keywords = listOf(
            "login", "signin", "sign-in", "verify", "verification",
            "account", "wallet", "payment", "pay", "bank", "banking",
            "recover", "recovery", "reset", "password", "passwd",
            "credential", "authorize", "auth", "secure", "security",
            "update", "confirm", "submit", "checkout", "invoice"
        )
        return keywords.any { lower.contains(it) }
    }

    private fun hasSuspiciousSubdomainKeyword(host: String): Boolean {
        val labels = host.lowercase(Locale.getDefault()).split(".")
        if (labels.size < 3) return false
        val subLabels = labels.dropLast(2)
        val keywords = listOf(
            "secure", "login", "signin", "verify", "account",
            "update", "auth", "banking", "payment", "wallet",
            "support", "helpdesk", "service", "portal", "webmail"
        )
        return subLabels.any { label -> keywords.any { label.contains(it) } }
    }

    private fun evaluateMissingSecurityHeaders(headers: Map<String, String>): List<String> {
        if (headers.isEmpty() || headers.values.all { it.isBlank() }) return emptyList()
        val required = linkedMapOf(
            "Strict-Transport-Security" to "HSTS",
            "Content-Security-Policy" to "CSP",
            "X-Frame-Options" to "X-Frame-Options",
            "X-Content-Type-Options" to "X-Content-Type-Options"
        )
        return required
            .filter { (header, _) -> headers[header].isNullOrBlank() }
            .map { it.value }
    }

    private fun looksLikeDownloadPayload(contentType: String): Boolean {
        val lower = contentType.lowercase(Locale.getDefault())
        return lower.contains("application/octet-stream") ||
            lower.contains("application/x-msdownload") ||
            lower.contains("application/x-dosexec") ||
            lower.contains("application/x-executable") ||
            lower.contains("application/x-msi") ||
            lower.contains("application/vnd.android.package-archive") ||
            lower.contains("application/x-sh") ||
            lower.contains("application/x-bat")
    }

    private fun containsPhishingContent(snippet: String): Boolean {
        if (snippet.isBlank()) return false
        val lower = snippet.lowercase(Locale.getDefault())
        val phrases = listOf(
            "verify your account",
            "account suspended",
            "act now",
            "urgent action required",
            "confirm your identity",
            "wallet connect",
            "seed phrase",
            "your account has been",
            "unusual activity",
            "limited time",
            "click here to verify",
            "update your payment",
            "confirm your payment",
            "enter your password",
            "enter your otp",
            "your card has been",
            "your package is waiting",
            "you have won",
            "congratulations you",
            "กรุณายืนยัน",
            "บัญชีของคุณถูกระงับ",
            "กรอกรหัสผ่าน",
            "ยืนยันตัวตน",
            "รางวัลของคุณ",
            "คลิกที่นี่เพื่อรับ"
        )
        return phrases.any { lower.contains(it) }
    }

    private fun countRedirects(response: Response): Int {
        var count = 0
        var current = response.priorResponse
        while (current != null) {
            count++
            current = current.priorResponse
        }
        return count
    }

    private fun isCrossDomainRedirect(initialUrl: String, finalUrl: String): Boolean {
        val initialHost = hostFromUrl(initialUrl)?.removePrefix("www.") ?: return false
        val finalHost = hostFromUrl(finalUrl)?.removePrefix("www.") ?: return false
        return !initialHost.equals(finalHost, ignoreCase = true)
    }

    private suspend fun fetchDomainAgeDays(domain: String): Long? {
        val rdapUrls = listOf(
            "https://rdap.org/domain/$domain",
            "https://rdap.verisign.com/com/v1/domain/$domain",
            "https://rdap.verisign.com/net/v1/domain/$domain",
            "https://rdap.cloudflare.com/rdap/v1/domain/$domain"
        )
        val body = coroutineScope {
            rdapUrls.map { url ->
                async(Dispatchers.IO) {
                    val request = Request.Builder().url(url).get().build()
                    runCatching { client.newCall(request).execute().use { it.body?.string() } }.getOrNull()
                }
            }.awaitAll().firstOrNull { !it.isNullOrBlank() }
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

    private suspend fun fetchCountry(domain: String): String? {
        val rdapUrls = listOf(
            "https://rdap.org/domain/$domain",
            "https://rdap.cloudflare.com/rdap/v1/domain/$domain",
            "https://rdap.verisign.com/com/v1/domain/$domain",
            "https://rdap.verisign.com/net/v1/domain/$domain"
        )
        val rdapCountry = coroutineScope {
            rdapUrls.map { url ->
                async(Dispatchers.IO) {
                    val body = runCatching {
                        val req = Request.Builder().url(url).get().build()
                        client.newCall(req).execute().use { it.body?.string() }
                    }.getOrNull()
                    if (!body.isNullOrBlank()) parseCountryFromRdap(body) else null
                }
            }.awaitAll().firstOrNull { !it.isNullOrBlank() }
        }
        if (!rdapCountry.isNullOrBlank()) return rdapCountry

        val ipGeoCountry = coroutineScope {
            val ipApi = async(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("http://ip-api.com/json/$domain?fields=status,country")
                    .get().build()
                val body = runCatching { client.newCall(req).execute().use { it.body?.string() } }.getOrNull()
                body?.let {
                    runCatching {
                        val json = JSONObject(it)
                        if (json.optString("status") == "success") {
                            json.optString("country").takeIf { c -> c.isNotBlank() }
                        } else null
                    }.getOrNull()
                }
            }
            val ipwhois = async(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("https://ipwho.is/$domain?fields=country")
                    .get().build()
                val body = runCatching { client.newCall(req).execute().use { it.body?.string() } }.getOrNull()
                body?.let {
                    runCatching { JSONObject(it).optString("country").takeIf { c -> c.isNotBlank() } }.getOrNull()
                }
            }
            val ipapi = async(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("https://ipapi.co/$domain/json/")
                    .get().build()
                val body = runCatching { client.newCall(req).execute().use { it.body?.string() } }.getOrNull()
                body?.let {
                    runCatching { JSONObject(it).optString("country_name").takeIf { c -> c.isNotBlank() } }.getOrNull()
                }
            }
            listOf(ipApi, ipwhois, ipapi).awaitAll().firstOrNull { !it.isNullOrBlank() }
        }
        if (!ipGeoCountry.isNullOrBlank()) return ipGeoCountry

        resolveIpCountry(domain, client)?.let { return it }

        return fetchCountryFromSite24x7(domain, client)
    }
}

fun extractCn(dn: String?): String? {
    dn ?: return null
    return dn.split(",")
        .map { it.trim() }
        .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
        ?.substringAfter("=")
}

fun extractHosts(cert: X509Certificate): List<String> {
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
        extractCountryFromEntities(entities) ?: json.optString("country").takeIf { it.isNotBlank() }
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
                val value = entry.optString(3).takeIf { it.isNotBlank() }
                if (!value.isNullOrBlank()) return value
            }
            if (key.equals("adr", ignoreCase = true)) {
                val adrValues = entry.optJSONArray(3)
                val country = adrValues?.optString(6)?.takeIf { it.isNotBlank() }
                if (!country.isNullOrBlank()) return country
            }
        }
    }
    return null
}

private fun fetchCountryFromSite24x7(domain: String, client: OkHttpClient): String? {
    // JSON endpoint
    val jsonBody = okhttp3.FormBody.Builder()
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
    val formBody = okhttp3.FormBody.Builder()
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

private fun resolveIpAddress(host: String): String? {
    val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull().orEmpty()
    val ipv4 = addresses.firstOrNull { it is Inet4Address }?.hostAddress
    val ipv6 = addresses.firstOrNull { it !is Inet4Address }?.hostAddress
    val resolved = ipv4 ?: ipv6
    if (resolved != null) {
        debugLog("Resolved $host -> $resolved (prefer ipv4=${ipv4 != null})")
    }
    return resolved
}

private fun resolveIpCountry(host: String, client: OkHttpClient): String? {
    val ip = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull() ?: return null
    debugLog("DNS resolved $host -> $ip")

    val ipApi = Request.Builder()
        .url("http://ip-api.com/json/$ip?fields=status,country")
        .get()
        .build()
    val ipApiBody = runCatching { client.newCall(ipApi).execute().use { it.body?.string() } }.getOrNull()
    if (!ipApiBody.isNullOrBlank()) {
        val country = runCatching {
            val json = JSONObject(ipApiBody)
            if (json.optString("status") == "success") {
                json.optString("country").takeIf { it.isNotBlank() }
            } else {
                null
            }
        }.getOrNull()
        if (!country.isNullOrBlank()) return country
    }

    val ipwhois = Request.Builder()
        .url("https://ipwho.is/$ip?fields=country")
        .get()
        .build()
    val ipwhoisBody = runCatching { client.newCall(ipwhois).execute().use { it.body?.string() } }.getOrNull()
    if (!ipwhoisBody.isNullOrBlank()) {
        val country = runCatching {
            JSONObject(ipwhoisBody).optString("country").takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!country.isNullOrBlank()) return country
    }

    val ipapi = Request.Builder()
        .url("https://ipapi.co/$ip/json/")
        .get()
        .build()
    val ipapiBody = runCatching { client.newCall(ipapi).execute().use { it.body?.string() } }.getOrNull()
    if (!ipapiBody.isNullOrBlank()) {
        val country = runCatching {
            JSONObject(ipapiBody).optString("country_name").takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!country.isNullOrBlank()) return country
    }

    return null
}

fun fingerprintSha256(cert: X509Certificate): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(cert.encoded)
    return digest.joinToString(":") { b -> "%02X".format(b) }
}

private fun debugLog(msg: String) {
    if (DebugSettings.isDebugEnabled.value) {
        Log.d("LinkChecker", msg)
    }
}
