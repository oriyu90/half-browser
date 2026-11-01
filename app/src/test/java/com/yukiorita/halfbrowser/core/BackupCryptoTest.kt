package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val passphrase = "correct horse battery".toCharArray()

    @Test
    fun `encrypt then decrypt round trips`() {
        val secret = """{"items":{"k":{"username":"u","password":"p"}}}"""
        val encrypted = BackupCrypto.encrypt(secret, passphrase)
        assertNotNull(encrypted)
        assertEquals(secret, BackupCrypto.decrypt(encrypted!!, passphrase))
    }

    @Test
    fun `wrong passphrase fails cleanly`() {
        val encrypted = BackupCrypto.encrypt("top secret", passphrase)!!
        assertNull(BackupCrypto.decrypt(encrypted, "wrong passphrase".toCharArray()))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val encrypted = BackupCrypto.encrypt("top secret", passphrase)!!
        val bytes = java.util.Base64.getDecoder().decode(encrypted.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        val tampered = encrypted.copy(
            ciphertext = java.util.Base64.getEncoder().encodeToString(bytes),
        )
        assertNull(BackupCrypto.decrypt(tampered, passphrase))
    }

    @Test
    fun `short passphrases are refused`() {
        assertNull(BackupCrypto.encrypt("x", "short".toCharArray()))
        assertTrue(BackupCrypto.isAcceptablePassphrase(passphrase))
    }

    @Test
    fun `identical plaintext encrypts differently each time`() {
        val a = BackupCrypto.encrypt("same", passphrase)!!
        val b = BackupCrypto.encrypt("same", passphrase)!!
        assertTrue(a.salt != b.salt)
        assertTrue(a.ciphertext != b.ciphertext)
    }

    @Test
    fun `payload reports the derivation parameters`() {
        val encrypted = BackupCrypto.encrypt("x", passphrase)!!
        assertEquals(BackupCrypto.DEFAULT_ITERATIONS, encrypted.iterations)
        assertTrue(encrypted.salt.isNotBlank())
        assertTrue(encrypted.iv.isNotBlank())
    }
}
