package com.yukiorita.halfbrowser.ui.browser

/** Fragment-only navigation may not invoke onPageStarted or onPageFinished. */
internal fun isFragmentOnlyNavigation(currentUrl: String, targetUrl: String): Boolean =
    currentUrl.isNotEmpty() && currentUrl != targetUrl &&
        currentUrl.substringBefore('#') == targetUrl.substringBefore('#')

/** Identifies the currently loading main-frame navigation, including same-URL reloads. */
internal class LoadingNavigation {
    private var generation = 0L
    private var url: String? = null

    fun started(url: String?) {
        generation += 1
        this.url = url
    }

    fun tokenFor(url: String?): Long? = if (url != null && url == this.url) generation else null

    fun isCurrent(token: Long): Boolean = token == generation
}
