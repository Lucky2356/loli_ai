package ai.loli.core.skills

import java.time.Instant

/** Сообщение из мессенджера (из уведомления). */
data class IncomingMessage(
    val key: String,
    val app: String,
    val sender: String,
    val text: String,
    val time: Instant,
    val canReply: Boolean,
)

data class ContactInfo(val name: String, val phones: List<String>)

data class CalendarItem(val title: String, val start: Instant, val end: Instant?, val allDay: Boolean, val location: String? = null)

data class ActiveTimer(val id: String, val label: String, val endsAt: Instant)

data class SavedPlace(val name: String, val lat: Double, val lon: Double)

data class PlaceReminder(val id: String, val text: String, val place: String, val onLeave: Boolean)

/** Для функции нужно разрешение — ответ предложит его выдать. */
class NeedsPermission(val permission: Permission, message: String) : Exception(message)

enum class Permission { LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS_ACCESS, CONTACTS, CALENDAR, ACCESSIBILITY }

/**
 * Возможности телефона для новых навыков. Реализует Android-приложение; по умолчанию — «не умею».
 * Методы могут бросать [NeedsPermission], если пользователь ещё не выдал доступ.
 */
interface SkillHost {
    /** Где сейчас телефон (приблизительно). */
    suspend fun location(): GeoPoint? = null

    /** Последние сообщения из уведомлений, новые сначала. */
    suspend fun messages(): List<IncomingMessage> = throw NeedsPermission(Permission.NOTIFICATIONS_ACCESS, "нет доступа к уведомлениям")
    /** Ответить в тот же чат через кнопку «Ответить» уведомления. */
    suspend fun reply(message: IncomingMessage, text: String): Boolean = false

    /** Текст на экране (через спецвозможности). */
    suspend fun screenText(): String? = throw NeedsPermission(Permission.ACCESSIBILITY, "нет доступа к экрану")

    suspend fun contact(name: String): ContactInfo? = null
    suspend fun calendar(from: Instant, to: Instant): List<CalendarItem> = emptyList()

    suspend fun playRadio(station: RadioStation): Boolean = false
    fun stopRadio(): Boolean = false

    /** Громкий сигнал «я здесь». */
    suspend fun ringPhone(): Boolean = false

    fun timers(): List<ActiveTimer> = emptyList()
    fun cancelTimers(label: String?): Int = 0

    /** Запомнить текущее место под именем. */
    suspend fun savePlace(name: String): SavedPlace? = null
    fun places(): List<SavedPlace> = emptyList()
    suspend fun addPlaceReminder(text: String, place: String, onLeave: Boolean): PlaceReminder? = null
    fun placeReminders(): List<PlaceReminder> = emptyList()
    fun cancelPlaceReminders(place: String?): Int = 0

    fun setUserName(name: String?) {}
    fun setCity(city: String?) {}
}

object NoSkillHost : SkillHost
