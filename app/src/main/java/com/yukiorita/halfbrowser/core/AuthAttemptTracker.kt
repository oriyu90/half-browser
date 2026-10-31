package com.yukiorita.halfbrowser.core

/**
 * Counts silent HTTP-auth attempts per scheme+host+port+realm so a rejected saved
 * password cannot loop forever. The counter is cleared once a page actually
 * renders, which lets auto-fill work again on the next visit.
 */
class AuthAttemptTracker(private val maxAutoFill: Int = 2) {

    private val attempts = mutableMapOf<String, Int>()

    @Synchronized
    fun attempts(key: String): Int = attempts[key] ?: 0

    @Synchronized
    fun shouldAutoFill(key: String): Boolean = attempts(key) < maxAutoFill

    @Synchronized
    fun record(key: String): Int {
        val next = (attempts[key] ?: 0) + 1
        attempts[key] = next
        return next
    }

    @Synchronized
    fun clear() {
        attempts.clear()
    }

    /** Drops counters for every realm of one host, across schemes/ports. */
    @Synchronized
    fun clearHost(host: String) {
        if (host.isBlank()) return
        val needle = "://${host.lowercase()}:"
        attempts.keys.removeIf { it.lowercase().contains(needle) }
    }

    companion object {
        fun keyOf(scheme: String, host: String, port: Int, realm: String): String =
            "${scheme.lowercase()}://${host.lowercase()}:$port|$realm"
    }
}
