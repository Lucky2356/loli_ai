package ai.loli.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ai.loli.core.util.Logger
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранилище секретов (API-ключи, токены Supabase, пароль локальной БД).
 * Значения шифруются AES-256-GCM ключом, который создаётся и живёт в Android Keystore
 * (не извлекается из устройства). В SharedPreferences лежит только шифротекст.
 */
class KeystoreSecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            // Ключ Keystore мог быть утрачен (например, восстановление на другом устройстве) — секрет недоступен.
            Logger.w(TAG, "Не удалось расшифровать секрет «$key»", e)
            null
        }
    }

    @Synchronized
    fun put(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    /** Пароль для шифрования локальной БД (SQLCipher). Генерируется один раз. */
    @Synchronized
    fun databasePassphrase(): ByteArray {
        val existing = get(DB_KEY)
        if (existing != null) return existing.toByteArray(Charsets.UTF_8)
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = random.joinToString("") { "%02x".format(it) }
        put(DB_KEY, hex)
        return hex.toByteArray(Charsets.UTF_8)
    }

    fun hasDatabasePassphrase(): Boolean = contains(DB_KEY)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "Secrets"
        private const val PREFS = "loli_secure_v1"
        private const val ALIAS = "loli_master_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val DB_KEY = "db_passphrase"

        fun aiKey(providerId: String) = "ai_key_$providerId"
        const val SESSION = "supabase_session"
        const val SUPABASE_ANON_OVERRIDE = "supabase_anon_key_override"
    }
}
