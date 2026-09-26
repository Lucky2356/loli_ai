package ai.loli.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.loli.app.BuildConfig
import ai.loli.app.LoliApp
import ai.loli.app.R
import ai.loli.app.reminders.Notifications
import ai.loli.core.data.LoliJson
import ai.loli.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** [sha256] — контрольная сумма из GitHub (поле digest ассета); скачанный файл обязан с ней совпасть. */
data class UpdateInfo(val version: String, val apkUrl: String, val size: Long, val notes: String, val sha256: String? = null)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Автообновление из GitHub Releases: проверка новой версии, скачивание APK и установка через PackageInstaller.
 * Первое обновление Android попросит подтвердить (и разрешить установку из Лоли). После этого на Android 12+
 * Лоли становится «источником установки» и следующие версии ставятся сами.
 */
class UpdateManager(private val context: Context) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("loli_updates", Context.MODE_PRIVATE)
    private val dir = File(context.cacheDir, "updates")

    val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore('-')
    var lastCheckedAt: Long
        get() = prefs.getLong("last_check", 0)
        private set(v) = prefs.edit().putLong("last_check", v).apply()

    /** Разрешено ли Лоли устанавливать приложения (Android 8+ спрашивает один раз). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun check(): UpdateInfo? = mutex.withLock {
        _state.value = UpdateState.Checking
        try {
            val info = withContext(Dispatchers.IO) { fetchLatest() }
            lastCheckedAt = System.currentTimeMillis()
            _state.value = if (info != null) UpdateState.Available(info) else UpdateState.UpToDate
            info
        } catch (e: Exception) {
            Logger.w(TAG, "Проверка обновлений не удалась", e)
            _state.value = UpdateState.Error(e.message ?: "нет связи с GitHub")
            null
        }
    }

    /** Скачивает и запускает установку. Возвращает false, если что-то пошло не так (причина — в [state]). */
    suspend fun downloadAndInstall(info: UpdateInfo): Boolean = mutex.withLock {
        try {
            val apk = withContext(Dispatchers.IO) { download(info) }
            _state.value = UpdateState.Installing(info)
            withContext(Dispatchers.IO) { install(apk) }
            true
        } catch (e: Exception) {
            Logger.w(TAG, "Обновление не установлено", e)
            _state.value = UpdateState.Error(e.message ?: "не удалось скачать обновление")
            false
        }
    }

    internal fun reportInstallResult(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Error("Новая версия подписана другим ключом. Один раз удалите Лоли и установите заново — дальше обновления пойдут сами.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Error("Недостаточно места для обновления.")
            else -> UpdateState.Error("Установка не удалась${message?.let { ": $it" } ?: ""}.")
        }
    }

    private fun fetchLatest(): UpdateInfo? {
        val conn = (URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LoliAssistant")
        }
        try {
            when (conn.responseCode) {
                200 -> Unit
                404 -> error("релизы недоступны (репозиторий закрыт или релизов ещё нет)")
                403, 429 -> error("GitHub временно ограничил проверки, попробуйте позже")
                else -> error("GitHub ответил ${conn.responseCode}")
            }
            val json = LoliJson.parseToJsonElement(conn.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val version = json["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: return null
            if (compareVersions(version, currentVersion) <= 0) return null
            val asset = (json["assets"] as? JsonArray).orEmpty().map { it.jsonObject }
                .firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith("-release.apk") == true }
                ?: return null
            return UpdateInfo(
                version = version,
                // Скачиваем только с GitHub — никаких сторонних адресов.
                apkUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("https://github.com/") || it.startsWith("https://objects.githubusercontent.com/") } ?: return null,
                size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
                notes = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                sha256 = asset["digest"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun download(info: UpdateInfo): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { if (!it.name.contains(info.version)) it.delete() }
        val target = File(dir, "loli-${info.version}.apk")
        if (target.exists() && info.size > 0 && target.length() == info.size && runCatching { verify(target, info) }.isSuccess) return target
        val part = File(dir, target.name + ".part")
        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LoliAssistant")
        }
        try {
            if (conn.responseCode !in 200..299) error("сервер ответил ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            var read = 0L
            var reported = 0f
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val p = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
                        if (p - reported >= 0.01f) { reported = p; _state.value = UpdateState.Downloading(info, p) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (info.size > 0 && part.length() != info.size) { part.delete(); error("файл скачан не полностью") }
        verify(part, info)
        target.delete()
        part.renameTo(target)
        return target
    }

    /** Файл должен совпасть с контрольной суммой из GitHub — иначе он повреждён или подменён. */
    private fun verify(file: File, info: UpdateInfo) {
        val expected = info.sha256 ?: run {
            file.delete()
            error("у файла обновления нет контрольной суммы — установка отменена")
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            error("контрольная сумма не совпала — файл повреждён или подменён, установка отменена")
        }
        verifyPackage(file)
    }

    /**
     * Вторая линия защиты: внутри APK должна быть именно Лоли, подписанная тем же ключом, и версия новее текущей.
     * (Android и сам не даст поставить чужую подпись поверх, но так подмена обнаруживается ещё до установки.)
     */
    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun certs(info: android.content.pm.PackageInfo?): Set<String> {
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info?.signingInfo?.apkContentsSigners else info?.signatures
            return sigs.orEmpty().map { sig ->
                java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val problem = when {
            archive == null -> "файл обновления не является приложением"
            archive.packageName != context.packageName -> "в файле другое приложение"
            certs(archive).isEmpty() || certs(archive) != certs(installed) -> "файл подписан другим ключом"
            androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(archive) <= androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed) -> "версия в файле не новее установленной"
            else -> null
        }
        if (problem != null) {
            file.delete()
            error("$problem — установка отменена")
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Если Лоли уже ставила себя сама, Android 12+ обновит её без лишних вопросов.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("loli.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateInstallReceiver::class.java),
                // PackageInstaller дописывает в интент статус — он обязан быть изменяемым.
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
            )
            session.commit(status.intentSender)
        }
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("loli-update-check", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        private const val TAG = "Update"

        /** «1.10.0» > «1.9.3»; суффиксы вроде «-debug» не учитываются. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val x = parts(a)
            val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d
            }
            return 0
        }
    }
}

/** Результат установки от PackageInstaller: запрос подтверждения, успех или ошибка. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LoliApp
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // На экране — сразу окно подтверждения; в фоне — уведомление «Нажмите, чтобы обновить».
            if (!app.container.launcher.launch(confirm)) {
                val pi = PendingIntent.getActivity(context, 7, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val n = NotificationCompat.Builder(context, Notifications.CHANNEL_SYSTEM)
                    .setSmallIcon(R.drawable.ic_stat_loli)
                    .setContentTitle(context.getString(R.string.update_ready))
                    .setContentText(context.getString(R.string.update_tap))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                Notifications.notifySafely(context, UPDATE_NOTIFICATION_ID, n)
            }
            return
        }
        app.container.updates.reportInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    companion object {
        const val UPDATE_NOTIFICATION_ID = 1004
    }
}

/** Фоновая проверка раз в 12 часов: если есть новая версия и включено автообновление — скачать и установить. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as LoliApp).container
        c.awaitReady()
        if (!c.settings.current().autoUpdate) return Result.success()
        val info = c.updates.check() ?: return Result.success()
        if (!c.updates.canInstall()) {
            // Разрешения на установку ещё нет — просто сообщаем о новой версии.
            val open = PendingIntent.getActivity(
                applicationContext, 8,
                Intent(applicationContext, ai.loli.app.ui.MainActivity::class.java).setAction(ai.loli.app.ui.MainActivity.ACTION_UPDATE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(applicationContext, Notifications.CHANNEL_SYSTEM)
                .setSmallIcon(R.drawable.ic_stat_loli)
                .setContentTitle(applicationContext.getString(R.string.update_available, info.version))
                .setContentText(applicationContext.getString(R.string.update_tap))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            Notifications.notifySafely(applicationContext, UpdateInstallReceiver.UPDATE_NOTIFICATION_ID, n)
            return Result.success()
        }
        c.updates.downloadAndInstall(info)
        return Result.success()
    }
}
