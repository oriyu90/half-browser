package com.yukiorita.halfbrowser.core

import java.security.MessageDigest
import java.security.cert.X509Certificate

sealed interface TrustDecision {
    /** Same host, same certificate: proceed silently. */
    data object Trusted : TrustDecision

    /** No pin for this host: show the interstitial. */
    data object Unknown : TrustDecision

    /** A pin exists but the certificate changed: warn harder, never auto-proceed. */
    data class Changed(val previous: TrustedSite) : TrustDecision
}

/**
 * Trust-on-first-use policy for self-signed certificates.
 *
 * Scope is host + exact certificate SHA-256. This is deliberately narrower than
 * "trust every certificate for this host": a renewed/rotated certificate must be
 * re-confirmed by the user. It is *not* a replacement for a real CA chain — the
 * WebView never validates this certificate; only an explicit user decision allows it.
 */
object TrustPolicy {

    fun fingerprintOf(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun normalizeFingerprint(raw: String?): String =
        raw.orEmpty().lowercase().replace(":", "").replace(" ", "").trim()

    fun formatFingerprint(normalized: String): String {
        val hex = normalizeFingerprint(normalized)
        if (hex.length != 64) return normalized
        return hex.chunked(2).joinToString(":") { it.uppercase() }
    }

    fun decide(
        host: String,
        port: Int,
        fingerprintSha256: String,
        saved: TrustedSite?,
    ): TrustDecision {
        val normalizedHost = host.lowercase().trim()
        val normalizedFingerprint = normalizeFingerprint(fingerprintSha256)
        if (saved == null || saved.host.lowercase() != normalizedHost) return TrustDecision.Unknown
        if (saved.port != 0 && port != 0 && saved.port != port) return TrustDecision.Unknown
        return if (normalizeFingerprint(saved.fingerprintSha256) == normalizedFingerprint) {
            TrustDecision.Trusted
        } else {
            TrustDecision.Changed(saved)
        }
    }

    /**
     * Pinning is only offered for a certificate that is self-signed/untrusted.
     * Expired, not-yet-valid or hostname-mismatched certificates may be used for
     * this visit at most: remembering them would hide a serious misconfiguration.
     */
    fun canPin(primaryError: Int): Boolean = primaryError == 3 // SslError.SSL_UNTRUSTED

    /** Short, non-leaking description of a certificate for the interstitial. */
    fun describeCertificate(certificate: X509Certificate): CertificateInfo = CertificateInfo(
        subject = certificate.subjectDN?.name.orEmpty(),
        issuer = certificate.issuerDN?.name.orEmpty(),
        fingerprintSha256 = fingerprintOf(certificate),
        notBefore = certificate.notBefore?.time ?: 0L,
        notAfter = certificate.notAfter?.time ?: 0L,
    )

    data class CertificateInfo(
        val subject: String,
        val issuer: String,
        val fingerprintSha256: String,
        val notBefore: Long,
        val notAfter: Long,
    )
}
