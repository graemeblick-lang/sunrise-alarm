package au.sunrise.alarm

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps secrets (the Todoist token) encrypted in the settings, with a key held by Android's keystore that
 * never leaves the phone. Where the keystore isn't available (unit tests), text is left as it is.
 */
object Secret {
    private const val ALIAS = "sunrise-secrets"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    fun isSealed(s: String) = s.startsWith(PREFIX)

    /** Encrypt [plain] (already-sealed text and "" are left alone). */
    fun seal(plain: String): String {
        if (plain.isEmpty() || isSealed(plain)) return plain
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            PREFIX + Base64.getEncoder().encodeToString(c.iv + c.doFinal(plain.toByteArray()))
        }.getOrDefault(plain)
    }

    /** Decrypt what [seal] made (plain text passes through). "" if it can't be read any more. */
    private val opened = java.util.concurrent.ConcurrentHashMap<String, String>()   // the screens read it often

    fun open(s: String): String {
        if (!isSealed(s)) return s
        opened[s]?.let { return it }
        return runCatching {
            val all = Base64.getDecoder().decode(s.removePrefix(PREFIX))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12)).also { opened[s] = it }
        }.getOrDefault("")
    }
}
