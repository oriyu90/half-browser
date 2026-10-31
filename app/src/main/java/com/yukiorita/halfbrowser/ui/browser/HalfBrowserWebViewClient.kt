package com.yukiorita.halfbrowser.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.webkit.ClientCertRequest
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.core.TrustDecision
import com.yukiorita.halfbrowser.core.TrustPolicy
import com.yukiorita.halfbrowser.core.AuthAttemptTracker

/**
 * WebView behaviour for Half-Browser.
 *
 * Security posture:
 *  - explicit certificate interstitial instead of silently proceeding;
 *  - credentials are scoped to scheme + host + port + realm and are never
 *    auto-filled over plain http;
 *  - `intent://` URLs are never launched as explicit components (intent
 *    redirection); only BROWSABLE VIEW intents reach a chooser;
 *  - unknown URL schemes are handed to the system, never loaded in the WebView;
 *  - a dead renderer is reported instead of leaving a blank white page.
 */
internal class HalfBrowserWebViewClient(
    private val context: Context,
    private val state: BrowserState,
    private val container: AppContainer,
    private val openExternalApp: (String) -> Boolean,
    private val showToast: (String) -> Unit,
) : WebViewClient() {

    /** scheme://host:port|realm -> number of silent attempts in this screen. */
    private val authAttempts = AuthAttemptTracker()

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        state.loading = true
        if (state.pendingAuthErrorUrl == null) state.error = null
        state.currentUrl = url.orEmpty()
        state.canGoBack = view?.canGoBack() == true
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        state.loading = false
        state.progress = 100
        state.currentUrl = url ?: state.currentUrl
        state.canGoBack = view?.canGoBack() == true
        val pendingAuth = state.pendingAuthErrorUrl
        if (pendingAuth != null) {
            state.pendingAuthErrorUrl = null
            state.loading = false
            state.error = BrowserError(
                description = null,
                failingUrl = state.currentUrl,
                kind = ErrorKind.AUTH,
            )
        } else {
            verifyLoadedCertificate(view, state.currentUrl)
        }
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        // A page actually rendered, so previous authentication failures for this
        // host are stale and silent auto-fill may be attempted again.
        val host = hostOf(url) ?: return
        runCatching { authAttempts.clearHost(host) }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        if (httpsOrHttp(url)) return false
        // tel:, mailto:, sms:, intent:// and app links belong to the system.
        return handleSystemScheme(url)
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        if (handler == null || error == null) {
            handler?.cancel()
            return
        }
        val host = hostOf(error.url)
        if (host == null) {
            handler.cancel()
            return
        }

        // Only the main document may raise a dialog. Sub-resources (images, CSS,
        // XHR) are blocked silently: prompting per sub-resource would replace the
        // main decision with a stream of dialogs and let a page spam the user.
        val mainUrl = view?.url
        if (mainUrl != null && error.url != mainUrl) {
            handler.cancel()
            return
        }

        val certificate = error.certificate
        val fingerprint = certificateFingerprint(certificate)
        val port = portOf(error.url, "https")
        val decision = if (fingerprint != null) {
            TrustPolicy.decide(host, port, fingerprint, container.trust.find(host, port))
        } else {
            TrustDecision.Unknown
        }

        if (decision == TrustDecision.Trusted && fingerprint != null) {
            container.trust.markSeen(host, port)
            handler.proceed()
            return
        }

        // A newer navigation supersedes any dialog that is still on screen.
        state.ssl?.cancel()
        state.ssl = SslChallenge(
            host = host,
            port = port,
            primaryError = error.primaryError,
            subject = subjectOf(certificate),
            issuer = issuerOf(certificate),
            fingerprintSha256 = fingerprint,
            notAfter = notAfterOf(certificate),
            changed = decision is TrustDecision.Changed,
            canPin = TrustPolicy.canPin(error.primaryError),
            onProceed = { handler.proceed() },
            onCancel = { handler.cancel() },
        )
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?,
    ) {
        if (handler == null) return
        val pageUri = runCatching { view?.url?.let { Uri.parse(it) } }.getOrNull()
        val scheme = pageUri?.scheme?.lowercase() ?: "http"
        val pageHost = pageUri?.host
        val safeHost = sanitizeAuthLabel((host ?: pageHost).orEmpty().lowercase().trim(), 120)
        val port = pageUri?.port?.takeIf { it > 0 }
            ?: if (scheme == "https") 443 else 80
        val safeRealm = sanitizeAuthLabel(realm.orEmpty(), 200)
        val key = AuthAttemptTracker.keyOf(scheme, safeHost, port, safeRealm)
        val attempts = authAttempts.attempts(key)
        val saved = container.credentials.find(scheme, safeHost, port, safeRealm)
        val allowAutoFill = scheme == "https"

        if (saved != null && allowAutoFill && authAttempts.shouldAutoFill(key)) {
            authAttempts.record(key)
            handler.proceed(saved.username, saved.password)
            return
        }

        state.auth?.cancel()
        state.auth = AuthChallenge(
            host = safeHost,
            realm = safeRealm,
            isProxy = false,
            allowSave = allowAutoFill,
            failedBefore = saved != null && attempts >= 2,
            suggestedUsername = if (allowAutoFill) saved?.username.orEmpty() else "",
            onProceed = { username, password, save ->
                authAttempts.record(key)
                if (save && allowAutoFill && username.isNotBlank()) {
                    val saved = container.credentials.save(
                        scheme,
                        safeHost,
                        port,
                        safeRealm,
                        username,
                        password,
                    )
                    if (!saved) {
                        showToast(context.getString(com.yukiorita.halfbrowser.R.string.auth_save_failed))
                    }
                }
                handler.proceed(username, password)
            },
            onCancel = {
                state.pendingAuthErrorUrl = view?.url ?: state.currentUrl
                handler.cancel()
            },
        )
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: android.webkit.WebResourceResponse?,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame != true) return
        val status = errorResponse?.statusCode ?: return
        if (status == 401 || status == 407) {
            // The server's bare 401 body is not useful; show a retryable pane.
            state.loading = false
            state.error = BrowserError(
                description = "HTTP $status",
                failingUrl = request.url?.toString(),
                kind = ErrorKind.AUTH,
            )
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame != true) return
        // While the certificate dialog explains the failure, do not also show a
        // generic error page behind it.
        if (state.ssl != null) return
        state.loading = false
        state.error = BrowserError(
            description = error?.description?.toString(),
            failingUrl = request.url?.toString(),
        )
    }

    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
        // Client certificates are not supported; cancel explicitly so the server
        // receives a clean refusal instead of a hung TLS handshake.
        request?.cancel()
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        state.webView = null
        state.lastRequestedUrl = view?.url ?: state.lastRequestedUrl
        runCatching {
            (view?.parent as? android.view.ViewGroup)?.removeView(view)
            view?.destroy()
        }
        state.loading = false
        state.error = BrowserError(
            description = "The web renderer stopped unexpectedly.",
            failingUrl = state.lastRequestedUrl,
        )
        // Rebuild a fresh WebView on the next composition.
        state.generation += 1
        return true
    }

    private fun handleSystemScheme(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return true
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "intent") return handleIntentUri(url, uri)
        // Only hand schemes that a browser legitimately delegates to the OS.
        // data:, javascript:, about:, blob: and file: must never be forwarded.
        if (scheme !in setOf("tel", "mailto", "sms", "smsto", "geo")) {
            showToast(context.getString(com.yukiorita.halfbrowser.R.string.toast_link_not_opened))
            return true
        }
        if (openExternalApp(url)) return true
        showToast(context.getString(com.yukiorita.halfbrowser.R.string.toast_link_not_opened))
        return true
    }

    /**
     * `intent:` links are never launched. Even with an implicit BROWSABLE VIEW
     * intent the extras/data are attacker-controlled, and any component that
     * trusts them becomes a confused deputy. Only the conventional
     * `S.browser_fallback_url` is loaded (after strict http/https validation).
     */
    private fun handleIntentUri(raw: String, uri: Uri): Boolean {
        val fallback = runCatching { uri.getQueryParameter("S.browser_fallback_url") }.getOrNull()
        val fallbackUrl = fallback?.let { com.yukiorita.halfbrowser.core.UrlNormalizer.validateWebUrl(it) }
        if (fallbackUrl != null) {
            state.webView?.loadUrl(fallbackUrl)
        } else {
            showToast(context.getString(com.yukiorita.halfbrowser.R.string.toast_link_not_opened))
        }
        return true
    }

    /** Defence in depth: re-check the certificate WebView actually used. */
    private fun verifyLoadedCertificate(view: WebView?, url: String?) {
        if (url == null || !url.startsWith("https://", ignoreCase = true)) return
        val host = hostOf(url) ?: return
        val port = portOf(url, "https")
        val pin = container.trust.find(host, port) ?: return
        val actual = certificateFingerprint(view?.certificate) ?: return
        if (TrustPolicy.normalizeFingerprint(actual) ==
            TrustPolicy.normalizeFingerprint(pin.fingerprintSha256)
        ) {
            return
        }
        // The session did not use the pinned certificate. Forget the pin and show
        // the error pane so the next attempt asks the user again.
        container.trust.revoke(host, port)
        state.loading = false
        state.error = BrowserError(
            description = "The certificate used for this page does not match the trusted one.",
            failingUrl = url,
            kind = ErrorKind.CERT,
        )
        runCatching { view?.stopLoading() }
    }
}

