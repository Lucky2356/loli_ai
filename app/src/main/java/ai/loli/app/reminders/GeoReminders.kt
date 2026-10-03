package ai.loli.app.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import ai.loli.app.R
import ai.loli.app.ui.MainActivity
import ai.loli.core.skills.PlaceReminder
import ai.loli.core.skills.SavedPlace
import ai.loli.core.util.Ids
import ai.loli.core.util.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Места («дом», «работа») и напоминания «когда буду дома…». Хранятся только на телефоне.
 * Срабатывание — системные «зоны приближения» LocationManager (без Google-сервисов), радиус 150 м.
 */
class GeoReminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("loli_places", Context.MODE_PRIVATE)

    @Synchronized
    fun places(): List<SavedPlace> = read(KEY_PLACES).mapNotNull { o ->
        SavedPlace(o.optString("name").ifBlank { return@mapNotNull null }, o.optDouble("lat"), o.optDouble("lon"))
    }

    @Synchronized
    fun savePlace(p: SavedPlace) {
        val list = places().filter { it.name != p.name } + p
        write(KEY_PLACES, list.map { JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon) })
        // Напоминания для этого места переносим на новые координаты.
        reminders().filter { it.place == p.name }.forEach { register(it) }
    }

    /** Забыть место вместе с его напоминаниями. */
    fun removePlace(name: String) {
        cancel(name)
        synchronized(this) { write(KEY_PLACES, places().filter { it.name != name }.map { JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon) }) }
    }

    @Synchronized
    fun reminders(): List<PlaceReminder> = read(KEY_REMINDERS).mapNotNull { o ->
        PlaceReminder(o.optString("id"), o.optString("text"), o.optString("place"), o.optBoolean("leave"))
    }

    @Synchronized
    private fun saveReminders(list: List<PlaceReminder>) =
        write(KEY_REMINDERS, list.map { JSONObject().put("id", it.id).put("text", it.text).put("place", it.place).put("leave", it.onLeave) })

    fun add(text: String, place: String, onLeave: Boolean): PlaceReminder {
        val r = PlaceReminder(Ids.newId(), text, place, onLeave)
        saveReminders(reminders() + r)
        register(r)
        return r
    }

    fun remove(id: String) {
        reminders().firstOrNull { it.id == id }?.let { unregister(it) }
        saveReminders(reminders().filter { it.id != id })
    }

    fun cancel(place: String?): Int {
        val gone = reminders().filter { place == null || it.place == place }
        gone.forEach { unregister(it) }
        saveReminders(reminders() - gone.toSet())
        return gone.size
    }

    /**
     * После перезагрузки ([force]) зоны приближения сброшены — регистрируем все заново. При обычном запуске
     * только новые и те, у которых место сдвинулось: повторная регистрация дала бы ложное «вы пришли».
     */
    fun registerAll(force: Boolean = false) = reminders().forEach { r ->
        val p = places().firstOrNull { it.name == r.place } ?: return@forEach
        if (force || prefs.getString(KEY_REG + r.id, null) != "${p.lat},${p.lon}") register(r)
    }

    private fun pending(r: PlaceReminder, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, 0, Intent(context, GeoReceiver::class.java).setAction(ACTION).setData(Uri.parse("loli-geo://${r.id}")).putExtra(EXTRA_ID, r.id),
        // Система дописывает в интент «вошли/вышли» — поэтому изменяемый.
        flags or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    private fun register(r: PlaceReminder) {
        val p = places().firstOrNull { it.name == r.place } ?: return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val pi = pending(r, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        // Повторная регистрация (перезагрузка, место перезаписано) — сначала снимаем старую зону.
        runCatching { lm.removeProximityAlert(pi) }
        // Система сразу сообщает «вошли», если мы уже в зоне: такое первое «вошли» пропускаем.
        val inside = runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000L }
                ?.let { loc -> FloatArray(1).also { d -> Location.distanceBetween(loc.latitude, loc.longitude, p.lat, p.lon, d) }[0] < RADIUS }
        }.getOrNull() == true
        prefs.edit().putBoolean(KEY_SKIP + r.id, inside && !r.onLeave).apply()
        runCatching { lm.addProximityAlert(p.lat, p.lon, RADIUS, -1, pi) }
            .onSuccess { prefs.edit().putString(KEY_REG + r.id, "${p.lat},${p.lon}").apply() }
            .onFailure { Logger.w(TAG, "Не удалось следить за местом", it) }
    }

    /** Первое «вошли» сразу после регистрации — мы и так были здесь. */
    fun consumeSkip(id: String, entering: Boolean): Boolean {
        val skip = prefs.getBoolean(KEY_SKIP + id, false)
        if (skip) prefs.edit().remove(KEY_SKIP + id).apply()
        return skip && entering
    }

    @SuppressLint("MissingPermission")
    private fun unregister(r: PlaceReminder) {
        prefs.edit().remove(KEY_SKIP + r.id).remove(KEY_REG + r.id).apply()
        val pi = pending(r, PendingIntent.FLAG_NO_CREATE) ?: return
        runCatching { context.getSystemService(LocationManager::class.java)?.removeProximityAlert(pi) }
        pi.cancel()
    }

    /** Для резервной копии: места и напоминания по месту как JSON-строки. */
    @Synchronized
    fun exportRaw(): Map<String, String> = mapOf(
        "geo_places" to JSONArray().also { a -> read(KEY_PLACES).forEach { a.put(it) } }.toString(),
        "geo_reminders" to JSONArray().also { a -> read(KEY_REMINDERS).forEach { a.put(it) } }.toString(),
    )

    /** Из копии берём только то, чего на этом телефоне ещё нет (места по названию, напоминания по id). */
    @Synchronized
    fun importRaw(values: Map<String, String>) {
        fun parse(raw: String?): List<JSONObject> {
            val arr = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }
        val havePlaces = read(KEY_PLACES).map { it.optString("name") }.toSet()
        write(KEY_PLACES, read(KEY_PLACES) + parse(values["geo_places"]).filter { it.optString("name").isNotBlank() && it.optString("name") !in havePlaces })
        val haveIds = read(KEY_REMINDERS).map { it.optString("id") }.toSet()
        write(KEY_REMINDERS, read(KEY_REMINDERS) + parse(values["geo_reminders"]).filter { it.optString("id").isNotBlank() && it.optString("id") !in haveIds })
    }

    /** Места (дом, работа) — это адреса человека, поэтому лежат зашифрованными ключом Android Keystore, а не в открытых настройках. */
    private val secrets by lazy { ai.loli.app.security.KeystoreSecretStore(context.applicationContext) }

    /** До 2.3 списки лежали в обычных настройках открытым текстом — переносим в зашифрованное хранилище и стираем. */
    private fun migrate(key: String) {
        val old = prefs.getString(key, null) ?: return
        if (secrets.get(SECRET_PREFIX + key).isNullOrEmpty()) secrets.put(SECRET_PREFIX + key, old)
        prefs.edit().remove(key).apply()
    }

    private fun read(key: String): List<JSONObject> {
        migrate(key)
        val arr = runCatching { JSONArray(secrets.get(SECRET_PREFIX + key) ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun write(key: String, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        secrets.put(SECRET_PREFIX + key, arr.toString())
    }

    companion object {
        private const val TAG = "Geo"
        private const val SECRET_PREFIX = "geo_"
        private const val KEY_PLACES = "places"
        private const val KEY_REMINDERS = "reminders"
        private const val KEY_SKIP = "skip_"
        private const val KEY_REG = "geo_reg_"
        private const val RADIUS = 150f
        const val ACTION = "ai.loli.GEO"
        const val EXTRA_ID = "geo_id"
    }
}

class GeoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(GeoReminders.EXTRA_ID) ?: return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
        val geo = GeoReminders(context)
        val r = geo.reminders().firstOrNull { it.id == id } ?: return
        if (geo.consumeSkip(id, entering)) return
        if (r.onLeave == entering) return
        val open = PendingIntent.getActivity(
            context, Notifications.GEO_BASE_ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle(r.text)
            .setContentText(if (r.onLeave) "Вы уходите — не забудьте!" else "Вы на месте — напоминание Лоли")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        Notifications.notifySafely(context, Notifications.GEO_BASE_ID + (id.hashCode() and 0x3f), n)
        geo.remove(id)
    }
}
