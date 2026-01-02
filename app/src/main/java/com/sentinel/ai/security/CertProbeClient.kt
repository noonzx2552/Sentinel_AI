package com.sentinel.ai.security

import android.util.Log
import com.sentinel.ai.ui.DebugSettings
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException

data class CertProbeResult(
    val certInfo: CertInfo?,
    val errorReason: CertErrorReason?,
    val errorMessage: String?
)

object CertProbeClient {

    private val ipv4Dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses: List<InetAddress> = Dns.SYSTEM.lookup(hostname)
            val ipv4 = addresses.filterIsInstance<Inet4Address>()
            return if (ipv4.isNotEmpty()) {
                debugLog("CertProbe DNS ipv4 ${ipv4.joinToString()}")
                ipv4
            } else {
                debugLog("CertProbe DNS no ipv4, fallback all")
                addresses
            }
        }
    }

    private val connectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(
            okhttp3.TlsVersion.TLS_1_3,
            okhttp3.TlsVersion.TLS_1_2
        )
        .allEnabledCipherSuites()
        .build()

    private fun buildClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(ipv4Dns)
            .connectionSpecs(listOf(connectionSpec))
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(java.time.Duration.ofSeconds(15))
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(15))
            .writeTimeout(java.time.Duration.ofSeconds(15))
            .build()
    }

    fun probe(host: String): CertProbeResult {
        val httpsUrl = "https://$host"
        val client = buildClient()
        var lastError: CertErrorReason? = null
        var lastMessage: String? = null

        repeat(2) { attempt ->
            val result = runCatching { executeRequest(client, httpsUrl) }.getOrElse { throwable ->
                when (throwable) {
                    is UnknownHostException -> {
                        lastError = CertErrorReason.DNS_FAIL
                        lastMessage = throwable.message
                    }
                    is SocketTimeoutException -> {
                        lastError = CertErrorReason.TIMEOUT
                        lastMessage = throwable.message
                    }
                    is SSLException -> {
                        lastError = CertErrorReason.TLS_HANDSHAKE_FAILED
                        lastMessage = throwable.message
                    }
                    else -> {
                        lastError = CertErrorReason.TLS_HANDSHAKE_FAILED
                        lastMessage = throwable.message
                    }
                }
                debugLog("CertProbe attempt ${attempt + 1} failed: ${throwable.javaClass.simpleName} ${throwable.message}")
                null
            }
            if (result != null) {
                return result
            }
        }
        return CertProbeResult(certInfo = null, errorReason = lastError, errorMessage = lastMessage)
    }

    private fun executeRequest(client: OkHttpClient, url: String): CertProbeResult? {
        var currentUrl = url
        var redirects = 0
        while (redirects < 3) {
            val request = Request.Builder()
                .url(currentUrl)
                .get()
                .header("User-Agent", "SentinelLinkChecker/1.0")
                .build()
            var followLocation: String? = null
            var redirectResult: CertProbeResult? = null
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val location = response.header("Location")
                    debugLog("CertProbe redirect code=${response.code} to=$location")
                    if (location.isNullOrBlank()) {
                        redirectResult = CertProbeResult(
                            certInfo = null,
                            errorReason = CertErrorReason.TLS_HANDSHAKE_FAILED,
                            errorMessage = "Redirect without Location"
                        )
                        return@use
                    }
                    if (location.startsWith("http://", ignoreCase = true)) {
                        redirectResult = CertProbeResult(
                            certInfo = null,
                            errorReason = CertErrorReason.REDIRECTED_TO_HTTP,
                            errorMessage = "Redirected to HTTP"
                        )
                        return@use
                    }
                    val resolved = runCatching { response.request.url.resolve(location) }.getOrNull()
                    followLocation = resolved?.toString() ?: location
                    return@use
                }

                val handshake = response.handshake
                if (handshake == null) {
                    debugLog("CertProbe no handshake code=${response.code}")
                    redirectResult = CertProbeResult(
                        certInfo = null,
                        errorReason = CertErrorReason.TLS_HANDSHAKE_FAILED,
                        errorMessage = "No TLS handshake"
                    )
                    return@use
                }

                val cert = handshake.peerCertificates.firstOrNull() as? X509Certificate
                val certInfo = cert?.let { x509 ->
                    val hosts = extractHosts(x509)
                    CertInfo(
                        subject = extractCn(x509.subjectX500Principal?.name) ?: x509.subjectX500Principal?.name,
                        issuer = extractCn(x509.issuerX500Principal?.name) ?: x509.issuerX500Principal?.name,
                        notBefore = x509.notBefore?.time,
                        notAfter = x509.notAfter?.time,
                        tlsVersion = handshake.tlsVersion?.javaName,
                        hosts = hosts,
                        fingerprintSha256 = fingerprintSha256(x509)
                    )
                }

                val socketAddress = response.request.url.host
                val resolvedAddresses = runCatching { ipv4Dns.lookup(socketAddress) }.getOrDefault(emptyList())
                val ipVersion = resolvedAddresses.firstOrNull()?.let { addr ->
                    if (addr is Inet4Address) "IPv4" else if (addr is InetAddress) "IPv6" else "unknown"
                }

                debugLog(
                    "CertProbe handshake success tls=${handshake.tlsVersion} cipher=${handshake.cipherSuite} ipVersion=$ipVersion"
                )
                redirectResult = CertProbeResult(
                    certInfo = certInfo,
                    errorReason = null,
                    errorMessage = null
                )
            }
            redirectResult?.let { return it }
            if (followLocation != null) {
                currentUrl = followLocation!!
                redirects++
                continue
            } else {
                break
            }
        }
        return CertProbeResult(
            certInfo = null,
            errorReason = CertErrorReason.TLS_HANDSHAKE_FAILED,
            errorMessage = "Too many redirects"
        )
    }

    private fun debugLog(msg: String) {
        if (DebugSettings.isDebugEnabled.value) {
            Log.d("LinkChecker", msg)
        }
    }
}
