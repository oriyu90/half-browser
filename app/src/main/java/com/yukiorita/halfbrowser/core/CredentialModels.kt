package com.yukiorita.halfbrowser.core

import kotlinx.serialization.Serializable

/**
 * One saved HTTP-auth credential. Moved out of the storage layer so backup and
 * import can serialise it without depending on Android.
 */
@Serializable
data class StoredCredential(
    val host: String,
    val realm: String,
    val username: String,
    val password: String,
    val scheme: String = "https",
    val port: Int = 443,
    val savedAt: Long = 0L,
)

@Serializable
data class CredentialBundle(
    val version: Int = 1,
    val items: Map<String, StoredCredential> = emptyMap(),
)
