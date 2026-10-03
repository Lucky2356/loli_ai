package ai.loli.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Файл резервной копии: заголовок + AES-256-GCM(gzip(JSON)). Ключ выводится из пароля пользователя (PBKDF2-HMAC-SHA256),
 * сам пароль нигде не хранится. Заголовок входит в проверяемые данные GCM: подмена любого байта файла даёт ошибку.
 *
 * Формат: `LOLIBK` (6) · версия (1) · число итераций (4) · соль (16) · IV (12) · шифротекст с тегом.
 */
object BackupCodec {
    const val VERSION = 1
    const val EXTENSION = "lolibk"
    const val MIN_PASSWORD = 8

    private val MAGIC = "LOLIBK".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    private const val IV = 12
    private const val HEADER = 6 + 1 + 4 + SALT + IV
    private const val ITERATIONS = 210_000
    /** Чужой файл не заставит нас считать миллиарды итераций. */
    private const val MAX_ITERATIONS = 2_000_000
    private const val MIN_ITERATIONS = 10_000
    const val MAX_FILE_BYTES = 50L * 1024 * 1024
    private const val MAX_JSON_BYTES = 200L * 1024 * 1024

    open class BackupException(message: String) : Exception(message)
    class WrongPassword : BackupException("Пароль не подошёл или файл повреждён.")
    class NotABackup : BackupException("Это не файл резервной копии Лоли.")
    class TooNew : BackupException("Копия создана более новой версией Лоли. Обновите приложение и повторите.")

    fun encode(json: String, password: CharArray, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): ByteArray {
        require(password.size >= MIN_PASSWORD) { "Пароль короче $MIN_PASSWORD знаков" }
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION.toByte()).putInt(iterations).put(salt).put(iv).array()
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(json.toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header, 0, HEADER - IV)
        return header + cipher.doFinal(packed)
    }

    fun decode(data: ByteArray, password: CharArray): String {
        if (data.size < HEADER + 16 || data.size > MAX_FILE_BYTES || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackup()
        val buf = ByteBuffer.wrap(data)
        buf.position(MAGIC.size)
        val version = buf.get().toInt()
        if (version > VERSION) throw TooNew()
        if (version < 1) throw NotABackup()
        val iterations = buf.getInt()
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw NotABackup()
        val salt = ByteArray(SALT).also(buf::get)
        val iv = ByteArray(IV).also(buf::get)
        val packed = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
            cipher.updateAAD(data, 0, HEADER - IV)
            cipher.doFinal(data, HEADER, data.size - HEADER)
        } catch (e: GeneralSecurityException) {
            throw WrongPassword()
        }
        return try {
            GZIPInputStream(ByteArrayInputStream(packed)).use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > MAX_JSON_BYTES) throw NotABackup()
                    out.write(chunk, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } catch (e: java.io.IOException) {
            throw NotABackup()
        }
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
