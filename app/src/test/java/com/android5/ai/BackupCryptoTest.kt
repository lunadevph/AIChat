package com.android5.ai

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class BackupCryptoTest {
    @Test fun roundTripAndAuthenticationRejectWrongPasswordAndTampering() {
        val salt = ByteArray(16) { it.toByte() }
        val key = BackupCrypto.deriveKey("correct password".toCharArray(), salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal("private conversations and keys".toByteArray())
        val iv = cipher.iv
        fun decrypt(data: ByteArray, secret: javax.crypto.SecretKey): ByteArray {
            val reader = Cipher.getInstance("AES/GCM/NoPadding")
            reader.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
            return reader.doFinal(data)
        }
        assertEquals("private conversations and keys", String(decrypt(encrypted, key)))
        val wrong = BackupCrypto.deriveKey("wrong password".toCharArray(), salt)
        assertThrows(javax.crypto.AEADBadTagException::class.java) { decrypt(encrypted, wrong) }
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
        assertThrows(javax.crypto.AEADBadTagException::class.java) { decrypt(encrypted, key) }
    }

    @Test fun saltsProduceDifferentKeys() {
        val password = "correct password".toCharArray()
        assertFalse(BackupCrypto.deriveKey(password, ByteArray(16)).encoded.contentEquals(
            BackupCrypto.deriveKey(password, ByteArray(16) { 1 }).encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortPassword() {
        BackupCrypto.deriveKey("short".toCharArray(), ByteArray(16))
    }
}
