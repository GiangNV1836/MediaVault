package com.vaultplayer.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** AES-GCM bằng khóa nằm trong Android Keystore (không xuất được). Kết quả = iv(12) + ciphertext. */
object KeystoreAes {
    private const val PROVIDER = "AndroidKeyStore"
    const val MASTER = "vp_master"
    const val THUMB = "vp_thumb"

    private fun ks(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, SecretKey>()

    fun key(alias: String): SecretKey {
        cache[alias]?.let { return it }
        (ks().getKey(alias, null) as? SecretKey)?.let { cache[alias] = it; return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        g.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey().also { cache[alias] = it }
    }

    fun delete(alias: String) { cache.remove(alias); runCatching { ks().deleteEntry(alias) } }

    fun encrypt(alias: String, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(alias))
        if (aad != null) c.updateAAD(aad)
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(alias: String, blob: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, blob, 0, 12))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

object Kdf {
    fun pbkdf2(pass: CharArray, salt: ByteArray, iterations: Int = 100_000, bits: Int = 256): ByteArray {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return f.generateSecret(PBEKeySpec(pass, salt, iterations, bits)).encoded
    }
}
