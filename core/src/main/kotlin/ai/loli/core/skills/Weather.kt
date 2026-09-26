package ai.loli.core.skills

import ai.loli.core.assistant.RuFormat
import ai.loli.core.data.LoliJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Точка на карте. */
data class GeoPoint(val lat: Double, val lon: Double, val name: String? = null)

/** Нет связи или источник не ответил — ответ «нет интернета», а не падение. */
class InfoUnavailable(message: String) : Exception(message)

/** Общие для «живых» ответов HTTP-запросы: таймаут, User-Agent, понятная ошибка. */
internal suspend fun HttpClient.fetchText(url: String): String {
    val r: HttpResponse = try {
        get(url) { header("User-Agent", "LoliAssistant/1.9 (Android; +https://github.com/Lucky2356/loli_ai)") }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw InfoUnavailable("нет связи")
    }
    if (!r.status.isSuccess()) throw InfoUnavailable("источник ответил ${r.status.value}")
    return r.bodyAsText()
}

/** Простой кеш на время: одинаковый вопрос подряд не ходит в сеть. */
internal class TtlCache<K, V>(private val ttlMillis: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val map = HashMap<K, Pair<Long, V>>()
    @Synchronized fun get(key: K): V? = map[key]?.takeIf { now() - it.first < ttlMillis }?.second
    @Synchronized fun put(key: K, value: V) { map[key] = now() to value; if (map.size > 64) map.clear() }
}

internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonArray.numAt(i: Int): Double? = (getOrNull(i) as? JsonPrimitive)?.doubleOrNull

/** Вопрос о погоде. */
data class WeatherQuery(
    /** Город словами как сказано («в Казани»); null — где пользователь сейчас. */
    val place: String? = null,
    /** День; null — «сейчас». */
    val date: LocalDate? = null,
    val aspect: Aspect = Aspect.GENERAL,
) {
    enum class Aspect { GENERAL, RAIN, SNOW, UMBRELLA, WEEK, WIND, WARM }
}

/** Прогноз от Open-Meteo: бесплатно, без ключа, работает без Google-сервисов. */
class WeatherService(private val http: HttpClient) {
    data class Place(val name: String, val lat: Double, val lon: Double, val country: String? = null)
    data class Day(val date: LocalDate, val code: Int, val min: Double, val max: Double, val rainChance: Int?, val wind: Double?)
    data class Forecast(val temp: Double?, val feels: Double?, val code: Int?, val wind: Double?, val days: List<Day>)

    private val geoCache = TtlCache<String, Place?>(24 * 3600_000L)
    private val forecastCache = TtlCache<String, Forecast>(15 * 60_000L)

    /** Ищет город; падежи («в Казани», «в Нижнем Новгороде») пробуем снять. */
    suspend fun findPlace(spoken: String): Place? {
        val key = spoken.lowercase().trim()
        geoCache.get(key)?.let { return it }
        for (candidate in nameCandidates(spoken)) {
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=${candidate.encodeURLParameter()}&count=10&language=ru&format=json"
            val found = parsePlaces(http.fetchText(url))
            val best = pickPlace(found, candidate)
            if (best != null) { geoCache.put(key, best); return best }
        }
        geoCache.put(key, null)
        return null
    }

    suspend fun forecast(lat: Double, lon: Double): Forecast {
        val key = "%.2f,%.2f".format(Locale.US, lat, lon)
        forecastCache.get(key)?.let { return it }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${"%.4f".format(Locale.US, lat)}&longitude=${"%.4f".format(Locale.US, lon)}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max" +
            "&timezone=auto&forecast_days=7&wind_speed_unit=ms"
        return parseForecast(http.fetchText(url)).also { forecastCache.put(key, it) }
    }

    companion object {
        private val ru = Locale("ru")
        private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", ru)
        private val weekdayShort = DateTimeFormatter.ofPattern("EEEE", ru)

        fun parsePlaces(json: String): List<Place> {
            val root = LoliJson.parseToJsonElement(json).jsonObject
            return root.arr("results")?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                Place(o.text("name") ?: return@mapNotNull null, o.num("latitude") ?: return@mapNotNull null, o.num("longitude") ?: return@mapNotNull null, o.text("country_code"))
            } ?: emptyList()
        }

        /** Город, чьё название начинается с основы сказанного; российские — в приоритете. */
        fun pickPlace(found: List<Place>, candidate: String): Place? {
            val stem = candidate.lowercase().replace('ё', 'е').take(maxOf(3, candidate.length - 2))
            val matching = found.filter { it.name.lowercase().replace('ё', 'е').startsWith(stem) }.ifEmpty { found.take(1) }
            return matching.firstOrNull { it.country == "RU" } ?: matching.firstOrNull()
        }

