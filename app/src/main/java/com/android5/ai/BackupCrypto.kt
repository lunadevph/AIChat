package com.android5.ai

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Portable password derivation; the random salt is stored in the backup header. */
internal object BackupCrypto {
    fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        require(password.size >= 8)
        // HMAC-SHA1 PBKDF2 is available on every supported Android version, including API 24.
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, 600_000, 256)
        return try {
            SecretKeySpec(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

}
