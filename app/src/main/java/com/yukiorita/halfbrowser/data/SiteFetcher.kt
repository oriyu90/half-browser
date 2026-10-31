package com.yukiorita.halfbrowser.data

import android.util.Base64
import com.yukiorita.halfbrowser.core.Bookmark
import com.yukiorita.halfbrowser.core.SiteContent
import com.yukiorita.halfbrowser.core.TrustPolicy
import com.yukiorita.halfbrowser.core.TrustedSite
import com.yukiorita.halfbrowser.core.WatchRules
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

sealed interface FetchResult {
    data class Success(
        val title: String,
        val fingerprint: String,
        val snippet: String,
    ) : FetchResult

    data class Failure(val reason: String) : FetchResult
}

/**
 * Minimal HTTP client for background page-change checks.
 *
 * It deliberately does not touch WebView: a short-lived HttpURLConnection keeps
 * the worker light. HTTPS trust follows the same rules as the in-app interstitial:
 * the platform CA store is tried first, then a certificate pinned for this exact
 * host+port. Credentials are only sent over https and only after a 401 tells us
 * the realm.
 */
class SiteFetcher(
    private val trust: TrustRepository,
    private val credentials: CredentialStore,
) {

    fun fetch(bookmark: Bookmark): FetchResult {
        val url = runCatching { URL(bookmark.url) }.getOrNull()
            ?: return FetchResult.Failure("invalid URL")
        val scheme = url.protocol.lowercase()
        if (scheme != "http" && scheme != "https") {
            return FetchResult.Failure("unsupported scheme")
        }
        val port = if (url.port > 0) url.port else if (scheme == "https") 443 else 80
        val pin = if (scheme == "https") trust.find(url.host, port) else null

        var response = request(url, pin, credential = null)
        if (response.code == 401 || response.code == 407) {
            val realm = SiteContent.basicRealm(response.authenticate)
            val credential = if (scheme == "https") {
                credentials.find(scheme, url.host, port, realm)
                    ?: credentials.findFirstForHost(url.host, port)
            } else {
                null // never send a password over cleartext
            }
            if (credential != null) {
                response = request(url, pin, credential)
            }
        }

        if (response.error != null) {
            return FetchResult.Failure(response.error)
        }
        if (response.code !in 200..299) {
            return FetchResult.Failure(
                when (response.code) {
                    401, 407 -> "authentication required"
                    403 -> "access denied (403)"
                    404 -> "not found (404)"
                    else -> "HTTP ${response.code}"
                },
            )
        }
        if (response.body.isBlank()) {
            return FetchResult.Failure("empty response")
        }

        val title = SiteContent.titleOf(response.body)
            .ifBlank { url.host.orEmpty() }
        return FetchResult.Success(
            title = title,
            fingerprint = SiteContent.fingerprint(response.body),
            snippet = SiteContent.snippet(response.body),
        )
    }

    private fun request(
        url: URL,
        pin: TrustedSite?,
        credential: StoredCredential?,
    ): RawResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = WatchRules.TIMEOUT_MS
                readTimeout = WatchRules.TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                setRequestProperty("Accept-Language", "ja,en;q=0.8")
                if (credential != null) {
                    val token = Base64.encodeToString(
                        "${credential.username}:${credential.password}".toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP,
                    )
                    setRequestProperty("Authorization", "Basic $token")
                }
            }
            if (connection is HttpsURLConnection && pin != null) {
                connection.sslSocketFactory = sslContext(pin).socketFactory
                connection.hostnameVerifier = hostnameVerifier(pin)
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { readLimited(it) } ?: ByteArray(0)
            val charset = charsetOf(connection.contentType, bytes)
            RawResponse(
                code = code,
                body = String(bytes, charset),
                authenticate = connection.getHeaderField("WWW-Authenticate"),
                error = null,
            )
        } catch (throwable: Throwable) {
            RawResponse(
                code = -1,
                body = "",
                authenticate = null,
                error = shortReason(throwable),
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun sslContext(pin: TrustedSite): SSLContext {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val system = factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("no system trust manager")
        val custom = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                system.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    system.checkServerTrusted(chain, authType)
                    return
                } catch (_: CertificateException) {
                    // Fall through to the pinned certificate.
                }
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                val fingerprint = TrustPolicy.fingerprintOf(leaf)
                if (TrustPolicy.normalizeFingerprint(fingerprint) !=
                    TrustPolicy.normalizeFingerprint(pin.fingerprintSha256)
                ) {
                    throw CertificateException("pinned certificate mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(custom), null)
        }
    }

    private fun hostnameVerifier(pin: TrustedSite): HostnameVerifier = HostnameVerifier { hostname, session ->
        if (HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)) {
            true
        } else {
            runCatching {
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: return@runCatching false
                TrustPolicy.normalizeFingerprint(TrustPolicy.fingerprintOf(leaf)) ==
                    TrustPolicy.normalizeFingerprint(pin.fingerprintSha256)
            }.getOrDefault(false)
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < WatchRules.MAX_HTML_BYTES) {
            val read = input.read(buffer)
            if (read <= 0) break
            val take = minOf(read, WatchRules.MAX_HTML_BYTES - total)
            output.write(buffer, 0, take)
            total += take
        }
        return output.toByteArray()
    }

    private fun charsetOf(contentType: String?, bytes: ByteArray): java.nio.charset.Charset {
        val fromHeader = contentType
            ?.let { Regex("charset\\s*=\\s*\"?([^;\"\\s]+)", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.getOrNull(1)
        val fromMeta = Regex("<meta[^>]+charset\\s*=\\s*\"?([^\"\\s/>]+)", RegexOption.IGNORE_CASE)
            .find(String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1))
            ?.groupValues?.getOrNull(1)
        val name = fromHeader ?: fromMeta
        return runCatching { java.nio.charset.Charset.forName(name) }
            .getOrDefault(Charsets.UTF_8)
    }

    private fun shortReason(throwable: Throwable): String {
        val text = throwable.message.orEmpty().ifBlank { throwable.javaClass.simpleName }
        // Never surface credentials or query strings in the UI.
        return text.replace(Regex("://[^\\s]+"), "://…").take(200)
    }

    private data class RawResponse(
        val code: Int,
        val body: String,
        val authenticate: String?,
        val error: String?,
    )

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36 " +
            "HalfBrowserWatch/1.1"
    }
}