        /** «Казани» → «Казани», «Казань», «Казан»; «Нижнем Новгороде» → «Нижний Новгород»… */
        fun nameCandidates(spoken: String): List<String> {
            val s = spoken.trim().replace(Regex("""\s+"""), " ")
            val words = s.split(' ', '-').filter { it.isNotEmpty() }
            val out = LinkedHashSet<String>()
            out += s
            fun nominative(w: String): String {
                val l = w.lowercase()
                val base = when {
                    l.endsWith("ем") && l.length > 4 -> w.dropLast(2) + "ий" // Нижнем → Нижний
                    l.endsWith("ой") && l.length > 4 -> w.dropLast(2) + "ая" // Белой → Белая
                    l.endsWith("ом") && l.length > 4 -> w.dropLast(2) // Новгородом → Новгород
                    l.endsWith("ске") || l.endsWith("ге") || l.endsWith("де") || l.endsWith("ре") || l.endsWith("не") || l.endsWith("те") || l.endsWith("ле") && !l.endsWith("еле") -> w.dropLast(1) // Омске → Омск
                    l.endsWith("ве") || l.endsWith("ке") -> w.dropLast(1) + "а" // Москве → Москва, Уфе…
                    l.endsWith("и") && l.length > 4 -> w.dropLast(1) + "ь" // Казани → Казань
                    l.endsWith("е") && l.length > 3 -> w.dropLast(1) + "а"
                    l.endsWith("у") && l.length > 3 -> w.dropLast(1) + "а" // в Москву
                    else -> w
                }
                return base
            }
            out += words.joinToString(" ") { nominative(it) }.let { if (s.contains('-')) it.replace(' ', '-') else it }
            out += words.joinToString(" ") { nominative(it) }
            // Основа слова: геокодер ищет по началу названия.
            out += words.joinToString(" ") { w -> if (w.length > 5) w.dropLast(2) else if (w.length > 3) w.dropLast(1) else w }
            return out.filter { it.length >= 2 }.take(4)
        }

        fun parseForecast(json: String): Forecast {
            val root = LoliJson.parseToJsonElement(json).jsonObject
            val cur = root["current"] as? JsonObject
            val daily = root["daily"] as? JsonObject
            val days = ArrayList<Day>()
            if (daily != null) {
                val dates = daily.arr("time") ?: JsonArray(emptyList())
                for (i in dates.indices) {
                    val date = runCatching { LocalDate.parse((dates[i] as JsonPrimitive).content) }.getOrNull() ?: continue
                    days += Day(
                        date = date,
                        code = daily.arr("weather_code")?.numAt(i)?.toInt() ?: 0,
                        min = daily.arr("temperature_2m_min")?.numAt(i) ?: continue,
                        max = daily.arr("temperature_2m_max")?.numAt(i) ?: continue,
                        rainChance = daily.arr("precipitation_probability_max")?.numAt(i)?.roundToInt(),
                        wind = daily.arr("wind_speed_10m_max")?.numAt(i),
                    )
                }
            }
            return Forecast(cur?.num("temperature_2m"), cur?.num("apparent_temperature"), (cur?.get("weather_code") as? JsonPrimitive)?.intOrNull, cur?.num("wind_speed_10m"), days)
        }

        /** Коды погоды ВМО → слова. */
        fun describe(code: Int): String = when (code) {
            0 -> "ясно"
            1 -> "в основном ясно"
            2 -> "переменная облачность"
            3 -> "пасмурно"
            45, 48 -> "туман"
            51, 53, 55 -> "морось"
            56, 57 -> "ледяная морось"
            61 -> "небольшой дождь"
            63 -> "дождь"
            65 -> "сильный дождь"
            66, 67 -> "ледяной дождь"
            71 -> "небольшой снег"
            73 -> "снег"
            75 -> "сильный снег"
            77 -> "снежная крупа"
            80 -> "небольшой ливень"
            81 -> "ливень"
            82 -> "сильный ливень"
            85 -> "небольшой снегопад"
            86 -> "сильный снегопад"
            95 -> "гроза"
            96, 99 -> "гроза с градом"
            else -> "без осадков"
        }

        fun isRain(code: Int) = code in 51..67 || code in 80..82 || code >= 95
        fun isSnow(code: Int) = code in 71..77 || code in 85..86

        fun deg(t: Double): String {
            val r = t.roundToInt()
            return when {
                r > 0 -> "+$r°"
                r < 0 -> "−${-r}°"
                else -> "0°"
            }
        }

