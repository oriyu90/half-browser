package com.yukiorita.halfbrowser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yukiorita.halfbrowser.core.DeepLinks
import com.yukiorita.halfbrowser.core.LocaleHelper
import com.yukiorita.halfbrowser.core.UrlNormalizer
import com.yukiorita.halfbrowser.ui.HalfBrowserRoot

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as HalfBrowserApplication).container

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HalfBrowserRoot(container) }
        // Intents may arrive before compose starts collecting nav events, so the
        // request is stored in the container and the event is buffered.
        routeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    /**
     * All intent handling funnels through here and is wrapped in runCatching:
     * a malformed intent from another app must never crash the browser.
     */
    private fun routeIntent(intent: Intent?) {
        runCatching {
            when (intent?.action) {
                Intent.ACTION_VIEW -> handleViewIntent(intent)
                Intent.ACTION_SEND -> handleSendIntent(intent)
                else -> Unit
            }
        }
    }

    private fun handleViewIntent(intent: Intent) {
        val raw = intent.dataString ?: return
        when (val link = DeepLinks.parse(raw)) {
            DeepLinks.Link.Home -> container.requestHome()
            is DeepLinks.Link.BookmarkById -> {
                val bookmark = container.bookmarks.findById(link.id) ?: return
                container.requestOpen(bookmark.url, bookmark.id, fromExternalApp = false)
            }
            is DeepLinks.Link.Open -> {
                // A bookmarkId only decorates the toolbar when it really belongs to
                // this URL; otherwise an external app could spoof the bookmark name.
                val bookmarkId = link.bookmarkId
                    ?.let { id -> container.bookmarks.findById(id) }
                    ?.takeIf { bookmark ->
                        com.yukiorita.halfbrowser.core.UrlNormalizer.canonicalForCompare(bookmark.url) ==
                            com.yukiorita.halfbrowser.core.UrlNormalizer.canonicalForCompare(link.url)
                    }
                    ?.id
                container.requestOpen(link.url, bookmarkId, fromExternalApp = false)
            }
            null -> Unit // Not ours (for example an unrelated custom scheme).
        }

        // http(s) VIEW intents are the "external link forwarded to the browser" path.
        if (DeepLinks.parse(raw) == null) {
            val url = UrlNormalizer.validateWebUrl(raw) ?: return
            val bookmark = container.bookmarks.findByUrl(url)
            container.requestOpen(url, bookmark?.id, fromExternalApp = true)
        }
    }

    private fun handleSendIntent(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = firstUrlIn(text) ?: return
        val bookmark = container.bookmarks.findByUrl(url)
        container.requestOpen(url, bookmark?.id, fromExternalApp = true)
    }

    private fun firstUrlIn(text: String): String? {
        val match = Regex("https?://[^\\s]+").find(text.trim())
        return match?.value?.trimEnd('.', ',', ')', ']', '、', '。')?.let { UrlNormalizer.validateWebUrl(it) }
    }
}
