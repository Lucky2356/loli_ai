package ai.loli.app.device

import android.Manifest
import android.app.PendingIntent
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.app.NotificationManager
import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.loli.app.R
import ai.loli.app.reminders.Notifications
import ai.loli.core.assistant.DeviceCommand
import ai.loli.core.assistant.DeviceController
import ai.loli.core.assistant.DevicePhrases
import ai.loli.core.assistant.DeviceResult
import ai.loli.core.assistant.GlobalAction
import ai.loli.core.assistant.MediaAction
import ai.loli.core.assistant.RuFormat
import ai.loli.core.assistant.SettingsSection
import ai.loli.core.assistant.VolumeChange
import ai.loli.core.nlp.TextAnalysis
import ai.loli.core.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime

/**
 * Выполнение команд телефону без интернета: будильники и таймеры в приложении «Часы», запуск приложений,
 * звонки, фонарик, заряд, музыка, громкость, поиск, маршрут, системные настройки.
 *
 * Android запрещает открывать окна из фона. Если ассистент вызван голосом при свёрнутом приложении,
 * таймер и будильник ставятся напоминаниями Лоли, а остальное приходит уведомлением «нажмите, чтобы открыть».
 */
class AndroidDeviceController(
    private val context: Context,
    private val launcher: BackgroundLauncher,
    private val access: AppAccess,
    /** Спросить нужное разрешение прямо во время команды. */
    private val permissions: PermissionBroker,
    /** Запасной таймер/будильник: напоминание Лоли на указанное время. */
    private val fallbackReminder: suspend (text: String, at: Instant) -> Unit,
) : DeviceController {

    override suspend fun perform(command: DeviceCommand): DeviceResult = withContext(Dispatchers.Main) {
        try {
            run(command)
        } catch (e: SecurityException) {
            Logger.w(TAG, "Нет разрешения для ${command::class.simpleName}", e)
            DeviceResult("Телефон не дал разрешения на это действие.", ok = false)
        } catch (e: Exception) {
            Logger.w(TAG, "Команда ${command::class.simpleName} не выполнена", e)
            DeviceResult("Не получилось выполнить команду на этом телефоне.", ok = false)
        }
    }

    private suspend fun run(c: DeviceCommand): DeviceResult {
        return when (c) {
            is DeviceCommand.Timer -> {
                val what = DevicePhrases.describeDuration(c.seconds)
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, c.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, c.label.ifBlank { "Лоли" })
                if (launcher.launch(intent)) DeviceResult("Таймер на $what запущен.")
                else {
                    fallbackReminder("Таймер: $what прошло", Instant.now().plusSeconds(c.seconds.toLong()))
                    DeviceResult("Засекла $what — напомню, когда время выйдет.")
                }
            }
            is DeviceCommand.Alarm -> {
                val time = RuFormat.time(c.time)
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, c.time.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, c.time.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, c.label.ifBlank { "Лоли" })
                if (c.days.isNotEmpty()) {
                    intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(c.days.map { (it.value % 7) + 1 }))
                }
                if (launcher.launch(intent)) DeviceResult("Будильник на $time поставлен${if (c.days.isNotEmpty()) ", с повтором" else ""}.")
                else {
                    fallbackReminder("Будильник $time", nextOccurrence(c.time))
                    DeviceResult("Поставила напоминание-будильник на $time.")
                }
            }
            DeviceCommand.ShowAlarms -> open(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Открываю будильники.", "Будильники")
            DeviceCommand.Stopwatch -> open(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Открываю часы — секундомер там.", "Часы")
            is DeviceCommand.OpenApp -> {
                val app = findApp(c.name) ?: return DeviceResult("Не нашла приложение «${c.name}».", ok = false)
                if (!access.isAllowed(app.first, app.second)) {
                    return DeviceResult("Открывать «${app.second}» мне не разрешено. Разрешить можно в Настройки Лоли → Доступ.", ok = false)
                }
                val launch = context.packageManager.getLaunchIntentForPackage(app.first)
                    ?: return DeviceResult("Приложение «${app.second}» нельзя открыть.", ok = false)
                open(launch, "Открываю ${app.second}.", app.second)
            }
            is DeviceCommand.Call -> {
                askContactsIfNeeded(c.who)
                val (number, name) = resolveNumber(c.who) ?: return contactsProblem(c.who)
                open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))), "Набираю ${name ?: number} — нажмите вызов.", "Звонок")
            }
            is DeviceCommand.Message -> {
                askContactsIfNeeded(c.who)
                val (number, name) = resolveNumber(c.who) ?: return contactsProblem(c.who)
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", c.text)
                open(intent, "Сообщение для ${name ?: number} готово — осталось отправить.", "Сообщение")
            }
            is DeviceCommand.Flashlight -> {
                val cm = context.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return DeviceResult("На этом телефоне нет вспышки.", ok = false)
                cm.setTorchMode(id, c.on)
                DeviceResult(if (c.on) "Фонарик включён." else "Фонарик выключен.")
            }
            DeviceCommand.Battery -> {
                val bm = context.getSystemService(BatteryManager::class.java)
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                DeviceResult("Заряд $level%${if (charging) ", телефон заряжается" else ""}.")
            }
            is DeviceCommand.Media -> {
                val code = when (c.action) {
                    MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
                    MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                }
                val am = context.getSystemService(AudioManager::class.java)
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                DeviceResult(
                    when (c.action) {
                        MediaAction.PLAY -> "Включаю."
                        MediaAction.PAUSE -> "Пауза."
                        MediaAction.NEXT -> "Следующий трек."
                        MediaAction.PREVIOUS -> "Предыдущий трек."
                    },
                )
            }
            is DeviceCommand.Volume -> {
                val am = context.getSystemService(AudioManager::class.java)
                val stream = AudioManager.STREAM_MUSIC
                val max = am.getStreamMaxVolume(stream)
                when (c.change) {
                    VolumeChange.UP -> repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, if (it == 1) AudioManager.FLAG_SHOW_UI else 0) }
                    VolumeChange.DOWN -> repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, if (it == 1) AudioManager.FLAG_SHOW_UI else 0) }
                    VolumeChange.MUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    VolumeChange.UNMUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    VolumeChange.MAX -> am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
                    VolumeChange.SET -> am.setStreamVolume(stream, (max * (c.percent ?: 50) / 100.0).toInt().coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
                }
                val percent = am.getStreamVolume(stream) * 100 / max.coerceAtLeast(1)
                DeviceResult(if (c.change == VolumeChange.MUTE) "Звук выключен." else "Громкость $percent%.")
            }
            is DeviceCommand.WebSearch -> {
                val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, c.query)
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(c.query)))
                open(if (resolves(search)) search else web, "Ищу «${c.query}».", "Поиск: ${c.query}")
            }
            is DeviceCommand.Navigate -> {
                val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(c.destination)))
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/maps/?text=" + Uri.encode(c.destination)))
                open(if (resolves(geo)) geo else web, "Строю маршрут: ${c.destination}.", "Маршрут")
            }
            is DeviceCommand.OpenSettings -> {
                val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                val action = when (c.section) {
                    // Android 10+ показывает компактную панель поверх экрана — включить Wi-Fi можно в одно касание.
                    SettingsSection.WIFI -> if (q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                    SettingsSection.MOBILE_DATA -> if (q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
                    SettingsSection.NFC -> if (q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
                    SettingsSection.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
                    SettingsSection.SOUND -> if (q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
                    SettingsSection.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
                    SettingsSection.BATTERY -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    SettingsSection.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    SettingsSection.APPS -> Settings.ACTION_APPLICATION_SETTINGS
                    SettingsSection.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                    SettingsSection.HOTSPOT -> Settings.ACTION_WIRELESS_SETTINGS
                    SettingsSection.NOTIFICATIONS -> "android.settings.NOTIFICATION_SETTINGS"
                    SettingsSection.SECURITY -> Settings.ACTION_SECURITY_SETTINGS
                    SettingsSection.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    SettingsSection.DATE_TIME -> Settings.ACTION_DATE_SETTINGS
                    SettingsSection.STORAGE -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    SettingsSection.MAIN -> Settings.ACTION_SETTINGS
                }
                // Android 10+ не даёт приложениям самим включать Wi-Fi, Bluetooth и режим полёта — открываем нужный экран.
                val intent = Intent(action)
                open(if (resolves(intent)) intent else Intent(Settings.ACTION_SETTINGS), "Открываю настройки.", "Настройки")
            }
            is DeviceCommand.Camera -> {
                val intent = Intent(if (c.video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                if (c.selfie) {
                    // Разные камеры понимают разные ключи — передаём все известные.
                    intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
                        .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                        .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                }
                open(intent, if (c.video) "Открываю видеокамеру." else if (c.selfie) "Открываю фронтальную камеру." else "Открываю камеру.", "Камера")
            }
            is DeviceCommand.OpenUrl -> open(Intent(Intent.ACTION_VIEW, Uri.parse(c.url)), "Открываю ${Uri.parse(c.url).host ?: c.url}.", "Сайт")
            is DeviceCommand.Play -> {
                if (c.youtube) {
                    val app = Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra("query", c.query)
                    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(c.query)))
                    open(if (resolves(app)) app else web, "Ищу на YouTube: ${c.query}.", "YouTube")
                } else {
                    val music = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        .putExtra(SearchManager.QUERY, c.query)
                        .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(c.query)))
                    open(if (resolves(music)) music else web, "Включаю ${c.query}.", "Музыка")
                }
            }
            is DeviceCommand.CalendarEvent -> {
                // Доступ к календарю спрашиваем сразу — тогда событие добавится без лишних экранов.
                permissions.ensure(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                addEvent(c)
            }
            is DeviceCommand.AddContact -> {
                val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.NAME, c.name)
                c.phone?.let { intent.putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                open(intent, "Проверьте и сохраните контакт «${c.name}».", "Новый контакт")
            }
            is DeviceCommand.Share -> {
                val pkg = SHARE_APPS.entries.firstOrNull { c.app.startsWith(it.key) }?.value
                if (pkg != null && !access.isAllowed(pkg, c.app)) {
                    return DeviceResult("Отправлять в «${c.app}» мне не разрешено. Разрешить можно в Настройки Лоли → Доступ.", ok = false)
                }
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.text)
                if (pkg != null) send.setPackage(pkg)
                val intent = if (pkg != null && resolves(send)) send else Intent.createChooser(send.setPackage(null), "Отправить")
                open(intent, "Выберите, кому отправить.", "Отправить")
            }
            is DeviceCommand.DoNotDisturb -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "Разрешите Лоли управлять режимом «Не беспокоить» — и повторите команду.", "Не беспокоить")
                } else {
                    nm.setInterruptionFilter(if (c.on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                    DeviceResult(if (c.on) "Режим «Не беспокоить» включён." else "Режим «Не беспокоить» выключен.")
                }
            }
            is DeviceCommand.Brightness -> {
                if (!Settings.System.canWrite(context)) {
                    open(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")),
                        "Разрешите Лоли менять системные настройки — и повторите команду.", "Яркость",
                    )
                } else {
                    val cr = context.contentResolver
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
                    val target = (c.percent ?: (current + c.delta)).coerceIn(1, 100)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target * 255 / 100)
                    DeviceResult("Яркость $target%.")
                }
            }
            is DeviceCommand.Global -> {
                when (LoliAccessibilityService.perform(c.action)) {
                    true -> DeviceResult(
                        when (c.action) {
                            GlobalAction.SCREENSHOT -> "Скриншот сделан."
                            GlobalAction.LOCK -> "Блокирую экран."
                            else -> "Готово."
                        },
                    )
                    false -> DeviceResult("На этой версии Android так нельзя.", ok = false)
                    null -> if (c.action == GlobalAction.HOME) {
                        open(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "Готово.", "Домой")
                    } else {
                        DeviceResult("Включите «Лоли» в спецвозможностях (Настройки Лоли → Разрешения) — тогда смогу нажимать системные кнопки.", ok = false)
                    }
                }
            }
        }
    }

    /** Событие в календарь: сразу (если разрешён доступ к календарю) или через экран календаря. */
    private fun addEvent(c: DeviceCommand.CalendarEvent): DeviceResult {
        val start = c.start ?: java.time.ZonedDateTime.now().plusHours(1).withMinute(0).toInstant()
        val begin = start.toEpochMilli()
        val end = begin + if (c.allDay) 24 * 3600_000L else 3600_000L
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val calendarId = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1",
                null, "${CalendarContract.Calendars.IS_PRIMARY} DESC",
            )?.use { cur -> if (cur.moveToFirst()) cur.getLong(0) else null }
            if (calendarId != null) {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, c.title)
                    put(CalendarContract.Events.DTSTART, begin)
                    put(CalendarContract.Events.DTEND, end)
                    put(CalendarContract.Events.ALL_DAY, if (c.allDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, if (c.allDay) "UTC" else java.util.TimeZone.getDefault().id)
                }
                context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                val whenText = ai.loli.core.assistant.RuFormat.dateTime(start, java.time.ZoneId.systemDefault(), Instant.now())
                return DeviceResult("Добавила в календарь: «${c.title}», $whenText.")
            }
        }
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, c.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, c.allDay)
        return open(intent, "Проверьте и сохраните событие «${c.title}».", "Календарь")
    }

    // ------------------------------------------------------------------ Вспомогательное

    private fun resolves(intent: Intent): Boolean = intent.resolveActivity(context.packageManager) != null

    /**
     * Открывает экран — и при свёрнутом приложении (с разрешением «Поверх других приложений»).
     * Без разрешения из фона — уведомление, которое откроет экран по нажатию.
     */
    private fun open(intent: Intent, done: String, title: String): DeviceResult {
        if (launcher.canLaunch()) {
            return if (launcher.launch(intent)) DeviceResult(done) else DeviceResult("На телефоне нет приложения для этого.", ok = false)
        }
        if (!Notifications.canPost(context)) return DeviceResult("Разрешите Лоли «Поверх других приложений» (Настройки → Разрешения), чтобы открывать приложения из фона.", ok = false)
        val pi = PendingIntent.getActivity(
            context, title.hashCode(), intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_SYSTEM)
            .setSmallIcon(R.drawable.ic_stat_loli)
            .setContentTitle(title)
            .setContentText("Нажмите, чтобы открыть")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        Notifications.notifySafely(context, ACTION_NOTIFICATION_ID, n)
        return DeviceResult("Нажмите на уведомление — открою. Чтобы открывала сразу, разрешите «Поверх других приложений» в настройках Лоли.")
    }

    private fun nextOccurrence(t: LocalTime): Instant {
        val now = java.time.ZonedDateTime.now()
        var at = now.with(t).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        return at.toInstant()
    }

    /** Приложение по сказанному названию: «телеграм», «камеру», «ютуб», «вк». Возвращает (пакет, название). */
    private fun findApp(spoken: String): Pair<String, String>? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
        val query = norm(spoken)
        val aliases = (ALIASES[query] ?: ALIASES.entries.firstOrNull { query.startsWith(it.key) }?.value).orEmpty() + translit(query)
        val stem = TextAnalysis.stems(spoken).firstOrNull()?.let { norm(it) } ?: query
        fun score(pkg: String, label: String): Int {
            val l = norm(label)
            return when {
                l == query -> 100
                aliases.any { a -> l == a || pkg.contains(a.replace(" ", "")) } -> 90
                l.startsWith(query) || query.startsWith(l) -> 80
                stem.length >= 3 && (l.startsWith(stem) || l.split(" ").any { it.startsWith(stem) }) -> 70
                aliases.any { a -> l.contains(a) } -> 60
                l.contains(query) -> 50
                else -> 0
            }
        }
        return apps.map { it to score(it.first, it.second) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').trim()

    /** Номер из фразы или из контактов: «8 900 123-45-67», «маме», «Саше». Возвращает (номер, имя). */
    private fun resolveNumber(who: String): Pair<String, String?>? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3) return digits to null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val stem = TextAnalysis.stems(who).firstOrNull() ?: norm(who)
        val key = if (stem.length > 3) stem.take(stem.length.coerceAtMost(5)) else stem
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(key))
        context.contentResolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0) to c.getString(1)
        }
        return null
    }

    /** «Позвони маме» без доступа к контактам — спрашиваем разрешение прямо сейчас (для номера цифрами не нужно). */
    private suspend fun askContactsIfNeeded(who: String) {
        if (who.count { it.isDigit() } >= 3) return
        permissions.ensure(Manifest.permission.READ_CONTACTS)
    }

    private fun contactsProblem(who: String): DeviceResult =
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            DeviceResult("Чтобы звонить по имени, разрешите доступ к контактам: Настройки → Разрешения → Контакты.", ok = false)
        } else {
            DeviceResult("Не нашла «$who» в контактах.", ok = false)
        }

    companion object {
        private const val TAG = "Device"
        private const val YOUTUBE = "com.google.android.youtube"
        private val SHARE_APPS = mapOf(
            "телеграм" to "org.telegram.messenger", "telegram" to "org.telegram.messenger",
            "ватсап" to "com.whatsapp", "вотсап" to "com.whatsapp", "whatsapp" to "com.whatsapp",
            "вк" to "com.vkontakte.android", "вконтакте" to "com.vkontakte.android",
            "viber" to "com.viber.voip", "вайбер" to "com.viber.voip", "gmail" to "com.google.android.gm", "почту" to "com.google.android.gm",
        )
        private const val ACTION_NOTIFICATION_ID = 1003

        /** Как популярные приложения называют вслух. */
        private val ALIASES = mapOf(
            "телеграм" to listOf("telegram"), "телега" to listOf("telegram"), "ютуб" to listOf("youtube"),
            "вотсап" to listOf("whatsapp"), "ватсап" to listOf("whatsapp"), "вк" to listOf("vk", "вконтакте"),
            "вконтакте" to listOf("vk", "вконтакте"), "инстаграм" to listOf("instagram"), "тикток" to listOf("tiktok"),
            "хром" to listOf("chrome"), "гугл" to listOf("google"), "карты" to listOf("maps", "карты"),
            "яндекс" to listOf("yandex", "яндекс"), "навигатор" to listOf("navigator", "навигатор"), "такси" to listOf("taxi", "go"),
            "сбер" to listOf("sberbank", "сбер"), "тинькофф" to listOf("tinkoff", "т-банк"), "т-банк" to listOf("tinkoff", "т-банк"),
            "госуслуги" to listOf("gosuslugi", "госуслуги"), "почта" to listOf("gmail", "mail", "почта"), "джимейл" to listOf("gmail"),
            "камер" to listOf("camera", "камера"), "галере" to listOf("gallery", "photos", "галерея", "фото"), "фото" to listOf("photos", "фото"),
            "часы" to listOf("clock", "часы"), "калькулятор" to listOf("calculator", "калькулятор"), "календар" to listOf("calendar", "календарь"),
            "настройки" to listOf("settings", "настройки"), "плей маркет" to listOf("vending", "play store"), "маркет" to listOf("vending", "market"),
            "музык" to listOf("music", "музыка"), "спотифай" to listOf("spotify"), "озон" to listOf("ozon"), "вайлдберриз" to listOf("wildberries"),
            "дискорд" to listOf("discord"), "твиттер" to listOf("twitter", "x"), "макс" to listOf("max"), "контакты" to listOf("contacts", "контакты"),
            "телефон" to listOf("dialer", "phone", "телефон"), "сообщения" to listOf("messaging", "mms", "сообщения"),
            "вайбер" to listOf("viber"), "скайп" to listOf("skype"), "зум" to listOf("zoom"), "тимс" to listOf("teams"),
            "одноклассник" to listOf("odnoklassniki", "ok"), "пинтерест" to listOf("pinterest"), "реддит" to listOf("reddit"),
            "фейсбук" to listOf("facebook"), "снэпчат" to listOf("snapchat"), "лайк" to listOf("likee"), "дзен" to listOf("zen", "дзен"),
            "кинопоиск" to listOf("kinopoisk", "кинопоиск"), "окко" to listOf("okko"), "иви" to listOf("ivi"), "нетфликс" to listOf("netflix"),
            "вк музык" to listOf("vk music", "boom"), "яндекс музык" to listOf("yandex.music", "яндекс музыка"), "звук" to listOf("zvuk", "звук"),
            "ютуб музык" to listOf("youtube.music", "youtube music"), "подкаст" to listOf("podcast"),
            "яндекс карт" to listOf("yandexmaps", "яндекс карты"), "гугл карт" to listOf("apps.maps", "google maps"), "2гис" to listOf("dublgis", "2гис", "2gis"),
            "дубльгис" to listOf("dublgis", "2gis"), "яндекс го" to listOf("taxi", "yandex go"), "убер" to listOf("uber"), "ситимобил" to listOf("citymobil"),
            "самокат" to listOf("samokat", "самокат"), "вкусвилл" to listOf("vkusvill"), "лавка" to listOf("lavka", "лавка"), "купер" to listOf("kuper", "sbermarket"),
            "сбермаркет" to listOf("sbermarket", "kuper"), "дельивери" to listOf("delivery"), "деливери" to listOf("delivery"), "авито" to listOf("avito"),
            "алиэкспресс" to listOf("aliexpress"), "али" to listOf("aliexpress"), "яндекс маркет" to listOf("market", "маркет"), "мегамаркет" to listOf("megamarket"),
            "ламода" to listOf("lamoda"), "лента" to listOf("lenta"), "пятерочк" to listOf("pyaterochka", "5ka"), "магнит" to listOf("magnit"),
            "перекресток" to listOf("perekrestok"), "ашан" to listOf("auchan"), "спортмастер" to listOf("sportmaster"), "днс" to listOf("dns"),
            "эльдорадо" to listOf("eldorado"), "мвидео" to listOf("mvideo"), "м видео" to listOf("mvideo"),
            "альфа" to listOf("alfabank", "альфа"), "втб" to listOf("vtb"), "озон банк" to listOf("ozon bank", "финанс"), "мтс" to listOf("mts", "мой мтс"),
            "билайн" to listOf("beeline"), "мегафон" to listOf("megafon"), "теле2" to listOf("tele2"), "йота" to listOf("yota"),
            "почта россии" to listOf("russianpost", "почта россии"), "сдэк" to listOf("cdek"), "боксберри" to listOf("boxberry"),
            "заметки" to listOf("notes", "keep", "заметки"), "кип" to listOf("keep"), "диск" to listOf("drive", "disk", "диск"),
            "гугл диск" to listOf("apps.docs", "drive"), "яндекс диск" to listOf("yandex.disk", "диск"), "документы" to listOf("docs", "документы"),
            "таблицы" to listOf("sheets"), "переводчик" to listOf("translate", "переводчик"), "погода" to listOf("weather", "погода"),
            "браузер" to listOf("browser", "chrome", "браузер"), "файлы" to listOf("files", "файлы", "documentsui"), "проводник" to listOf("files", "filemanager"),
            "диктофон" to listOf("recorder", "soundrecorder", "диктофон"), "фонарик" to listOf("flashlight", "torch"), "компас" to listOf("compass"),
            "здоровье" to listOf("health", "fit", "здоровье"), "шаги" to listOf("fit", "health"), "будильник" to listOf("clock", "deskclock"),
            "плеер" to listOf("player", "music"), "радио" to listOf("radio", "fm"), "книги" to listOf("books", "litres", "книги"), "литрес" to listOf("litres"),
            "стим" to listOf("steam"), "твич" to listOf("twitch"), "чатгпт" to listOf("chatgpt", "openai"), "джипити" to listOf("chatgpt"),
            "гемини" to listOf("gemini"), "алиса" to listOf("yandex", "алиса"), "умный дом" to listOf("iot", "smarthome", "дом с алисой", "mi home", "home"),
        )

        private val TRANSLIT = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y",
            'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ы' to "y", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        )

        fun translit(s: String): String = s.map { TRANSLIT[it] ?: if (it == 'ь' || it == 'ъ') "" else it.toString() }.joinToString("")
    }
}
