package com.yukiorita.halfbrowser.core

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based encryption for exported passwords.
 *
 * A backup file may leave the device, so saved passwords must never be written
 * in clear text. PBKDF2-HMAC-SHA256 derives a 256-bit key from the passphrase;
 * AES-GCM then provides confidentiality and tamper detection in one step. A
 * wrong passphrase fails the GCM tag check and is reported as "could not
 * decrypt" rather than producing garbage.
 *
 * Both primitives exist on every supported Android version (API 26+), so the
 * same code runs on the JVM and is covered by unit tests.
 */
object BackupCrypto {

    /** Identifier recorded in the backup so the format can evolve later. */
    const val SCHEME = "pbkdf2-hmac-sha256/aes-256-gcm"
    const val DEFAULT_ITERATIONS = 120_000
    const val MIN_PASSPHRASE_LENGTH = 8
    const val MAX_PASSPHRASE_LENGTH = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val DERIVATION = "PBKDF2WithHmacSHA256"

    data class Encrypted(
        val salt: String,
        val iv: String,
        val ciphertext: String,
        val iterations: Int,
    )

    fun encrypt(plaintext: String, passphrase: CharArray): Encrypted? {
        if (!isAcceptablePassphrase(passphrase)) return null
        return runCatching {
            val random = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val key = deriveKey(passphrase, salt, DEFAULT_ITERATIONS)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val data = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val encoder = Base64.getEncoder()
            Encrypted(
                salt = encoder.encodeToString(salt),
                iv = encoder.encodeToString(iv),
                ciphertext = encoder.encodeToString(data),
                iterations = DEFAULT_ITERATIONS,
            )
        }.getOrNull()
    }

    /** @return the plaintext, or null when the passphrase is wrong or data is corrupt. */
    fun decrypt(payload: Encrypted, passphrase: CharArray): String? {
        if (!isAcceptablePassphrase(passphrase)) return null
        if (payload.iterations !in 1..1_000_000) return null
        return runCatching {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(payload.salt)
            val iv = decoder.decode(payload.iv)
            val data = decoder.decode(payload.ciphertext)
            if (salt.size < SALT_BYTES || iv.size != IV_BYTES) return null
            val key = deriveKey(passphrase, salt, payload.iterations)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isAcceptablePassphrase(passphrase: CharArray): Boolean =
        passphrase.size in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(DERIVATION)
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
