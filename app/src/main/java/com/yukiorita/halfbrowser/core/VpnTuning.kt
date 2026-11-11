package com.yukiorita.halfbrowser.core

import java.util.Locale

/**
 * Runtime adjustments applied to a profile just before the tunnel is built.
 *
 * The stored profile text is never modified — the settings editor and export
 * keep showing exactly what the user pasted. These adjustments only shape the
 * config handed to the WireGuard library.
 *
 * Why each one exists
 * -------------------
 *  - **PersistentKeepalive (default 25 s).** Without it an idle tunnel sends
 *    nothing, so a carrier-grade NAT on the phone or on the server side drops
 *    the UDP mapping after a few minutes and the *next* packet is lost. This is
 *    the most common cause of "the VPN says connected after sleep but nothing
 *    loads". WireGuard's documentation recommends 25 s for peers behind NAT.
 *    A user-supplied non-zero value is kept; a literal `0` (explicitly off) is
 *    replaced too, because an idle browser tunnel without keepalive is exactly
 *    the reported failure mode.
 *  - **MTU (1280).** A profile without MTU inherits the interface MTU, which on
 *    Wi-Fi is usually 1500. Through a PPPoE / WireGuard-over-NAT path that makes
 *    large TLS handshake packets get dropped (pages hang while small requests
 *    succeed). 1280 is the IPv6 minimum and what the official WireGuard Android
 *    app uses on mobile networks, so a missing MTU is filled in with it and a
 *    larger one is lowered to it. A *smaller* user value is left alone: it is
 *    a deliberate choice for a path with an even smaller MTU, and lowering the
 *    value further can only break more.
 *  - **DNS cap (3 servers).** Android's VpnService takes a short list; a config
 *    naming many public resolvers makes the system resolver time out one by one,
 *    which looks like a dead VPN.
 *
 * Pure (text in, text out) and covered by unit tests.
 */
object VpnTuning {

    const val DEFAULT_KEEPALIVE_SECONDS = 25

    /**
     * Keepalive used when the user turns on the power-saving option. Still
     * inside the range WireGuard documents for NAT traversal, but long enough
     * that a strict firewall can drop an idle mapping, hence opt-in.
     */
    const val POWER_SAVER_KEEPALIVE_SECONDS = 60
    const val DEFAULT_MTU = 1280
    const val MAX_DNS_SERVERS = 3

    /**
     * @param configText a validated WireGuard config
     * @param keepaliveSeconds the keepalive to enforce (see [DEFAULT_KEEPALIVE_SECONDS]
     *   and [POWER_SAVER_KEEPALIVE_SECONDS])
     * @return the same config with keepalive / MTU / DNS adjusted
     */
    fun tune(configText: String, keepaliveSeconds: Int = DEFAULT_KEEPALIVE_SECONDS): String {
        val keepalive = keepaliveSeconds.coerceIn(1, 65_535)
        val lines = configText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>(lines.size + 4)
        var section = ""
        var sawMtu = false
        var interfaceInsertAt = -1
        // True while the current peer has not yet emitted a keepalive line.
        var peerNeedsKeepalive = false

        fun closePeer() {
            if (peerNeedsKeepalive) {
                out += "PersistentKeepalive = $keepalive"
                peerNeedsKeepalive = false
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                closePeer()
                section = trimmed.lowercase(Locale.ROOT)
                // Remember where the [Interface] body ends: a missing MTU is
                // inserted there (after the blank separator), never in a peer.
                if (section == "[interface]") interfaceInsertAt = -1
                if (section == "[peer]") peerNeedsKeepalive = true
                out += line
                continue
            }
            val key = trimmed.substringBefore('=').trim().lowercase(Locale.ROOT)
            when {
                section == "[interface]" && key == "dns" -> {
                    val entries = trimmed.substringAfter('=').trim()
                        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val kept = entries.take(MAX_DNS_SERVERS)
                    // An empty `DNS =` line is kept verbatim: dropping it would
                    // silently change which resolver the system uses (the
                    // interface default), which is not this object's job.
                    out += if (kept.isEmpty()) line else "DNS = ${kept.joinToString(", ")}"
                }
                section == "[interface]" && key == "mtu" -> {
                    sawMtu = true
                    val parsed = trimmed.substringAfter('=').trim().toIntOrNull()
                    out += if (parsed != null && parsed > DEFAULT_MTU) "MTU = $DEFAULT_MTU" else line
                }
                section == "[peer]" && key == "persistentkeepalive" -> {
                    val value = trimmed.substringAfter('=').trim()
                    out += if (value == "0") {
                        "PersistentKeepalive = $keepalive"
                    } else {
                        line
                    }
                    peerNeedsKeepalive = false
                }
                else -> out += line
            }
            // The insertion point is the end of the [Interface] body, so it must
            // follow every line that belongs to that section (blank separators
            // included) and stop growing once the next section starts.
            if (section == "[interface]") interfaceInsertAt = out.size
        }
        closePeer()

        if (!sawMtu) {
            // The parser guarantees an [Interface] section, so the index is valid;
            // guard anyway so a malformed input can never throw here.
            val at = interfaceInsertAt.coerceIn(0, out.size)
            out.add(at, "MTU = $DEFAULT_MTU")
        }
        return out.joinToString("\n")
    }
}
