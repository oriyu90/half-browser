package com.yukiorita.halfbrowser.core

import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.Locale

/**
 * A WireGuard profile stored by Half-Browser.
 *
 * The profile is kept as the exact `[Interface]`/`[Peer]` text the user pasted
 * (or generated) plus a few derived, non-secret fields used by the UI. Keeping
 * the original text means an exported profile is byte-for-byte usable with the
 * official WireGuard clients, and a future config feature added upstream is not
 * silently dropped.
 *
 * Secrets: [configText] contains the private key and any pre-shared key. It is
 * persisted only through the Keystore-backed [com.yukiorita.halfbrowser.data.VpnStore]
 * and is never written to logs, notifications or the web page.
 */
@Serializable
data class VpnProfile(
    val id: String,
    val name: String,
    val configText: String,
    /** First peer endpoint as shown in the UI ("vpn.example.com:51820"). */
    val endpoint: String = "",
    /** Tunnel addresses of the interface ("10.0.0.2/32"), for the UI only. */
    val addresses: List<String> = emptyList(),
    /** DNS servers of the interface, for the UI only. */
    val dns: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class VpnProfileList(
    val version: Int = 1,
    val items: List<VpnProfile> = emptyList(),
)

/**
 * Parsed, validated view of a WireGuard configuration.
 *
 * Only the fields the app itself needs are surfaced; the authoritative parser is
 * the WireGuard library, which re-parses [source] before the tunnel is brought
 * up. This parser exists so the UI can reject a broken profile *before* it is
 * stored, show a useful error, and derive the display fields — and so the rules
 * can be unit-tested without the native library.
 */
data class ParsedVpnConfig(
    val source: String,
    val addresses: List<String>,
    val dns: List<String>,
    val listenPort: Int?,
    val mtu: Int?,
    val peers: List<ParsedPeer>,
) {
    val endpoint: String get() = peers.firstOrNull()?.endpoint.orEmpty()
    val allowedIps: List<String> get() = peers.flatMap { it.allowedIps }
}

data class ParsedPeer(
    val publicKey: String,
    val endpoint: String,
    val allowedIps: List<String>,
    val persistentKeepalive: Int?,
    val hasPresharedKey: Boolean,
)

sealed interface VpnConfigResult {
    data class Ok(val config: ParsedVpnConfig) : VpnConfigResult
    data class Error(val reason: VpnConfigError, val detail: String = "") : VpnConfigResult
}

/**
 * Reasons a configuration is rejected. Each maps to a localized message; the
 * enum keeps the parser free of Android resources so it stays unit-testable.
 */
enum class VpnConfigError {
    EMPTY,
    TOO_LARGE,
    MISSING_INTERFACE,
    MISSING_PEER,
    MISSING_PRIVATE_KEY,
    INVALID_PRIVATE_KEY,
    MISSING_PUBLIC_KEY,
    INVALID_PUBLIC_KEY,
    INVALID_PRESHARED_KEY,
    MISSING_ENDPOINT,
    INVALID_ENDPOINT,
    MISSING_ALLOWED_IPS,
    INVALID_ALLOWED_IPS,
    INVALID_ADDRESS,
    INVALID_DNS,
    INVALID_PORT,
    INVALID_MTU,
    INVALID_KEEPALIVE,
    UNKNOWN_KEY,
    DUPLICATE_SECTION,
    MALFORMED_LINE,
}

/**
 * Strict WireGuard `wg-quick` configuration parser.
 *
 * Design notes:
 *  - **Reject rather than repair.** A silently "fixed" config can route traffic
 *    somewhere the user did not intend, so every deviation is an error.
 *  - **Bounded.** Input size, line count, peer count and list lengths are capped
 *    so a pasted 50 MB file cannot stall the UI thread or exhaust memory.
 *  - **No I/O, no Android.** Pure Kotlin, so the whole rule set is unit-tested.
 */
object VpnConfigParser {

    const val MAX_BYTES = 64 * 1024
    const val MAX_PEERS = 32
    const val MAX_LIST_ITEMS = 32
    const val MAX_NAME_LENGTH = 40
    const val MIN_MTU = 576
    const val MAX_MTU = 9000

    /** Plain unsigned decimal ("0", "1", "51820"); rejects "+8", "-0", "008". */
    private val NUMERIC_TEXT = Regex("0|[1-9][0-9]*")

    /**
     * Keys accepted in `[Interface]`: exactly the set the WireGuard Android
     * library (and official wg-quick) understands, plus the scope keys the
     * library maps to `addAllowedApplication`/`addDisallowedApplication`.
     * Amnezia/future keys (FwMark, PresharedKey in Interface, JunkPacket*,
     * S1/S2, Jc, ...) are rejected with UNKNOWN_KEY: wg-quick does not
     * support them and silently ignoring them would mislead the user.
     */
    private val INTERFACE_KEYS = setOf(
        "privatekey", "address", "dns", "listenport", "mtu",
        "excludedapplications", "includedapplications",
    )

    /** Keys accepted in `[Peer]`. */
    private val PEER_KEYS = setOf(
        "publickey", "presharedkey", "allowedips", "endpoint",
        "persistentkeepalive",
    )

    fun parse(raw: String): VpnConfigResult {
        // Pre-normalization raw-length guard: normalize()/line handling on a
        // multi-MB paste would allocate heavily before the byte check below.
        if (raw.length > MAX_BYTES * 4) {
            return VpnConfigResult.Error(VpnConfigError.TOO_LARGE)
        }
        val text = normalize(raw)
        if (text.isBlank()) return VpnConfigResult.Error(VpnConfigError.EMPTY)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return VpnConfigResult.Error(VpnConfigError.TOO_LARGE)
        }

        var section: String? = null
        var sawInterface = false
        var sawPeer = false
        var privateKey: String? = null
        var addresses = mutableListOf<String>()
        var dns = mutableListOf<String>()
        var listenPort: Int? = null
        var mtu: Int? = null
        val peers = mutableListOf<MutablePeer>()
        var current: MutablePeer? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                when (line.lowercase(Locale.ROOT)) {
                    "[interface]" -> {
                        if (sawInterface) {
                            return VpnConfigResult.Error(VpnConfigError.DUPLICATE_SECTION, "[Interface]")
                        }
                        sawInterface = true
                        section = "interface"
                        current = null
                    }
                    "[peer]" -> {
                        if (peers.size >= MAX_PEERS) {
                            return VpnConfigResult.Error(VpnConfigError.TOO_LARGE, "peers")
                        }
                        sawPeer = true
                        section = "peer"
                        current = MutablePeer().also { peers += it }
                    }
                    else -> return VpnConfigResult.Error(VpnConfigError.UNKNOWN_KEY, line)
                }
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) return VpnConfigResult.Error(VpnConfigError.MALFORMED_LINE, line)
            val key = line.substring(0, eq).trim().lowercase(Locale.ROOT)
            val value = line.substring(eq + 1).trim()
            when (section) {
                "interface" -> when (key) {
                    "privatekey" -> {
                        if (privateKey != null) {
                            return VpnConfigResult.Error(VpnConfigError.MALFORMED_LINE, "duplicate PrivateKey")
                        }
                        if (!isWireGuardKey(value)) {
                            return VpnConfigResult.Error(VpnConfigError.INVALID_PRIVATE_KEY)
                        }
                        privateKey = value
                    }
                    "address" -> {
                        val list = splitList(value)
                        if (list.isEmpty() || !list.all(::isCidr)) {
                            return VpnConfigResult.Error(VpnConfigError.INVALID_ADDRESS, value)
                        }
                        if (addresses.size + list.size > MAX_LIST_ITEMS) {
                            return VpnConfigResult.Error(VpnConfigError.TOO_LARGE, "Address")
                        }
                        addresses += list
                    }
                    "dns" -> {
                        val list = splitList(value)
                        if (list.isEmpty() || !list.all(::isDnsEntry)) {
                            return VpnConfigResult.Error(VpnConfigError.INVALID_DNS, value)
                        }
                        if (dns.size + list.size > MAX_LIST_ITEMS) {
                            return VpnConfigResult.Error(VpnConfigError.TOO_LARGE, "DNS")
                        }
                        dns += list
                    }
                    "listenport" -> {
                        val port = if (NUMERIC_TEXT.matches(value)) value.toIntOrNull() else null
                        if (port == null || port !in 1..65535) {
                            return VpnConfigResult.Error(VpnConfigError.INVALID_PORT, value)
                        }
                        listenPort = port
                    }
                    "mtu" -> {
                        val parsed = if (NUMERIC_TEXT.matches(value)) value.toIntOrNull() else null
                        if (parsed == null || parsed !in MIN_MTU..MAX_MTU) {
                            return VpnConfigResult.Error(VpnConfigError.INVALID_MTU, value)
                        }
                        mtu = parsed
                    }
                    in INTERFACE_KEYS -> Unit // accepted, not surfaced
                    else -> return VpnConfigResult.Error(VpnConfigError.UNKNOWN_KEY, key)
                }
                "peer" -> {
                    val peer = current
                        ?: return VpnConfigResult.Error(VpnConfigError.MALFORMED_LINE, line)
                    when (key) {
                        "publickey" -> {
                            if (!isWireGuardKey(value)) {
                                return VpnConfigResult.Error(VpnConfigError.INVALID_PUBLIC_KEY)
                            }
                            peer.publicKey = value
                        }
                        "presharedkey" -> {
                            if (!isWireGuardKey(value)) {
                                return VpnConfigResult.Error(VpnConfigError.INVALID_PRESHARED_KEY)
                            }
                            peer.hasPresharedKey = true
                        }
                        "allowedips" -> {
                            val list = splitList(value)
                            if (list.isEmpty() || !list.all(::isCidr)) {
                                return VpnConfigResult.Error(VpnConfigError.INVALID_ALLOWED_IPS, value)
                            }
                            if (peer.allowedIps.size + list.size > MAX_LIST_ITEMS) {
                                return VpnConfigResult.Error(VpnConfigError.TOO_LARGE, "AllowedIPs")
                            }
                            peer.allowedIps += list
                        }
                        "endpoint" -> {
                            if (!isEndpoint(value)) {
                                return VpnConfigResult.Error(VpnConfigError.INVALID_ENDPOINT, value)
                            }
                            peer.endpoint = value
                        }
                        "persistentkeepalive" -> {
                            val keepalive = if (NUMERIC_TEXT.matches(value)) value.toIntOrNull() else null
                            if (keepalive == null || keepalive !in 0..65535) {
                                return VpnConfigResult.Error(
                                    VpnConfigError.INVALID_KEEPALIVE,
                                    value,
                                )
                            }
                            peer.keepalive = keepalive
                        }
                        in PEER_KEYS -> Unit
                        else -> return VpnConfigResult.Error(VpnConfigError.UNKNOWN_KEY, key)
                    }
                }
                else -> return VpnConfigResult.Error(VpnConfigError.MALFORMED_LINE, line)
            }
        }

        if (!sawInterface) return VpnConfigResult.Error(VpnConfigError.MISSING_INTERFACE)
        if (privateKey == null) return VpnConfigResult.Error(VpnConfigError.MISSING_PRIVATE_KEY)
        if (!sawPeer || peers.isEmpty()) return VpnConfigResult.Error(VpnConfigError.MISSING_PEER)

        val parsedPeers = mutableListOf<ParsedPeer>()
        for (peer in peers) {
            if (peer.publicKey.isBlank()) {
                return VpnConfigResult.Error(VpnConfigError.MISSING_PUBLIC_KEY)
            }
            if (peer.allowedIps.isEmpty()) {
                return VpnConfigResult.Error(VpnConfigError.MISSING_ALLOWED_IPS)
            }
            // An endpoint is optional in WireGuard (a server-side peer), but a
            // client profile that cannot dial anyone is useless in this app.
            if (peer.endpoint.isBlank()) {
                return VpnConfigResult.Error(VpnConfigError.MISSING_ENDPOINT)
            }
            parsedPeers += ParsedPeer(
                publicKey = peer.publicKey,
                endpoint = peer.endpoint,
                allowedIps = peer.allowedIps.toList(),
                persistentKeepalive = peer.keepalive,
                hasPresharedKey = peer.hasPresharedKey,
            )
        }

        return VpnConfigResult.Ok(
            ParsedVpnConfig(
                source = text,
                addresses = addresses.toList(),
                dns = dns.toList(),
                listenPort = listenPort,
                mtu = mtu,
                peers = parsedPeers,
            ),
        )
    }

    /**
     * A WireGuard key is exactly 32 bytes in standard base64 (44 chars, one '=').
     * Anything else would be rejected by the native parser later; rejecting it
     * now gives the user a precise message.
     */
    fun isWireGuardKey(value: String): Boolean {
        if (value.length != 44 || !value.endsWith("=")) return false
        val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return false
        if (decoded.size != 32) return false
        // Reject a key that is not canonical base64 (e.g. non-zero padding bits),
        // which would otherwise decode to the same bytes with a different text.
        return Base64.getEncoder().encodeToString(decoded) == value
    }

    fun isCidr(value: String): Boolean {
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.length - 1) return false
        val address = value.substring(0, slash)
        val prefixText = value.substring(slash + 1)
        // toIntOrNull() would happily accept "+8" / "-0"; the grammar only
        // allows a plain unsigned decimal, so validate the shape first.
        if (!NUMERIC_TEXT.matches(prefixText)) return false
        val prefix = prefixText.toIntOrNull() ?: return false
        val maxBits = when {
            isIpv4(address) -> 32
            isIpv6(address) -> 128
            else -> return false
        }
        return prefix in 0..maxBits
    }

    fun isEndpoint(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        val host: String
        val portText: String
        if (trimmed.startsWith("[")) {
            // [v6]:port
            val close = trimmed.indexOf(']')
            if (close <= 1) return false
            val rest = trimmed.substring(close + 1)
            if (!rest.startsWith(":")) return false
            host = trimmed.substring(1, close)
            portText = rest.substring(1)
            if (!isIpv6(host)) return false
        } else {
            val colon = trimmed.lastIndexOf(':')
            if (colon <= 0) return false
            host = trimmed.substring(0, colon)
            portText = trimmed.substring(colon + 1)
            if (host.contains(':')) return false
            if (!isIpv4(host) && !isHostname(host)) return false
        }
        if (!NUMERIC_TEXT.matches(portText)) return false
        val port = portText.toIntOrNull() ?: return false
        return port in 1..65535
    }

    /** Accepts an IP literal or a DNS name (the app resolves it before dialing). */
    private fun isDnsEntry(value: String): Boolean = isIpv4(value) || isIpv6(value)

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 &&
                part.all { it in '0'..'9' } &&
                part.toIntOrNull()?.let { it in 0..255 } == true &&
                (part.length == 1 || part[0] != '0')
        }
    }

    private fun isIpv6(value: String): Boolean {
        if (value.isEmpty() || value.length > 45) return false
        if (!value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return false
        }
        if (!value.contains(':')) return false
        // A single "::" at most; groups of 1-4 hex digits otherwise.
        val doubleColon = value.indexOf("::")
        if (doubleColon >= 0 && value.indexOf("::", doubleColon + 1) >= 0) return false
        val head: List<String>
        val tail: List<String>
        if (doubleColon >= 0) {
            head = value.substring(0, doubleColon).split(':').filter { it.isNotEmpty() }
            tail = value.substring(doubleColon + 2).split(':').filter { it.isNotEmpty() }
        } else {
            head = value.split(':')
            tail = emptyList()
            // Without "::" the address is exactly 8 groups; an embedded IPv4
            // counts as two, so 6 hex groups + IPv4 (7 parts) is also valid.
            val embeddedCandidate = head.lastOrNull()?.contains('.') == true
            val expectedParts = if (embeddedCandidate) 7 else 8
            if (head.size != expectedParts) return false
        }
        val groups = head + tail
        // Embedded IPv4 (::ffff:1.2.3.4) occupies the last two 16-bit groups
        // and is allowed only as the last "group". It is validated separately
        // below, so it must be exempt from the hex-group check.
        val hasEmbeddedIpv4 = groups.lastOrNull()?.contains('.') == true
        for ((index, group) in groups.withIndex()) {
            if (hasEmbeddedIpv4 && index == groups.lastIndex) continue
            if (group.isEmpty() || group.length > 4) return false
            if (!group.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return false
        }
        if (hasEmbeddedIpv4) {
            if (!isIpv4(value.substringAfterLast(':'))) return false
        }
        // Group-count budget: an embedded IPv4 counts as two groups. With
        // "::", at most 7 plain groups (or 6 hex groups + the embedded IPv4,
        // i.e. eight groups' worth) fit; without "::" the total must be
        // exactly eight groups' worth (6 hex groups + the embedded IPv4).
        val effectiveGroups = groups.size + if (hasEmbeddedIpv4) 1 else 0
        return if (doubleColon >= 0) {
            if (hasEmbeddedIpv4) effectiveGroups <= 8 else groups.size <= 7
        } else {
            effectiveGroups == 8
        }
    }

    private fun isHostname(value: String): Boolean {
        if (value.length !in 1..253) return false
        val labels = value.trimEnd('.').split('.')
        if (labels.isEmpty()) return false
        return labels.all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
    }

    private fun splitList(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Normalizes line endings and strips a UTF-8 BOM. A config pasted from a
     * Windows editor or an exported file otherwise fails on a stray `\r`.
     */
    fun normalize(raw: String): String =
        raw.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

    private class MutablePeer {
        var publicKey: String = ""
        var endpoint: String = ""
        val allowedIps = mutableListOf<String>()
        var keepalive: Int? = null
        var hasPresharedKey: Boolean = false
    }
}

/**
 * Naming rules for a profile. The tunnel name handed to WireGuard must match
 * `[a-zA-Z0-9_=+.-]{1,15}`; the display name is free text, so a safe tunnel name
 * is derived from the profile id instead of the user's text.
 */
object VpnNaming {

    private val TUNNEL_NAME = Regex("[a-zA-Z0-9_=+.-]{1,15}")

    fun sanitizeDisplayName(raw: String, fallback: String): String {
        val cleaned = raw.filter { it.code >= 0x20 && it.code != 0x7f }
            .trim()
            .take(VpnConfigParser.MAX_NAME_LENGTH)
        return cleaned.ifBlank { fallback }
    }

    fun tunnelNameFor(profileId: String): String {
        val compact = profileId.filter { it.isLetterOrDigit() }.take(12)
        val name = "hb$compact"
        return if (TUNNEL_NAME.matches(name)) name else "hbvpn"
    }

    fun isValidTunnelName(name: String): Boolean = TUNNEL_NAME.matches(name)
}
