package ai.loli.desktop

import ai.loli.core.util.Logger
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.Properties

/**
 * API-ключи на компьютере. В Windows шифруются DPAPI — расшифровать их может только этот пользователь
 * Windows на этом компьютере (файл, скопированный на другой ПК, бесполезен). На других системах —
 * файл с доступом только для владельца.
 *
 * Расшифровка DPAPI — системный вызов; ключ нужен на каждом ответе и при каждой отрисовке статуса,
 * поэтому расшифрованное держим в памяти процесса и сбрасываем при записи.
 */
class SecretStore(private val file: File) {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    val encrypted: Boolean get() = windows

    private val cache = HashMap<String, String?>()

    @Synchronized
    fun get(key: String): String? {
        if (cache.containsKey(key)) return cache[key]
        val value = runCatching {
            val raw = load().getProperty(key) ?: return@runCatching null
            val bytes = Base64.getDecoder().decode(raw)
            String(if (windows) Crypt32Util.cryptUnprotectData(bytes) else bytes, Charsets.UTF_8)
        }.onFailure { Logger.w("Secrets", "Ключ не расшифровался (другая учётная запись или повреждён файл)", it) }.getOrNull()
        cache[key] = value
        return value
    }

    /** true — сохранено. */
    @Synchronized
    fun put(key: String, value: String?): Boolean = runCatching {
        val p = load()
        if (value.isNullOrEmpty()) p.remove(key) else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            p[key] = Base64.getEncoder().encodeToString(if (windows) Crypt32Util.cryptProtectData(bytes) else bytes)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { p.store(it, null) }
        if (!windows) runCatching { Files.setPosixFilePermissions(file.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
        cache[key] = value?.takeIf { it.isNotEmpty() }
        true
    }.onFailure { Logger.w("Secrets", "Ключ не сохранился", it); cache.remove(key) }.getOrDefault(false)

    private fun load(): Properties = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
}