        private fun wind(w: Double?): String? = w?.roundToInt()?.takeIf { it >= 1 }?.let { "ветер $it м/с" }

        /** Текст ответа по готовому прогнозу. */
        fun answer(q: WeatherQuery, placeName: String?, f: Forecast, today: LocalDate): String {
            val where = placeName?.let { "$it: " } ?: ""
            val whereIn = placeName?.let { " ($it)" } ?: ""
            val day = q.date?.let { d -> f.days.firstOrNull { it.date == d } }
            if (q.date != null && day == null) return "Прогноз есть только на неделю вперёд — на ${RuFormat.date(q.date, today)} пока не знаю."
            val target = day ?: f.days.firstOrNull { it.date == today }
            val dayWord = q.date?.let { RuFormat.date(it, today) } ?: "сегодня"
            when (q.aspect) {
                WeatherQuery.Aspect.RAIN, WeatherQuery.Aspect.UMBRELLA -> {
                    val d = target ?: return "${where}не удалось получить прогноз."
                    val chance = d.rainChance ?: 0
                    val rainy = isRain(d.code) || chance >= 50
                    return when {
                        rainy -> "Да, $dayWord$whereIn вероятен дождь — $chance%. ${if (q.aspect == WeatherQuery.Aspect.UMBRELLA) "Зонт лучше взять." else "Возьмите зонт."}"
                        chance >= 30 -> "Возможно: $dayWord$whereIn вероятность дождя $chance%. Зонт на всякий случай не помешает."
                        isSnow(d.code) -> "Дождя не будет, но $dayWord$whereIn ожидается ${describe(d.code)}."
                        else -> "Нет, $dayWord$whereIn дождя не ожидается${if (chance > 0) " (вероятность $chance%)" else ""}. ${if (q.aspect == WeatherQuery.Aspect.UMBRELLA) "Зонт не нужен." else ""}".trim()
                    }
                }
                WeatherQuery.Aspect.SNOW -> {
                    val d = target ?: return "${where}не удалось получить прогноз."
                    return if (isSnow(d.code)) "Да, $dayWord$whereIn ожидается ${describe(d.code)}." else "Нет, $dayWord$whereIn снега не ожидается: ${describe(d.code)}, от ${deg(d.min)} до ${deg(d.max)}."
                }
                WeatherQuery.Aspect.WEEK -> {
                    val lines = f.days.take(7).map { d ->
                        val name = when (d.date) { today -> "Сегодня"; today.plusDays(1) -> "Завтра"; else -> weekdayShort.format(d.date).replaceFirstChar { it.uppercase() } + ", " + dayMonth.format(d.date) }
                        "$name: ${deg(d.min)}…${deg(d.max)}, ${describe(d.code)}${d.rainChance?.takeIf { it >= 40 }?.let { " ($it%)" } ?: ""}"
                    }
                    return "Погода на неделю${whereIn}:\n" + lines.joinToString("\n")
                }
                else -> Unit
            }
            // Сейчас.
            if (q.date == null) {
                val parts = ArrayList<String>()
                f.temp?.let { t ->
                    var s = "сейчас ${deg(t)}"
                    f.code?.let { s += ", ${describe(it)}" }
                    f.feels?.let { fl -> if (kotlin.math.abs(fl - t) >= 3) s += ", ощущается как ${deg(fl)}" }
                    wind(f.wind)?.let { s += ", $it" }
                    parts += s
                }
                target?.let { d ->
                    var s = "днём до ${deg(d.max)}, ночью до ${deg(d.min)}"
                    d.rainChance?.takeIf { it >= 40 }?.let { s += ", вероятность дождя $it%" }
                    parts += s
                }
                if (parts.isEmpty()) return "${where}не удалось получить прогноз."
                return where + parts.joinToString(". ") { it.replaceFirstChar { c -> c.uppercase() } } + "." 
            }
            val d = target ?: return "${where}не удалось получить прогноз."
            val head = "${dayWord.replaceFirstChar { it.uppercase() }}, ${dayMonth.format(d.date)}$whereIn"
            var body = "от ${deg(d.min)} до ${deg(d.max)}, ${describe(d.code)}"
            d.rainChance?.takeIf { it >= 20 }?.let { body += ", вероятность осадков $it%" }
            wind(d.wind)?.let { body += ", $it" }
            val advice = when {
                isRain(d.code) || (d.rainChance ?: 0) >= 60 -> " Зонт пригодится."
                d.max <= -10 -> " Одевайтесь теплее."
                d.max >= 28 -> " Будет жарко — не забудьте воду."
                else -> ""
            }
            return "$head: $body.$advice"
        }
    }
}
