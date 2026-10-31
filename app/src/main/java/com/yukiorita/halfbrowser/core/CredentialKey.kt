package com.yukiorita.halfbrowser.core

/**
 * Builds the look-up key for saved HTTP credentials. Pure so the scoping rules
 * (scheme + host + port + realm) can be asserted in unit tests.
 */
object CredentialKey {
    fun of(scheme: String, host: String, port: Int, realm: String): String =
        "${scheme.lowercase()}://${host.lowercase().trim()}:$port|${realm.trim()}"
}
