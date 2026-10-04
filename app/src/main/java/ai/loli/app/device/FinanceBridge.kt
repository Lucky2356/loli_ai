package ai.loli.app.device

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import ai.loli.core.finance.FinanceItem
import ai.loli.core.finance.FinanceSink
import ai.loli.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Связь с «Финансовым помощником» (github.com/Lucky2356/financeapps) на этом же телефоне.
 *
 * Без сети и без сервера: прямой вызов его ContentProvider.call. Туда — траты Лоли
 * (новая, правка, отмена — по номеру траты), оттуда — короткая сводка для ответов
 * «сколько можно тратить сегодня?».
 *
 * С кем говорим — проверяется: адрес должен принадлежать приложению помощника,
 * подписанному его ключом. Чужое приложение, занявшее тот же адрес, не получит
 * ни одной траты и не подсунет свою сводку.
 *
 * Не дозвонились (помощник обновляется, система его выгрузила) — трата ждёт в очереди
 * и уйдёт со следующей. Отказ помощника («связь выключена») — не ошибка: трата не уходит.
 */
class FinanceBridge(private val context: Context, private val scope: CoroutineScope) : FinanceSink {
    data class Status(
        /** Помощник стоит на телефоне и подписан своим ключом. */
        val available: Boolean,
        /** Связь включена в самом помощнике. */
        val enabled: Boolean = false,
        /** Траты сразу записываются в учёт, а не в «Подсказки». */
        val auto: Boolean = false,
        /** Помощник разрешил отдавать сводку. */
        val share: Boolean = false,
    )

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    /** Очередь: чтение и запись. */
    private val lock = Mutex()
    /** Одна отправка за раз — иначе две могли бы послать одно и то же. */
    private val flushing = Mutex()

    /** Переключатель в настройках Лоли. По умолчанию включён: решает человек в самом помощнике. */
    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) prefs.edit().remove(KEY_OUTBOX).apply()
    }

    override fun active(): Boolean = enabled() && available()

    override fun known(id: String): Boolean = id in knownIds() || outbox().any { it.optString("id") == id }

    /**
     * Трата встаёт в очередь сразу, а уходит фоном: помощника, возможно, придётся запустить,
     * и Лоли не должна ждать этого, прежде чем ответить «Записала».
     */
    override suspend fun send(item: FinanceItem) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val queue = outbox().filter { it.optString("id") != item.id }.toMutableList()
                queue += JSONObject(item.toJson())
                saveOutbox(queue.takeLast(OUTBOX_LIMIT))
            }
        }
        scope.launch { flush() }
    }

    /**
     * Дослать очередь (и при запуске Лоли — то, что не ушло в прошлый раз).
     * Очередь замкнута только на время чтения и записи, не на время звонка помощнику:
     * новая трата не ждёт, пока он запускается.
     */
    suspend fun flush() = withContext(Dispatchers.IO) {
        if (!active()) return@withContext
        flushing.withLock {
            while (true) {
                val item = lock.withLock { outbox().firstOrNull() } ?: break
                val answer = call("record", Bundle().apply { putString("json", item.toString()) }) ?: break
                val id = item.optString("id")
                lock.withLock {
                    // Пока звонили, трату могли поправить: тогда в очереди уже новая версия, и она уйдёт следующей.
                    val queue = outbox().toMutableList()
                    val index = queue.indexOfFirst { it.toString() == item.toString() }
                    if (index >= 0) queue.removeAt(index)
                    saveOutbox(queue)
                    when (val status = answer.getString("status")) {
                        "queued" -> if (item.optString("op") == "delete") forget(id) else remember(id)
                        // «Связь выключена», «не понял трату», «не тебе» — повтор не поможет.
                        else -> Logger.w(TAG, "Помощник не принял трату: $status")
                    }
                }
            }
        }
    }

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        if (!available()) return@withContext Status(false)
        val answer = call("status", null) ?: return@withContext Status(true)
        Status(
            available = true,
            enabled = answer.getBoolean("enabled", false),
            auto = answer.getBoolean("auto", false),
            share = answer.getBoolean("share", false),
        )
    }

    /** Сводка помощника (JSON) или null: связь выключена, сводки нет, помощника нет. */
    suspend fun summary(): String? = withContext(Dispatchers.IO) {
        if (!active()) return@withContext null
        val answer = call("summary", null) ?: return@withContext null
        if (answer.getString("status") == "ok") answer.getString("json") else null
    }

    /** null — не дозвонились (тогда трата остаётся в очереди). */
    private fun call(method: String, extras: Bundle?): Bundle? = try {
        context.contentResolver.call(AUTHORITY, method, null, extras)
    } catch (e: Exception) {
        Logger.w(TAG, "Финансовый помощник не ответил на $method", e)
        null
    }

    /** Адрес связи занят именно помощником, подписанным его ключом. */
    private fun available(): Boolean = try {
        val pm = context.packageManager
        val provider = pm.resolveContentProvider(AUTHORITY.authority!!, 0)
        provider != null && provider.packageName == PACKAGE && signatures(pm).any { it in TRUSTED }
    } catch (_: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    private fun signatures(pm: PackageManager): List<String> {
        val raw = if (Build.VERSION.SDK_INT >= 28) {
            // Только текущая подпись APK: старые ключи из истории ротации не в счёт.
            pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES).signatures
        }
        return (raw ?: emptyArray()).map { sha256(it.toByteArray()) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    private fun outbox(): List<JSONObject> = try {
        val array = JSONArray(prefs.getString(KEY_OUTBOX, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveOutbox(items: List<JSONObject>) {
        prefs.edit().putString(KEY_OUTBOX, JSONArray(items).toString()).apply()
    }

    private fun knownIds(): List<String> = prefs.getString(KEY_KNOWN, "").orEmpty().split(',').filter { it.isNotEmpty() }

    private fun remember(id: String) {
        val ids = knownIds().filter { it != id } + id
        prefs.edit().putString(KEY_KNOWN, ids.takeLast(KNOWN_LIMIT).joinToString(",")).apply()
    }

    private fun forget(id: String) {
        prefs.edit().putString(KEY_KNOWN, knownIds().filter { it != id }.joinToString(",")).apply()
    }

    companion object {
        private const val TAG = "FinanceBridge"
        const val PACKAGE = "ru.lucky2356.financeapps"
        private val AUTHORITY: Uri = Uri.parse("content://ru.lucky2356.financeapps.loli")

        /**
         * Отпечаток (SHA-256) сертификата, которым подписан выпущенный помощник.
         * Сменит он ключ — сменится и этот; до тех пор связь честно не работает.
         */
        private val TRUSTED = setOf("08521A60A60424A1CD8A0AD31CAB8685951DEA9438DFDBDD34714046E281230E")

        private const val PREFS = "finance_link"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_OUTBOX = "outbox"
        private const val KEY_KNOWN = "known"
        private const val OUTBOX_LIMIT = 200
        private const val KNOWN_LIMIT = 2000
    }
}
