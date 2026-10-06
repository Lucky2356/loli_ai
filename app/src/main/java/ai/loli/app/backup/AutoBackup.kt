package ai.loli.app.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ai.loli.app.LoliApp
import ai.loli.app.R
import ai.loli.app.reminders.Notifications
import ai.loli.app.ui.MainActivity
import ai.loli.core.backup.BackupCodec
import ai.loli.core.util.Logger
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Автокопия раз в неделю в папку, которую выбрал человек (телефон, SD-карта, облачная папка).
 * Файл тот же, что и при ручной копии: зашифрован паролем. Пароль хранится в защищённом хранилище телефона
 * (Android Keystore), иначе фоновая копия была бы невозможна. В папке остаются [KEEP] последних автокопий.
 */
object AutoBackup {
    private const val WORK = "loli-auto-backup"
    private const val PREFS = "loli_auto_backup"
    private const val KEY_URI = "folder"
    private const val SECRET = "auto_backup_password"
    private const val PREFIX = "loli-auto-"
    private const val KEEP = 4

    fun folder(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URI, null)?.let(Uri::parse)

    fun enabled(context: Context): Boolean = folder(context) != null

    /** Включить: папка уже выбрана через системное окно, право на неё закрепляем навсегда. */
    fun enable(context: Context, tree: Uri, password: CharArray) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        (context.applicationContext as LoliApp).container.secrets.put(SECRET, String(password))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URI, tree.toString()).apply()
        schedule(context)
    }

    fun disable(context: Context) {
        folder(context)?.let { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        (context.applicationContext as LoliApp).container.secrets.put(SECRET, null)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_URI).apply()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Раз в 7 дней, когда заряда достаточно. KEEP: при каждом запуске приложения расписание не сдвигается. */
    fun schedule(context: Context) {
        if (!enabled(context)) return
        val request = PeriodicWorkRequestBuilder<Worker>(7, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Сделать копию сейчас; возвращает имя файла. Бросает исключение, если папка недоступна. */
    suspend fun runNow(context: Context): String {
        val c = (context.applicationContext as LoliApp).container
        c.awaitReady()
        val tree = folder(context) ?: error("папка не выбрана")
        val password = c.secrets.get(SECRET) ?: error("пароль автокопии недоступен — включите автокопию заново")
        val bytes = c.backup.export(password.toCharArray(), includeChat = c.settings.current().syncChat)
        val resolver = context.contentResolver
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "$PREFIX${LocalDate.now()}.${BackupCodec.EXTENSION}"
        val file = DocumentsContract.createDocument(resolver, dir, "application/octet-stream", name) ?: error("не удалось создать файл в папке")
        (resolver.openOutputStream(file, "w") ?: error("не удалось открыть файл")).use { it.write(bytes) }
        BackupNudge.markDone(context)
        prune(context, tree)
        return name
    }

    /** Старые автокопии удаляем, ручные копии и чужие файлы не трогаем. */
    private fun prune(context: Context, tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Pair<String, String>>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(0) ?: continue
                val n = cur.getString(1) ?: continue
                if (n.startsWith(PREFIX) && n.endsWith(".${BackupCodec.EXTENSION}")) found += id to n
            }
        }
        // Имя содержит дату ГГГГ-ММ-ДД — сортировка по имени совпадает с сортировкой по времени.
        found.sortedByDescending { it.second }.drop(KEEP).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (!enabled(applicationContext)) return Result.success()
            return try {
                runNow(applicationContext)
                Result.success()
            } catch (e: Exception) {
                Logger.w("AutoBackup", "Автокопия не удалась", e)
                notifyFailed(applicationContext)
                Result.success()
            }
        }
    }

    private fun notifyFailed(context: Context) {
        val open = PendingIntent.getActivity(
            context, Notifications.AUTO_BACKUP_ID,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_BACKUP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_SYSTEM)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle("Автокопия не сохранилась")
            .setContentText("Папка недоступна. Откройте «Резервная копия» и выберите папку заново.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        Notifications.notifySafely(context, Notifications.AUTO_BACKUP_ID, n)
    }
}
