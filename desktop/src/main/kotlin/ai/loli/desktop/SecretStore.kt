package ai.loli.desktop

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
 */
class SecretStore(private val file: File) {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    val encrypted: Boolean get() = windows

    @Synchronized
    fun get(key: String): String? = runCatching {
        val raw = load().getProperty(key) ?: return null
        val bytes = Base64.getDecoder().decode(raw)
        String(if (windows) Crypt32Util.cryptUnprotectData(bytes) else bytes, Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    fun put(key: String, value: String?) {
        val p = load()
        if (value.isNullOrEmpty()) p.remove(key) else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            p[key] = Base64.getEncoder().encodeToString(if (windows) Crypt32Util.cryptProtectData(bytes) else bytes)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { p.store(it, null) }
        if (!windows) runCatching { Files.setPosixFilePermissions(file.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) }
    }

    private fun load(): Properties = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
}
