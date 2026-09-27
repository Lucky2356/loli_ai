package ai.loli.app

import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.skills.GeoPoint
import ai.loli.core.skills.RadioCatalog
import ai.loli.core.skills.SkillCommand
import ai.loli.core.skills.SkillHost
import ai.loli.core.skills.SkillOutcome
import ai.loli.core.skills.SkillPhrases
import ai.loli.core.skills.Skills
import ai.loli.core.util.SystemTimeSource
import ai.loli.core.voice.SpeechText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Живая проверка на Android: тот же сетевой клиент, что в приложении, настоящие адреса погоды, новостей,
 * курсов, Википедии и радио. Плюс разбор фраз и произношение на движке регулярных выражений Android (ICU).
 */
@RunWith(AndroidJUnit4::class)
class LiveSkillsTest {
    private val http = HttpClient(Android) {
        expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 60_000; socketTimeoutMillis = 60_000 }
    }
    private val host = object : SkillHost {
        override suspend fun location() = GeoPoint(55.7558, 37.6173)
    }
    private val skills = Skills(host, http, SystemTimeSource())
    private val cfg = AssistantSettings()

    private fun ask(phrase: String): String = runBlocking {
        val out = skills.handle(phrase, cfg, null)
        val text = (out as? SkillOutcome.Say)?.text ?: "нет ответа: $out"
        println("LIVE «$phrase» → $text")
        text
    }

    private fun assertAnswered(phrase: String, vararg expect: String) {
        val t = ask(phrase)
        assertFalse("«$phrase»: $t", t.startsWith("Не получилось") || t.startsWith("Чтобы узнать") || t.startsWith("нет ответа") || t.startsWith("Не нашла"))
        assertTrue("«$phrase»: $t", expect.all { t.contains(it, ignoreCase = true) })
    }

    @Test fun weather() {
        assertAnswered("какая погода", "°")
        assertAnswered("какая погода завтра в Казани", "Казань")
        assertAnswered("будет ли дождь в Санкт-Петербурге")
        assertAnswered("погода на неделю", "Завтра")
    }

    @Test fun news() {
        assertAnswered("новости", "1.")
        assertAnswered("новости спорта", "1.")
        assertAnswered("что нового в науке", "1.")
        assertAnswered("новости экономики", "1.")
    }

    @Test fun ratesAndFacts() {
        assertAnswered("курс доллара", "₽")
        assertAnswered("сколько стоит биткоин", "₽")
        assertAnswered("переведи 100 долларов в евро", "евро")
        assertAnswered("кто такой Гагарин", "космонавт")
    }

    @Test fun radioCatalog() = runBlocking {
        val found = RadioCatalog(http).find("джаз")
        println("LIVE радио джаз → ${found.take(3).map { it.name }}")
        assertTrue(found.isNotEmpty())
    }

    /** На Android регулярные выражения — ICU: проверяем, что разбор совпадает с тестами на JVM. */
    @Test fun phrasesOnIcu() {
        val today = LocalDate.of(2026, 9, 25)
        fun p(t: String) = SkillPhrases.parse(t, today)
        assertTrue(p("какая погода завтра в Казани") is SkillCommand.Weather)
        assertTrue(p("че там по погоде") is SkillCommand.Weather)
        assertTrue(p("курс доллара") is SkillCommand.Rates)
        assertTrue(p("новости спорта") is SkillCommand.News)
        assertTrue(p("кто такой Гагарин") is SkillCommand.Fact)
        assertTrue(p("включи радио европа плюс") is SkillCommand.RadioPlay)
        assertEquals(null, p("поставь напоминание завтра провести встречу"))
        assertEquals("Будильник на 7 часов.", SpeechText.forSpeech("Будильник на 7:00."))
        assertEquals("плюс 12 градусов", SpeechText.declineUnits("+12°"))
    }
}
