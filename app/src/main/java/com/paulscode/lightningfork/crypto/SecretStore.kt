package com.paulscode.lightningfork.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device key, encrypted at rest with a hardware-backed Keystore AES-GCM key
 * that works only while the phone is unlocked. The ciphertext lives in private
 * preferences, excluded from backups.
 */
class SecretStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("lf_secrets", Context.MODE_PRIVATE)
    // Opened on first use, so a Keystore fault is a failed read, not a crash
    // at launch.
    private val ks by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    @Volatile private var cached: String? = null

    /** What reading the key gave. */
    sealed interface Read {
        data class Key(val value: String) : Read
        /** The phone is locked, or the Keystore is briefly unavailable: try later. */
        data object Unavailable : Read
        /** The Keystore key is gone or no longer opens the ciphertext: pair again. */
        data object Lost : Read
        data object None : Read
    }

    fun putApiKey(value: String) {
        wrapAndStore(KEY_API, value)
        cached = value
    }

    fun readApiKey(): Read {
        cached?.let { return Read.Key(it) }
        if (!hasApiKey()) return Read.None
        return try {
            val key = loadAndUnwrap(KEY_API) ?: return Read.Lost
            cached = key
            Read.Key(key)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            Read.Lost
        } catch (e: javax.crypto.AEADBadTagException) {
            Read.Lost
        } catch (e: Exception) {
            Read.Unavailable
        }
    }

    fun getApiKey(): String? = (readApiKey() as? Read.Key)?.value

    fun hasApiKey(): Boolean = prefs.contains("$KEY_API.ct")

    /** Forget the decrypted key in memory; it is read again when next needed. */
    fun forgetCached() {
        cached = null
    }

    /** Wipe on unpair; the Keystore key goes too, so nothing is recoverable. */
    fun clear() {
        cached = null
        prefs.edit().clear().apply()
        runCatching { ks.deleteEntry(KEY_API) }
    }

    private fun wrapAndStore(alias: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey(alias))
        }
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // commit, not apply: a process death right after pairing must not
        // keep the pairing and lose the key.
        prefs.edit()
            .putString("$alias.iv", b64(cipher.iv))
            .putString("$alias.ct", b64(ct))
            .commit()
    }

    private fun loadAndUnwrap(alias: String): String? {
        val iv = prefs.getString("$alias.iv", null) ?: return null
        val ct = prefs.getString("$alias.ct", null) ?: return null
        if (!ks.containsAlias(alias)) return null
        val key = (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unb64(iv)))
        }
        return String(cipher.doFinal(unb64(ct)), Charsets.UTF_8)
    }

    private fun wrappingKey(alias: String): SecretKey {
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                // Usable only while the phone is unlocked, where Android can
                // say so (API 28); minSdk is 26.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) setUnlockedDeviceRequired(true)
            }
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_API = "lf_apikey_wrap"
    }
}
