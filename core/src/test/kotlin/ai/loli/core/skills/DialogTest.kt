package ai.loli.core.skills

import ai.loli.core.TestEnv
import ai.loli.core.assistant.AssistantSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Живой диалог 2.2: уточнения к последнему ответу, «повтори», «ещё». */
class DialogTest {
    private val geo = """{"results":[{"id":1,"name":"Москва","latitude":55.75,"longitude":37.61,"country_code":"RU","country":"Россия"}]}"""
    private val geoKazan = """{"results":[{"id":2,"name":"Казань","latitude":55.78,"longitude":49.12,"country_code":"RU","country":"Россия"}]}"""
    private val forecast = """{"current":{"time":"2026-09-25T12:00","temperature_2m":15.8,"apparent_temperature":11.3,"weather_code":2,"wind_speed_10m":4.1},"daily":{"time":["2026-09-25","2026-09-26","2026-09-27","2026-09-28","2026-09-29","2026-09-30","2026-10-01"],"weather_code":[3,61,80,3,3,2,71],"temperature_2m_max":[16.0,16.1,16.4,15.4,15.6,14.7,1.1],"temperature_2m_min":[8.5,9.6,10.3,7.3,8.8,6.5,-2.4],"precipitation_probability_max":[0,70,23,0,0,0,2],"wind_speed_10m_max":[2.1,6.1,1.8,1.7,2.3,1.8,2.2]}}"""
    private val cbr = """{"Date":"2026-09-26T11:30:00+03:00","Valute":{"USD":{"CharCode":"USD","Nominal":1,"Name":"Доллар США","Value":84.35,"Previous":84.1},"EUR":{"CharCode":"EUR","Nominal":1,"Name":"Евро","Value":98.7,"Previous":99.1}}}"""
    private val rss = "<rss><channel>" + (1..12).joinToString("") { "<item><title>Новость $it</title><link>https://x/$it</link></item>" } + "</channel></rss>"
    val urls = ArrayList<String>()

    private fun http() = HttpClient(MockEngine { r ->
        val u = r.url.toString()
        urls += u
        val body = when {
            u.contains("geocoding-api") && u.contains("%D0%9A%D0%B0%D0%B7%D0%B0") -> geoKazan
            u.contains("geocoding-api") -> geo
            u.contains("open-meteo") -> forecast
            u.contains("cbr-xml-daily") -> cbr
            u.contains("lenta.ru/rss/news/sport") -> "<rss><channel><item><title>Спорт 1</title></item></channel></rss>"
            u.contains("lenta.ru") -> rss
            else -> return@MockEngine respond("", HttpStatusCode.NotFound)
        }
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json; charset=utf-8"))
    })

    private class Host : SkillHost { override suspend fun location() = GeoPoint(55.75, 37.62) }

    private fun env() = TestEnv(skillsFactory = { t -> Skills(Host(), http(), t) }).apply { settings = AssistantSettings(useAI = false) }

    @Test fun weatherFollowUps() = runTest {
        val e = env()
        assertTrue(e.engine.handle("какая погода").text.startsWith("Сейчас"))
        val tomorrow = e.engine.handle("а завтра?").text
        assertTrue(tomorrow.contains("Завтра") || tomorrow.contains("26"), tomorrow)
        val kazan = e.engine.handle("а в Казани?").text
        assertTrue(kazan.contains("Казань"), kazan)
        val week = e.engine.handle("а на неделю?").text
        assertTrue(week.lines().size >= 7, week)
    }

    @Test fun ratesAndNewsFollowUps() = runTest {
        val e = env()
        assertTrue(e.engine.handle("курс доллара").text.contains("84"))
        assertTrue(e.engine.handle("а евро?").text.contains("98"))
        assertTrue(e.engine.handle("новости").text.contains("1. Новость 1"))
        val more = e.engine.handle("ещё").text
        assertTrue(more.startsWith("Ещё новости") && more.contains("6. Новость 6"), more)
        assertTrue(e.engine.handle("а спорт?").text.contains("Спорт 1"))
    }

    @Test fun repeatAndMore() = runTest {
        val e = env()
        val first = e.engine.handle("курс доллара").text
        assertEquals(first, e.engine.handle("повтори").text)
        assertEquals(first, e.engine.handle("что ты сказала?").text)
        val joke = e.engine.handle("расскажи анекдот").text
        val next = e.engine.handle("ещё").text
        assertTrue(next.isNotBlank() && !next.contains("Не поняла"), next)
        assertTrue(joke.isNotBlank())
    }

    @Test fun undoLast() = runTest {
        val e = env()
        e.engine.handle("потратила 300 на кофе")
        assertEquals(1, e.store.expenses.all().size)
        val r = e.engine.handle("отмени последнее")
        if (r.awaitingConfirmation) e.engine.handle("да")
        assertEquals(0, e.store.expenses.all().size, r.text)
        e.engine.handle("добавь молоко в покупки")
        val r2 = e.engine.handle("удали то что добавила")
        if (r2.awaitingConfirmation) e.engine.handle("да")
        assertEquals(0, e.store.shopping.all().count { !it.done }, r2.text)
    }
}
