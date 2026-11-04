package io.github.weavespeak.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session tokens and saved sign-ins, encrypted at rest with a key that never leaves the
 * Android Keystore.
 *
 * The same two files the desktop app keeps under Electron's safeStorage — tokens.dat and
 * credentials.dat — and kept apart for the same reason: a token expires and can be revoked
 * from the server, a saved password opens the account until it is changed, and signing out
 * must be able to drop one without touching the other.
 *
 * Every failure reads as "nothing saved". A file written under a key that no longer exists
 * (a reinstall, a restored backup on another phone) is recovered from by signing in again,
 * which is exactly what a person in that position expects to do.
 */
object SecretStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "weave-secrets"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** The only files this store will touch. Anything else from the page is refused. */
    val STORES = setOf("tokens", "credentials")

    private val lock = Any()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun fileOf(context: Context, store: String) = File(context.filesDir, "$store.dat")

    private fun read(context: Context, store: String): JSONObject {
        val file = fileOf(context, store)
        if (!file.exists()) return JSONObject()
        return try {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            JSONObject(String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun write(context: Context, store: String, map: JSONObject): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(map.toString().toByteArray(Charsets.UTF_8))
        // Written beside the file and renamed over it, so a write cut short by the app being
        // killed leaves the previous file whole rather than half of a new one.
        val temp = File(context.filesDir, "$store.dat.tmp")
        temp.writeBytes(cipher.iv + body)
        temp.renameTo(fileOf(context, store))
    } catch (e: Exception) {
        AppLog.write(context, "warn", "could not save $store: ${e.javaClass.simpleName}")
        false
    }

    fun get(context: Context, store: String, key: String): String? = synchronized(lock) {
        val map = read(context, store)
        if (map.has(key)) map.getString(key) else null
    }

    fun set(context: Context, store: String, key: String, value: String): Boolean = synchronized(lock) {
        val map = read(context, store)
        map.put(key, value)
        write(context, store, map)
    }

    fun clear(context: Context, store: String, key: String): Boolean = synchronized(lock) {
        val map = read(context, store)
        map.remove(key)
        write(context, store, map)
    }
}
