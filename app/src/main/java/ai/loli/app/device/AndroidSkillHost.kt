package ai.loli.app.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import ai.loli.app.media.RadioService
import ai.loli.app.media.RingService
import ai.loli.app.notify.LoliNotificationListener
import ai.loli.app.reminders.GeoReminders
import ai.loli.app.reminders.LoliTimers
import ai.loli.core.nlp.TextAnalysis
import ai.loli.core.skills.ActiveTimer
import ai.loli.core.skills.CalendarItem
import ai.loli.core.skills.ContactInfo
import ai.loli.core.skills.GeoPoint
import ai.loli.core.skills.IncomingMessage
import ai.loli.core.skills.NeedsPermission
import ai.loli.core.skills.Permission
import ai.loli.core.skills.PlaceReminder
import ai.loli.core.skills.RadioStation
import ai.loli.core.skills.SavedPlace
import ai.loli.core.skills.SkillHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Возможности телефона для навыков Лоли. Обычные разрешения (контакты, календарь, геолокация)
 * спрашиваются прямо по ходу команды; особые доступы (уведомления, спецвозможности) — открываем нужный экран настроек.
 */
class AndroidSkillHost(
    private val context: Context,
    private val permissions: PermissionBroker,
    private val launcher: BackgroundLauncher,
    val timers: LoliTimers,
    val geo: GeoReminders,
    private val onUserName: (String?) -> Unit,
    private val onCity: (String?) -> Unit,
) : SkillHost {
    private val locator = Locator(context)

    override suspend fun location(): GeoPoint? {
        if (!locator.hasCoarse() && !permissions.ensure(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        return locator.current()?.let { GeoPoint(it.latitude, it.longitude) }
    }

    override suspend fun messages(): List<IncomingMessage> {
        if (!LoliNotificationListener.enabled(context)) {
            launcher.launch(LoliNotificationListener.settingsIntent(context))
            throw NeedsPermission(Permission.NOTIFICATIONS_ACCESS, "нет доступа к уведомлениям")
        }
        return LoliNotificationListener.messages()
    }

    override suspend fun reply(message: IncomingMessage, text: String): Boolean =
        withContext(Dispatchers.Main) { LoliNotificationListener.reply(context, message.key, text) }

    override suspend fun screenText(): String? {
        val service = LoliAccessibilityService.current
        if (service == null) {
            launcher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            throw NeedsPermission(Permission.ACCESSIBILITY, "спецвозможности выключены")
        }
        return withContext(Dispatchers.Main) { service.screenText() }
    }

    override suspend fun contact(name: String): ContactInfo? {
        if (!permissions.ensure(Manifest.permission.READ_CONTACTS)) throw NeedsPermission(Permission.CONTACTS, "нет доступа к контактам")
        return withContext(Dispatchers.IO) {
            val stem = TextAnalysis.stems(name).firstOrNull() ?: name.lowercase()
            val key = if (stem.length > 3) stem.take(stem.length.coerceAtMost(5)) else stem
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(key))
            var found: String? = null
            val phones = ArrayList<String>()
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    if (found == null) found = n
                    if (n == found) c.getString(1)?.let { phones += it }
                }
            }
            found?.let { ContactInfo(it, phones.distinctBy { p -> p.filter(Char::isDigit).takeLast(10) }) }
        }
    }

    override suspend fun calendar(from: Instant, to: Instant): List<CalendarItem> {
        if (!permissions.ensure(Manifest.permission.READ_CALENDAR)) throw NeedsPermission(Permission.CALENDAR, "нет доступа к календарю")
        return withContext(Dispatchers.IO) {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                android.content.ContentUris.appendId(it, from.toEpochMilli())
                android.content.ContentUris.appendId(it, to.toEpochMilli())
            }.build()
            val out = ArrayList<CalendarItem>()
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 50) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "Без названия"
                    val allDay = c.getInt(3) == 1
                    // Событие «на весь день» хранится в UTC-полночь — переносим на местную дату.
                    val begin = if (allDay) {
                        val utc = Instant.ofEpochMilli(c.getLong(1)).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        utc.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
                    } else Instant.ofEpochMilli(c.getLong(1))
                    out += CalendarItem(title, begin, c.getLong(2).takeIf { it > 0 }?.let(Instant::ofEpochMilli), allDay, c.getString(4))
                }
            }
            out
        }
    }

    override suspend fun playRadio(station: RadioStation): Boolean = RadioService.play(context, station)
    override fun stopRadio(): Boolean = RadioService.stop(context)

    override suspend fun ringPhone(): Boolean = RingService.start(context, "Я здесь!", "Лоли ищет телефон. Коснитесь, чтобы остановить.", loud = true)

    override fun timers(): List<ActiveTimer> = timers.all()
    override fun cancelTimers(label: String?): Int = timers.cancel(label)

    override suspend fun savePlace(name: String): SavedPlace? {
        if (!locator.hasFine() && !permissions.ensure(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            throw NeedsPermission(Permission.LOCATION, "нет доступа к геолокации")
        }
        val loc = locator.current(maxAgeMs = 2 * 60_000L, precise = true) ?: return null
        return SavedPlace(name, loc.latitude, loc.longitude).also { geo.savePlace(it) }
    }

    override fun places(): List<SavedPlace> = geo.places()

    override suspend fun addPlaceReminder(text: String, place: String, onLeave: Boolean): PlaceReminder? {
        if (!locator.hasFine() && !permissions.ensure(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            throw NeedsPermission(Permission.LOCATION, "нет доступа к геолокации")
        }
        val r = geo.add(text, place, onLeave)
        // Чтобы напоминание сработало, когда Лоли закрыта, нужна геолокация «Всегда» (Android 10+).
        if (!locator.hasBackground()) permissions.ensure(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return r
    }

    override fun placeReminders(): List<PlaceReminder> = geo.reminders()
    override fun cancelPlaceReminders(place: String?): Int = geo.cancel(place)

    override fun setUserName(name: String?) = onUserName(name)
    override fun setCity(city: String?) = onCity(city)
}
