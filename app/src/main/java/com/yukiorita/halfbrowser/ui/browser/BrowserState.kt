package com.yukiorita.halfbrowser.ui.browser

import android.net.Uri
import android.net.http.SslCertificate
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.net.http.SslError
import android.os.Build
import android.webkit.WebView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yukiorita.halfbrowser.core.TrustPolicy
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Mutable UI state for one browser screen. All writes happen on the main thread. */
@Stable
class BrowserState {
    var webView: WebView? = null
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(true)
    var pageTitle by mutableStateOf("")
    var currentUrl by mutableStateOf("")
    var lastRequestedUrl by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
    var error by mutableStateOf<BrowserError?>(null)
    var ssl by mutableStateOf<SslChallenge?>(null)
    var auth by mutableStateOf<AuthChallenge?>(null)

    /** URL whose sign-in dialog was cancelled; keeps the error pane visible. */
    var pendingAuthErrorUrl by mutableStateOf<String?>(null)

    var fileChooser by mutableStateOf<FileChooserRequest?>(null)
    var webPermission by mutableStateOf<WebPermissionRequest?>(null)
    var geoPermission by mutableStateOf<GeoPermissionRequest?>(null)
    var fullscreenView by mutableStateOf<android.view.View?>(null)
    var fullscreenCallback by mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    var desktopMode by mutableStateOf(false)
    var defaultUserAgent by mutableStateOf("")

    /** Bumped when the renderer process dies so Compose builds a fresh WebView. */
    var generation by mutableIntStateOf(0)
}

enum class ErrorKind { NETWORK, AUTH, CERT }

class FileChooserRequest(
    val params: WebChromeClient.FileChooserParams,
    private val callback: ValueCallback<Array<Uri>>,
) {
    private var settled = false

    fun deliver(uris: Array<Uri>?) {
        if (settled) return
        settled = true
        runCatching { callback.onReceiveValue(uris) }
    }
}

class WebPermissionRequest(
    val origin: String,
    val resources: Array<String>,
    private val request: PermissionRequest,
) {
    fun grant() = runCatching { request.grant(resources) }

    fun deny() = runCatching { request.deny() }

    val wantsCamera: Boolean
        get() = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

    val wantsMicrophone: Boolean
        get() = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
}

class GeoPermissionRequest(
    val origin: String,
    private val callback: GeolocationPermissions.Callback,
) {
    fun resolve(allow: Boolean) = runCatching { callback.invoke(origin, allow, false) }
}

data class BrowserError(
    val description: String?,
    val failingUrl: String?,
    val kind: ErrorKind = ErrorKind.NETWORK,
)

/**
 * One pending certificate decision. The handler is captured here because Compose
 * must not hold a reference to it in a State<T> of a data class (it is not
 * comparable) — the closures are invoked exactly once by the UI.
 */
class SslChallenge(
    val host: String,
    val port: Int,
    val primaryError: Int,
    val subject: String,
    val issuer: String,
    val fingerprintSha256: String?,
    val notAfter: Long,
    val changed: Boolean,
    /** False for expired / hostname-mismatched certificates. */
    val canPin: Boolean,
    private val onProceed: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private var settled = false

    fun proceed() {
        if (settled) return
        settled = true
        onProceed()
    }

    fun cancel() {
        if (settled) return
        settled = true
        onCancel()
    }
}

class AuthChallenge(
    val host: String,
    val realm: String,
    val isProxy: Boolean,
    /** False for plain http: a password sent without TLS must never be saved. */
    val allowSave: Boolean,
    val failedBefore: Boolean,
    val suggestedUsername: String,
    private val onProceed: (String, String, Boolean) -> Unit,
    private val onCancel: () -> Unit,
) {
    private var settled = false

    fun signIn(username: String, password: String, save: Boolean) {
        if (settled) return
        settled = true
        onProceed(username, password, save)
    }

    fun cancel() {
        if (settled) return
        settled = true
        onCancel()
    }
}

/**
 * Returns the X.509 certificate behind a platform SslCertificate.
 *
 * `SslCertificate.getX509Certificate()` only exists on API 29+, so on API 26-28
 * the certificate is re-parsed from the platform's saved state Bundle. Without
 * this fallback the fingerprint would silently be null on those versions and
 * "always trust" could never work.
 */