/** Strips control characters and bounds the length of text shown in auth UI. */
private fun sanitizeAuthLabel(value: String, maxLength: Int): String =
    value.filter { it.code >= 0x20 && it.code != 0x7f }.take(maxLength)

internal class HalfBrowserChromeClient(
    private val state: BrowserState,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        state.progress = newProgress.coerceIn(0, 100)
        if (newProgress >= 100) state.loading = false
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        if (!title.isNullOrBlank()) state.pageTitle = title
    }

    override fun onShowFileChooser(
        view: WebView?,
        callback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        if (fileChooserParams == null || callback == null) return false
        // A new request supersedes a pending one: return null to the old callback.
        state.fileChooser?.deliver(null)
        state.fileChooser = FileChooserRequest(fileChooserParams, callback)
        return true
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        val supported = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }
        if (supported.isEmpty()) {
            request.deny()
            return
        }
        state.webPermission?.deny()
        state.webPermission = WebPermissionRequest(
            origin = request.origin?.toString().orEmpty(),
            resources = supported.toTypedArray(),
            request = request,
        )
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        if (callback == null) return
        state.geoPermission?.resolve(false)
        state.geoPermission = GeoPermissionRequest(origin.orEmpty(), callback)
    }

    override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
        if (view == null || callback == null) return
        state.fullscreenCallback?.let { runCatching { it.onCustomViewHidden() } }
        state.fullscreenView = view
        state.fullscreenCallback = callback
    }

    override fun onHideCustomView() {
        val callback = state.fullscreenCallback
        state.fullscreenView = null
        state.fullscreenCallback = null
        runCatching { callback?.onCustomViewHidden() }
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean = false
}

internal fun isWebViewAvailable(context: Context): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WebView.getCurrentWebViewPackage() != null
    } else {
        WebViewCompat.getCurrentWebViewPackage(context) != null
    }
}.getOrDefault(true)

/**
 * Opens a URL outside Half-Browser, explicitly excluding this package: otherwise
 * "open in another browser" could resolve straight back to Half-Browser.
 */
internal fun startExternalView(context: Context, url: String): Boolean = runCatching {
    val validated = com.yukiorita.halfbrowser.core.UrlNormalizer.validateWebUrl(url) ?: return false
    val base = Intent(Intent.ACTION_VIEW, Uri.parse(validated))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val candidates = context.packageManager.queryIntentActivities(base, 0)
        .map { it.activityInfo.packageName }
        .filter { it != context.packageName }
        .distinct()
    if (candidates.isEmpty()) return false
    // Let the user choose among the other browsers instead of silently picking
    // whichever package the PackageManager happened to return first.
    val chooser = Intent.createChooser(base, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(android.content.ComponentName(context, com.yukiorita.halfbrowser.MainActivity::class.java)),
        )
    }
    context.startActivity(chooser)
    true
}.getOrElse { false }
