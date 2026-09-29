package ai.loli.core.skills

import ai.loli.core.TestEnv
import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.assistant.DeviceCommand
import ai.loli.core.assistant.DevicePhrases
import ai.loli.core.assistant.MediaAction
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Баги, найденные проверкой после 2.1: ложные срабатывания разбора, диалог ответа, радио, сказки, игры. */
class Audit22Test {
    private val today = LocalDate.of(2026, 9, 25)
    private val now = Instant.parse("2026-09-25T09:00:00Z")
    private val zone = ZoneId.of("Europe/Moscow")
    private fun p(t: String) = SkillPhrases.parse(t, today)
    private fun device(t: String) = DevicePhrases.parse(t, now, zone)?.command

    @Test fun skillPhrasesFalsePositives() {
        assertTrue(p("что такое горизонт событий") is SkillCommand.Fact)
        assertFalse(p("как одеться на собеседование") is SkillCommand.Weather)
        assertFalse(p("что надеть в театр") is SkillCommand.Weather)
        assertTrue(p("нужен ли зонт") is SkillCommand.Weather)
        assertFalse(p("скажи что написали в новостях") is SkillCommand.ReadMessages)
        assertTrue(p("прочитай что пришло") is SkillCommand.ReadMessages)
        assertFalse(p("есть новости от маши") is SkillCommand.News)
        assertFalse(p("а ларисе сколько") is SkillCommand.Rates)
        assertTrue(p("курс лари") is SkillCommand.Rates)
        assertFalse(p("ответь на вопрос: сколько будет 2+2") is SkillCommand.ReplyMessage)
        assertFalse(p("ответь мне честно") is SkillCommand.ReplyMessage)
        assertTrue(p("ответь маше: буду через 10 минут") is SkillCommand.ReplyMessage)
    }

    @Test fun deviceCommandsFalsePositives() {
        assertEquals("кино", (device("вруби музыку группы кино") as? DeviceCommand.Play)?.query?.lowercase())
        assertTrue(device("запусти песенку про ёлочку") is DeviceCommand.Play)
        assertEquals(DeviceCommand.Media(MediaAction.PLAY), device("вруби музыку"))
        assertFalse(device("отправь еще сообщение маме привет").let { it is DeviceCommand.Message && it.who == "еще" })
        assertFalse(device("напиши красивое сообщение с днём рождения").let { it is DeviceCommand.Message && it.who == "красивое" })
        assertTrue(device("напиши маме сообщение перезвоню позже") is DeviceCommand.Message)
        assertFalse(device("соедини с интернетом") is DeviceCommand.Call)
        assertTrue(device("соедини с мамой") is DeviceCommand.Call)
    }

    @Test fun gamesAndTales() {
        assertTrue(Game.parseStart("я загадала число, угадай") is Game.ReverseGuess)
        assertNull(Game.parseStart("загадай число от 1 до 6"))
        assertTrue(Game.parseStart("играй в города") is Game.Cities)
        assertEquals("Лиса и журавль", Tales.find("про лису")?.title)
        assertEquals("Маша и медведь", Tales.find("про машеньку")?.title)
        assertEquals("Волк и семеро козлят", Tales.find("про волка")?.title)
        assertNull(Tales.find("про гусеницу"))
        assertTrue(DevicePhrases.answer("загадай число от 1 до 6", today)!!.startsWith("Пусть будет"))
    }

    @Test fun reverseGuessFindsNumber() = runTest {
        val g = Game.ReverseGuess()
        assertTrue(g.start().endsWith("Это 50?"))
        assertEquals("Это 75?", g.play("больше", null).text)
        assertEquals("Это 62?", g.play("меньше", null).text)
        assertTrue(g.play("да", null).over)
    }

    private class Host(private val playing: Boolean = false) : SkillHost {
        val sent = ArrayList<Pair<String, String>>()
        val radio = ArrayList<String>()
        override suspend fun location() = null
        override suspend fun messages() = listOf(IncomingMessage("k1", "Telegram", "Маша", "Ты где?", Instant.parse("2026-09-25T08:55:00Z"), canReply = true))
        override suspend fun reply(message: IncomingMessage, text: String): Boolean { sent += message.sender to text; return true }
        override suspend fun playRadio(station: RadioStation): Boolean { radio += station.name; return true }
        override fun radioPlaying() = playing
    }

    @Test fun replyWaitsForText() = runTest {
        val host = Host()
        val env = TestEnv(skillsFactory = { t -> Skills(host, null, t) }).apply { settings = AssistantSettings(useAI = false) }
        assertEquals("Что ответить Маша?", env.engine.handle("ответь").text)
        val r = env.engine.handle("буду через 10 минут")
        assertEquals(listOf("Маша" to "Буду через 10 минут"), host.sent, r.text)
        assertTrue(env.store.reminders.all().isEmpty())
    }

    @Test fun nextWhileRadioPlaysSwitchesStation() = runTest {
        val host = Host(playing = true)
        val env = TestEnv(skillsFactory = { t -> Skills(host, null, t) }).apply { settings = AssistantSettings(useAI = false) }
        val r = env.engine.handle("дальше")
        assertTrue(r.text.startsWith("Включаю"), r.text)
        assertEquals(1, host.radio.size)
    }

    @Test fun complimentKeepsCommand() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(useAI = false) }
        env.engine.handle("молодец, напомни через 5 минут выключить плиту")
        assertEquals(1, env.store.reminders.all().size)
        assertTrue(env.engine.handle("спасибо большое").text.isNotBlank())
    }

    @Test fun timeQuestionIsAnchored() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(useAI = false) }
        assertTrue(env.engine.handle("сколько время").text.startsWith("Сейчас"))
        assertFalse(env.engine.handle("сколько времени варить гречку").text.startsWith("Сейчас"))
    }

    @Test fun cryptoFallsBackWhenCoinGeckoRefuses() = runTest {
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { r ->
            val u = r.url.toString()
            when {
                u.contains("coingecko") -> respond("", io.ktor.http.HttpStatusCode.Forbidden)
                u.contains("cryptocompare") -> respond("""{"BTC":{"USD":80000},"ETH":{"USD":2500},"USDT":{"USD":1}}""", io.ktor.http.HttpStatusCode.OK)
                u.contains("cbr-xml-daily") -> respond("""{"Date":"2026-09-26T11:30:00+03:00","Valute":{"USD":{"CharCode":"USD","Nominal":1,"Name":"Доллар США","Value":84.0,"Previous":84.1}}}""", io.ktor.http.HttpStatusCode.OK)
                else -> respond("", io.ktor.http.HttpStatusCode.NotFound)
            }
        })
        val prices = RatesService(http).crypto()
        assertEquals(6_720_000.0, prices.getValue("BTC"), 1.0)
        assertEquals(mapOf("BTC" to 5_000_000.0), RatesService.parseCoinbase("""{"data":{"currency":"USD","rates":{"BTC":"0.00002"}}}""", 100.0))
    }
}