internal fun x509Of(certificate: SslCertificate?): X509Certificate? {
    if (certificate == null) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { certificate.x509Certificate }.getOrNull()?.let { return it }
    }
    return runCatching {
        val state = SslCertificate.saveState(certificate)
        parseAnyCertificate(state)
    }.getOrNull()
}

/**
 * The Bundle layout behind SslCertificate is not a public API. The documented
 * key is "x509-certificate", but if it is absent we scan nested bundles for any
 * byte[] that parses as an X.509 certificate, so the API 26-28 fallback does not
 * silently disable pinning on an OEM variation.
 */
private fun parseAnyCertificate(bundle: android.os.Bundle, depth: Int = 0): X509Certificate? {
    if (depth > 2) return null
    bundle.getByteArray("x509-certificate")?.let { bytes ->
        (runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        }.getOrNull())?.let { return it }
    }
    for (key in bundle.keySet()) {
        val value = runCatching { bundle.get(key) }.getOrNull()
        when (value) {
            is ByteArray -> {
                val cert = runCatching {
                    CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(value)) as X509Certificate
                }.getOrNull()
                if (cert != null) return cert
            }
            is android.os.Bundle -> parseAnyCertificate(value, depth + 1)?.let { return it }
        }
    }
    return null
}

internal fun certificateFingerprint(certificate: SslCertificate?): String? =
    x509Of(certificate)?.let { runCatching { TrustPolicy.fingerprintOf(it) }.getOrNull() }

internal fun subjectOf(certificate: SslCertificate?): String {
    val x509 = x509Of(certificate)
    if (x509 != null) return runCatching { x509.subjectDN?.name }.getOrNull().orEmpty()
    return runCatching { certificate?.issuedTo?.toString() }.getOrDefault("").orEmpty()
}

internal fun issuerOf(certificate: SslCertificate?): String {
    val x509 = x509Of(certificate)
    if (x509 != null) return runCatching { x509.issuerDN?.name }.getOrNull().orEmpty()
    return runCatching { certificate?.issuedBy?.toString() }.getOrDefault("").orEmpty()
}

internal fun notAfterOf(certificate: SslCertificate?): Long =
    x509Of(certificate)?.let { runCatching { it.notAfter?.time }.getOrNull() } ?: 0L

internal fun hostOf(url: String?): String? =
    runCatching { url?.let { Uri.parse(it).host } }.getOrNull()?.takeIf { it.isNotBlank() }

/** host:port, if a non-default port is present. Used for the chrome subtitle. */
internal fun authorityOf(url: String?): String? =
    runCatching { url?.let { Uri.parse(it).authority } }.getOrNull()?.takeIf { it.isNotBlank() }

/** Explicit or default port for a URL ("https" -> 443, otherwise 80). */
internal fun portOf(url: String?, scheme: String): Int {
    val explicit = runCatching { url?.let { Uri.parse(it).port } }.getOrNull() ?: -1
    if (explicit > 0) return explicit
    return if (scheme.equals("https", ignoreCase = true)) 443 else 80
}

internal fun httpsOrHttp(url: String?): Boolean {
    val scheme = runCatching { url?.let { Uri.parse(it).scheme } }.getOrNull() ?: return false
    return scheme == "http" || scheme == "https"
}

internal fun sslErrorRes(primaryError: Int): Int = when (primaryError) {
    SslError.SSL_NOTYETVALID -> com.yukiorita.halfbrowser.R.string.ssl_error_not_yet_valid
    SslError.SSL_EXPIRED -> com.yukiorita.halfbrowser.R.string.ssl_error_expired
    SslError.SSL_IDMISMATCH -> com.yukiorita.halfbrowser.R.string.ssl_error_hostname_mismatch
    SslError.SSL_UNTRUSTED -> com.yukiorita.halfbrowser.R.string.ssl_error_untrusted
    SslError.SSL_DATE_INVALID -> com.yukiorita.halfbrowser.R.string.ssl_error_date_invalid
    else -> com.yukiorita.halfbrowser.R.string.ssl_error_unknown
}
