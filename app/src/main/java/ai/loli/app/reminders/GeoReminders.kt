package ai.loli.app.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
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

    /** После перезагрузки зоны приближения сбрасываются — регистрируем заново. */
    fun registerAll() = reminders().forEach { register(it) }

    private fun pending(r: PlaceReminder, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, r.id.hashCode(), Intent(context, GeoReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, r.id),
        // Система дописывает в интент «вошли/вышли» — поэтому изменяемый.
        flags or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    private fun register(r: PlaceReminder) {
        val p = places().firstOrNull { it.name == r.place } ?: return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val pi = pending(r, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching { lm.addProximityAlert(p.lat, p.lon, RADIUS, -1, pi) }
            .onFailure { Logger.w(TAG, "Не удалось следить за местом ${r.place}", it) }
    }

    @SuppressLint("MissingPermission")
    private fun unregister(r: PlaceReminder) {
        val pi = pending(r, PendingIntent.FLAG_NO_CREATE) ?: return
        runCatching { context.getSystemService(LocationManager::class.java)?.removeProximityAlert(pi) }
        pi.cancel()
    }

    private fun read(key: String): List<JSONObject> {
        val arr = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun write(key: String, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "Geo"
        private const val KEY_PLACES = "places"
        private const val KEY_REMINDERS = "reminders"
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
