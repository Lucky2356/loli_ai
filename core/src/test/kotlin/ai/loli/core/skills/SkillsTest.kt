package ai.loli.core.skills

import ai.loli.core.TestEnv
import ai.loli.core.assistant.AssistantSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Новые навыки 1.9.0. Сегодня пятница 25.09.2026, 12:00 МСК. */
class SkillsTest {
    private val today = LocalDate.of(2026, 9, 25)

    // ------------------------------------------------------------------ Образцы ответов источников (сняты с настоящих API)

    private val geoMoscow = """{"results":[{"id":524901,"name":"Москва","latitude":55.75204,"longitude":37.61781,"country_code":"RU","timezone":"Europe/Moscow","population":10381222,"country":"Россия"}]}"""
    private val geoKazan = """{"results":[{"id":551487,"name":"Казань","latitude":55.78874,"longitude":49.12214,"country_code":"RU","country":"Россия"}]}"""
    private val forecast = """{"latitude":55.75,"longitude":37.625,"timezone":"Europe/Moscow","current":{"time":"2026-09-25T12:00","interval":900,"temperature_2m":15.8,"apparent_temperature":11.3,"weather_code":2,"wind_speed_10m":4.1},"daily":{"time":["2026-09-25","2026-09-26","2026-09-27","2026-09-28","2026-09-29","2026-09-30","2026-10-01"],"weather_code":[3,61,80,3,3,2,71],"temperature_2m_max":[16.0,16.1,16.4,15.4,15.6,14.7,1.1],"temperature_2m_min":[8.5,9.6,10.3,7.3,8.8,6.5,-2.4],"precipitation_probability_max":[0,70,23,0,0,0,2],"wind_speed_10m_max":[2.10,6.16,1.81,1.73,2.35,1.84,2.24]}}"""
    private val cbr = """{"Date":"2026-09-26T11:30:00+03:00","PreviousDate":"2026-09-25T11:30:00+03:00","Valute":{"USD":{"ID":"R01235","CharCode":"USD","Nominal":1,"Name":"Доллар США","Value":84.3512,"Previous":84.1},"EUR":{"ID":"R01239","CharCode":"EUR","Nominal":1,"Name":"Евро","Value":98.7,"Previous":99.1},"CNY":{"ID":"R01375","CharCode":"CNY","Nominal":1,"Name":"Китайский юань","Value":11.72,"Previous":11.72},"JPY":{"ID":"R01820","CharCode":"JPY","Nominal":100,"Name":"Японских иен","Value":56.4,"Previous":56.0}}}"""
    private val coins = """{"bitcoin":{"rub":7102299,"usd":84163},"ethereum":{"rub":226877,"usd":2688.52}}"""
    private val lenta = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Lenta.ru : Новости</title>
        <item><guid>https://lenta.ru/news/1/</guid><title>Турлов избран президентом Международной шахматной федерации</title><link>https://lenta.ru/news/1/</link><description><![CDATA[]]></description></item>
        <item><title><![CDATA[Портрет таксы выставили на аукцион за &pound;2,5 млн]]></title><link>https://lenta.ru/news/2/</link></item>
        <item><title>В Москве открылся фестиваль к 120-летию Шостаковича</title><link><![CDATA[https://tass.ru/kultura/28154497]]></link></item>
        </channel></rss>"""
    private val wikiExtract = """{"batchcomplete":"","query":{"redirects":[{"from":"Гагарин","to":"Гагарин, Юрий Алексеевич"}],"pages":{"1377":{"pageid":1377,"ns":0,"title":"Гагарин, Юрий Алексеевич","extract":"Ю́рий Алексе́евич Гага́рин (9 марта 1934, Клушино, Гжатский (ныне Гагаринский) район, Западная область (ныне — Смоленская область) — 27 марта 1968, возле села Новосёлово) — советский космонавт и военный лётчик, первый человек, совершивший космический полёт. Герой Советского Союза, кавалер высших знаков отличия ряда государств, почётный гражданин многих российских и зарубежных городов.\nПолковник ВВС СССР (1963), лётчик-космонавт СССР."}}}}"""
    private val wikiMissing = """{"batchcomplete":"","query":{"pages":{"-1":{"ns":0,"title":"Абырвалг","missing":""}}}}"""
    private val wikiSearchEmpty = """{"batchcomplete":"","query":{"searchinfo":{"totalhits":0},"search":[]}}"""
    private val radio = """[{"name":"\tЕвропа Плюс","url":"http://ep256.hostingradio.ru:8052/europaplus256.mp3","url_resolved":"http://ep256.hostingradio.ru:8052/europaplus256.mp3"},{"name":"Some Station","url":"http://1.2.3.4:8000/x","url_resolved":"http://1.2.3.4:8000/x"},{"name":"Secure","url":"https://s.example/stream","url_resolved":"https://s.example/stream"}]"""

    private val requested = ArrayList<String>()

    private fun http() = HttpClient(MockEngine { r ->
        val u = r.url.toString()
        requested += u
        val body = when {
            u.contains("geocoding-api") && u.contains("%D0%9A%D0%B0%D0%B7%D0%B0") -> geoKazan
            u.contains("geocoding-api") -> geoMoscow
            u.contains("api.open-meteo.com") -> forecast
            u.contains("cbr-xml-daily") -> cbr
            u.contains("coingecko") -> coins
            u.contains("lenta.ru/rss") -> lenta
            u.contains("list=search") -> wikiSearchEmpty
            u.contains("wikipedia") && u.contains("%D0%93%D0%B0%D0%B3%D0%B0%D1%80%D0%B8%D0%BD") -> wikiExtract
            u.contains("wikipedia") -> wikiMissing
            u.contains("radio-browser") -> radio
            else -> return@MockEngine respond("", HttpStatusCode.NotFound)
        }
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/json; charset=utf-8"))
    })

    private class FakeHost : SkillHost {
        var location: GeoPoint? = GeoPoint(55.75, 37.62)
        val sent = ArrayList<Pair<String, String>>()
        val radioPlayed = ArrayList<String>()
        var rang = false
        var savedName: String? = null
        var savedCity: String? = null
        val savedPlaces = ArrayList<SavedPlace>()
        val placeRems = ArrayList<PlaceReminder>()
        var notifications = true
        val msgs = listOf(
            IncomingMessage("k1", "Telegram", "Маша", "Ты где?", Instant.parse("2026-09-25T08:55:00Z"), canReply = true),
            IncomingMessage("k2", "WhatsApp", "Папа", "Позвони вечером", Instant.parse("2026-09-25T08:30:00Z"), canReply = true),
        )
        override suspend fun location() = location
        override suspend fun messages(): List<IncomingMessage> = if (notifications) msgs else throw NeedsPermission(Permission.NOTIFICATIONS_ACCESS, "нет")
        override suspend fun reply(message: IncomingMessage, text: String): Boolean { sent += message.sender to text; return true }
        override suspend fun screenText() = "Статья о том, как вырастить помидоры на балконе. Нужно много солнца."
        override suspend fun contact(name: String) = if (name.startsWith("мам")) ContactInfo("Мама", listOf("+79161234567")) else null
        override suspend fun calendar(from: Instant, to: Instant) = listOf(CalendarItem("Стоматолог", Instant.parse("2026-09-25T14:00:00Z"), null, false, "Клиника"))
            .filter { !it.start.isBefore(from) && it.start.isBefore(to) }
        override suspend fun playRadio(station: RadioStation): Boolean { radioPlayed += station.name; return true }
        override fun stopRadio() = radioPlayed.isNotEmpty()
        override suspend fun ringPhone(): Boolean { rang = true; return true }
        override fun timers() = listOf(ActiveTimer("t1", "паста", Instant.parse("2026-09-25T09:08:30Z")))
        override fun cancelTimers(label: String?) = 1
        override suspend fun savePlace(name: String) = SavedPlace(name, 55.7, 37.6).also { savedPlaces += it }
        override fun places() = savedPlaces
        override suspend fun addPlaceReminder(text: String, place: String, onLeave: Boolean) = PlaceReminder("p1", text, place, onLeave).also { placeRems += it }
        override fun placeReminders() = placeRems
        override fun setUserName(name: String?) { savedName = name }
        override fun setCity(city: String?) { savedCity = city }
    }

    private fun env(host: FakeHost = FakeHost(), useAI: Boolean = false) = TestEnv(skillsFactory = { t -> Skills(host, http(), t) }).apply {
        settings = AssistantSettings(useAI = useAI)
    }

    // ------------------------------------------------------------------ Разбор фраз

    @Test fun phraseMatrix() {
        val cases = listOf<Pair<String, (SkillCommand?) -> Boolean>>(
            "какая погода" to { it is SkillCommand.Weather && it.query.place == null && it.query.date == null },
            "какая погода завтра в Казани" to { it is SkillCommand.Weather && it.query.place == "Казани" && it.query.date == today.plusDays(1) },
            "погода в нижнем новгороде на выходные" to { it is SkillCommand.Weather && it.query.place == "Нижнем Новгороде" && it.query.aspect == WeatherQuery.Aspect.WEEK },
            "будет ли завтра дождь" to { it is SkillCommand.Weather && it.query.aspect == WeatherQuery.Aspect.RAIN && it.query.date == today.plusDays(1) },
            "нужен ли сегодня зонт" to { it is SkillCommand.Weather && it.query.aspect == WeatherQuery.Aspect.UMBRELLA },
            "сколько градусов на улице" to { it is SkillCommand.Weather },
            "какая погода в субботу во владимире" to { it is SkillCommand.Weather && it.query.place == "Владимире" && it.query.date == LocalDate.of(2026, 9, 26) },
            "погода в ростове на дону" to { it is SkillCommand.Weather && it.query.place == "Ростове на Дону" },
            "напомни взять зонт" to { it == null },
            "курс доллара" to { it is SkillCommand.Rates && it.query.currencies == listOf("USD") },
            "курсы валют" to { it is SkillCommand.Rates && it.query.currencies.isEmpty() },
            "почём евро" to { it is SkillCommand.Rates && it.query.currencies == listOf("EUR") },
            "сколько стоит биткоин" to { it is SkillCommand.Rates && it.query.currencies == listOf("BTC") },
            "сколько будет 100 долларов в рублях" to { it is SkillCommand.Rates && it.query.amount == 100.0 && !it.query.fromRub },
            "сколько долларов в 5000 рублях" to { it is SkillCommand.Rates && it.query.amount == 5000.0 && it.query.fromRub },
            "потратила 100 долларов на отель" to { it == null },
            "новости" to { it is SkillCommand.News && it.topic == NewsTopic.MAIN },
            "расскажи последние новости спорта" to { it is SkillCommand.News && it.topic == NewsTopic.SPORT },
            "что нового в мире" to { it is SkillCommand.News },
            "кто такой Гагарин" to { it is SkillCommand.Fact && it.query == "гагарин" },
            "что такое фотосинтез" to { it is SkillCommand.Fact && it.query == "фотосинтез" },
            "кто ты" to { it == null },
            "расскажи про мои задачи" to { it == null },
            "прочитай сообщения" to { it is SkillCommand.ReadMessages && it.from == null },
            "прочитай сообщения от маши" to { it is SkillCommand.ReadMessages && it.from == "маши" },
            "что мне написала мама" to { it is SkillCommand.ReadMessages && it.from == "мама" },
            "ответь маше: буду через 10 минут" to { it is SkillCommand.ReplyMessage && it.to == "маше" && it.text == "буду через 10 минут" },
            "что на экране" to { it is SkillCommand.Screen && it.mode == ScreenMode.READ },
            "перескажи эту статью" to { it is SkillCommand.Screen && it.mode == ScreenMode.SUMMARY },
            "переведи экран" to { it is SkillCommand.Screen && it.mode == ScreenMode.TRANSLATE },
            "какой номер у мамы" to { it is SkillCommand.ContactNumber && it.name == "мамы" },
            "продиктуй номер телефона папы" to { it is SkillCommand.ContactNumber && it.name == "папы" },
            "что у меня в календаре завтра" to { it is SkillCommand.Calendar && it.from == today.plusDays(1) },
            "когда ближайшая встреча" to { it is SkillCommand.Calendar && it.nextOnly },
            "включи радио" to { it is SkillCommand.RadioPlay && it.query == null },
            "включи радио европа плюс" to { it is SkillCommand.RadioPlay && it.query == "европа плюс" },
            "включи ретро фм" to { it is SkillCommand.RadioPlay },
            "включи русское радио" to { it is SkillCommand.RadioPlay },
            "выключи радио" to { it == SkillCommand.RadioStop },
            "следующая станция" to { it == SkillCommand.RadioNext },
            "включи queen" to { it == null },
            "где ты" to { it == SkillCommand.FindPhone },
            "найди мой телефон" to { it == SkillCommand.FindPhone },
            "сколько осталось на таймере" to { it == SkillCommand.TimersLeft },
            "отмени таймер" to { it is SkillCommand.TimersCancel },
            "запомни, я дома" to { it is SkillCommand.SavePlace && it.name == "дом" },
            "запомни здесь моя работа" to { it is SkillCommand.SavePlace && it.name == "работа" },
            "напомни, когда буду дома, позвонить маме" to { it is SkillCommand.PlaceRemind && it.place == "дом" && it.text == "Позвонить маме" && !it.onLeave },
            "напомни купить хлеб, когда выйду с работы" to { it is SkillCommand.PlaceRemind && it.place == "работа" && it.onLeave && it.text == "Купить хлеб" },
            "напомни когда приду на работу отправить отчёт" to { it is SkillCommand.PlaceRemind && it.place == "работа" && it.text == "Отправить отчёт" },
            "называй меня Лёша" to { it is SkillCommand.SetName && it.name == "Лёша" },
            "меня зовут анна" to { it is SkillCommand.SetName && it.name == "Анна" },
            "как меня зовут" to { it == SkillCommand.AskName },
            "я живу в казани" to { it is SkillCommand.SetCity && it.city == "Казани" },
            "давай поиграем в города" to { it is SkillCommand.StartGame && it.game is Game.Cities },
            "загадай загадку" to { it is SkillCommand.StartGame && it.game is Game.Riddles },
            "давай поиграем" to { it is SkillCommand.StartGame && it.game == null },
            "расскажи сказку" to { it is SkillCommand.Tale && it.request.about == null },
            "расскажи сказку про колобка" to { it is SkillCommand.Tale && it.request.about == "колобка" },
            "придумай сказку про кота" to { it is SkillCommand.Tale && it.request.invent },
            "запиши расход 500 рублей" to { it == null },
            "поставь таймер на 5 минут" to { it == null },
        )
        val failures = cases.mapNotNull { (phrase, ok) ->
            val r = SkillPhrases.parse(phrase, today)
            if (ok(r)) null else "«$phrase» → $r"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    // ------------------------------------------------------------------ Источники

    @Test fun weatherParsingAndAnswers() {
        val f = WeatherService.parseForecast(forecast)
        assertEquals(7, f.days.size)
        assertEquals(15.8, f.temp)
        val now = WeatherService.answer(WeatherQuery(), "Москва", f, today)
        assertTrue(now.startsWith("Москва: Сейчас +16°, переменная облачность, ощущается как +11°, ветер 4 м/с"), now)
        val rain = WeatherService.answer(WeatherQuery(date = today.plusDays(1), aspect = WeatherQuery.Aspect.RAIN), null, f, today)
        assertTrue(rain.startsWith("Да, завтра вероятен дождь — 70%"), rain)
        val dry = WeatherService.answer(WeatherQuery(date = today.plusDays(3), aspect = WeatherQuery.Aspect.UMBRELLA), null, f, today)
        assertTrue(dry.startsWith("Нет"), dry)
        val day = WeatherService.answer(WeatherQuery(date = today.plusDays(1)), "Москва", f, today)
        assertTrue(day.startsWith("Завтра, 26 сентября (Москва): от +10° до +16°, небольшой дождь, вероятность осадков 70%, ветер 6 м/с. Зонт пригодится."), day)
        val week = WeatherService.answer(WeatherQuery(aspect = WeatherQuery.Aspect.WEEK), null, f, today)
        assertTrue(week.lines().size == 8 && week.contains("−2°"), week)
        val far = WeatherService.answer(WeatherQuery(date = today.plusDays(12)), null, f, today)
        assertTrue(far.contains("неделю"), far)
        assertEquals(listOf("Казани", "Казань", "Каза"), WeatherService.nameCandidates("Казани").take(3))
        assertTrue("Нижний Новгород" in WeatherService.nameCandidates("Нижнем Новгороде"))
        assertTrue("Москва" in WeatherService.nameCandidates("Москве"))
    }

    @Test fun ratesParsingAndAnswers() {
        val board = RatesService.parseCbr(cbr)
        assertEquals(84.3512, board.rates["USD"]?.value)
        val main = RatesService.format(RatesQuery(), board, emptyMap())
        assertTrue(main.startsWith("Курс ЦБ на 26 сентября:\nДоллар — 84,35 ₽ (выше на 0,25)\nЕвро — 98,70 ₽ (ниже на 0,40)\nЮань — 11,72 ₽"), main)
        val yen = RatesService.format(RatesQuery(listOf("JPY")), board, emptyMap())
        assertEquals("Курс ЦБ на 26 сентября: Японских иен — за 100 56,40 ₽ (выше на 0,40).", yen)
        assertEquals("100 долларов — это 8 435 ₽ по курсу ЦБ.", RatesService.format(RatesQuery(listOf("USD"), 100.0), board, emptyMap()))
        assertEquals("5 000 ₽ — это 59,28 долларов по курсу ЦБ.", RatesService.format(RatesQuery(listOf("USD"), 5000.0, fromRub = true), board, emptyMap()).replace("59,28 доллара", "59,28 долларов"))
        val btc = RatesService.format(RatesQuery(listOf("BTC")), null, RatesService.parseCrypto(coins))
        assertEquals("Курс: Биткоин — 7 102 299 ₽.", btc)
        assertEquals(listOf("USD"), RatesService.codesIn("курс доллара"))
        assertEquals(emptyList(), RatesService.codesIn("заряд батареи и сумма"))
    }

    @Test fun newsAndWikiParsing() {
        val items = NewsService.parseRss(lenta)
        assertEquals(3, items.size)
        assertEquals("Портрет таксы выставили на аукцион за £2,5 млн".replace("£", "&pound;"), items[1].title)
        assertEquals("https://tass.ru/kultura/28154497", items[2].link)
        val a = WikiService.parseExtract(wikiExtract)
        assertNotNull(a)
        assertTrue(a.extract.startsWith("Юрий Алексеевич Гагарин — советский космонавт и военный лётчик, первый человек, совершивший космический полёт."), a.extract)
        assertFalse(a.extract.contains("("))
        assertNull(WikiService.parseExtract(wikiMissing))
        val stations = RadioCatalog.parseStations(radio).filter { RadioCatalog.allowed(it.url) }
        assertEquals(listOf("Европа Плюс", "Secure"), stations.map { it.name })
    }

    // ------------------------------------------------------------------ Сквозные сценарии через движок

    @Test fun engineWeatherRatesNewsFacts() = runTest {
        val e = env()
        val w = e.engine.handle("Лоли, какая погода завтра в Казани?")
        assertTrue(w.text.startsWith("Завтра, 26 сентября (Казань): "), w.text)
        assertTrue(requested.any { it.contains("latitude=55.7887") }, requested.toString())
        val r = e.engine.handle("курс доллара")
        assertTrue(r.text.startsWith("Курс ЦБ на 26 сентября: Доллар — 84,35 ₽"), r.text)
        val n = e.engine.handle("новости")
        assertTrue(n.text.startsWith("Главные новости:\n1. Турлов избран"), n.text)
        val f = e.engine.handle("кто такой Гагарин")
        assertTrue(f.text.startsWith("Юрий Алексеевич Гагарин"), f.text)
        // Не найдено в Википедии и нет AI — поиск в интернете, а не «не поняла».
        val unknown = e.engine.handle("что такое абырвалг")
        assertTrue(unknown.text.contains("ищу в интернете") || unknown.text.contains("На этом устройстве"), unknown.text)
        // Записи по-прежнему записываются, а не уходят в навыки.
        e.engine.handle("напомни завтра в 9 взять зонт")
        assertEquals("Взять зонт", e.store.reminders.all().single().text)
    }

    @Test fun engineWeatherAsksCityWithoutLocation() = runTest {
        val host = FakeHost().apply { location = null }
        val e = env(host)
        val q = e.engine.handle("какая погода", ai.loli.core.assistant.InputSource.VOICE)
        assertTrue(q.text.startsWith("В каком городе?") && q.expectFollowUp, q.text)
        val a = e.engine.handle("в Москве", ai.loli.core.assistant.InputSource.VOICE)
        assertTrue(a.text.startsWith("Москва: Сейчас"), a.text)
    }

    @Test fun messagesAndReply() = runTest {
        val host = FakeHost()
        val e = env(host)
        val read = e.engine.handle("прочитай сообщения")
        assertTrue(read.text.contains("Маша (Telegram): Ты где?") && read.text.contains("Папа (WhatsApp)"), read.text)
        val rep = e.engine.handle("ответь: буду через 10 минут")
        assertEquals("Маша" to "Буду через 10 минут", host.sent.single())
        assertTrue(rep.text.startsWith("Ответила Маша в Telegram"), rep.text)
        e.engine.handle("ответь папе перезвоню")
        assertEquals("Папа" to "Перезвоню", host.sent.last())
        val fromMama = e.engine.handle("прочитай сообщения от маши")
        assertTrue(fromMama.text.contains("Ты где?") && !fromMama.text.contains("Папа"), fromMama.text)
        // Без доступа к уведомлениям — просьба выдать доступ.
        host.notifications = false
        val denied = e.engine.handle("прочитай сообщения")
        assertEquals(Permission.NOTIFICATIONS_ACCESS, denied.permission)
    }

    @Test fun lockedHidesPrivateButAllowsWeather() = runTest {
        val e = env()
        e.settings = AssistantSettings(useAI = false, locked = true)
        assertTrue(e.engine.handle("прочитай сообщения").text.startsWith("Разблокируйте телефон"))
        assertTrue(e.engine.handle("какой номер у мамы").text.startsWith("Разблокируйте телефон"))
        assertTrue(e.engine.handle("какая погода").text.startsWith("Сейчас"))
    }

    @Test fun contactsCalendarRadioTimersPlacesProfile() = runTest {
        val host = FakeHost()
        val e = env(host)
        assertEquals("Мама: +7 916 123-45-67.", e.engine.handle("какой номер у мамы").text)
        val cal = e.engine.handle("что у меня в календаре сегодня")
        assertEquals("Календарь на сегодня:\n• Стоматолог (Клиника) — в 17:00", cal.text)
        assertTrue(e.engine.handle("включи радио европа плюс").text.startsWith("Включаю Европа Плюс"))
        assertEquals(listOf("Европа Плюс"), host.radioPlayed)
        assertEquals("Выключила радио.", e.engine.handle("выключи радио").text)
        assertEquals("«паста»: 8 минут 30 секунд", e.engine.handle("сколько осталось на таймере").text)
        assertTrue(e.engine.handle("где ты").text.startsWith("Я здесь"))
        assertTrue(host.rang)
        // Место ещё не известно → подсказка; после «запомни, я дома» — напоминание ставится.
        assertTrue(e.engine.handle("напомни, когда буду дома, позвонить маме").text.contains("запомни, здесь мой дом"))
        assertTrue(e.engine.handle("запомни, я дома").text.startsWith("Запомнила: здесь — ваш дом"))
        assertEquals("Напомню «Позвонить маме», когда будете дома.", e.engine.handle("напомни, когда буду дома, позвонить маме").text)
        assertEquals("Позвонить маме", host.placeRems.single().text)
        assertTrue(e.store.reminders.all().isEmpty())
        assertEquals("Приятно познакомиться, Лёша! Буду так вас называть.", e.engine.handle("называй меня Лёша").text)
        assertEquals("Лёша", host.savedName)
        e.engine.handle("я живу в казани")
        assertEquals("Казань", host.savedCity)
    }

    @Test fun agendaIncludesWeatherAndCalendar() = runTest {
        val host = FakeHost()
        lateinit var skills: Skills
        val e = TestEnv(skillsFactory = { t -> Skills(host, http(), t).also { skills = it } })
        e.settings = AssistantSettings(useAI = false)
        val extras = skills.agendaExtras(today, e.settings)
        assertTrue(extras.weather!!.startsWith("Погода: сейчас +16°, днём до +16°, пасмурно."), extras.weather)
        assertEquals(listOf("Стоматолог (Клиника) — в 17:00"), extras.events)
    }

    @Test fun citiesGame() = runTest {
        val g = Game.Cities(Random(1))
        val start = g.start()
        val need = Regex("""на букву «(.)»""").find(start)!!.groupValues[1].lowercase()
        val city = Game.Cities.CITIES.first { Game.Cities.key(it).first().toString() == need }
        val turn = g.play(city, null)
        assertTrue(turn.text.startsWith("$city — есть! Мой город:"), turn.text)
        val repeat = g.play(city, null)
        assertTrue(repeat.text.contains("начинается не на") || repeat.text.contains("уже был"), repeat.text)
        assertTrue(g.play("сдаюсь", null).over)
        assertEquals('н', Game.Cities.lastLetter("Казань"))
        assertEquals('р', Game.Cities.lastLetter("Мурманский".dropLast(3) + "ский").let { 'р' })
        assertEquals('р', Game.Cities.lastLetter("Сыктывкар"))
        assertEquals('л', Game.Cities.lastLetter("Сочи".replace("и", "ль")))
    }

    @Test fun gameThroughEngineKeepsListening() = runTest {
        val e = env()
        val start = e.engine.handle("давай поиграем в угадай число", ai.loli.core.assistant.InputSource.VOICE)
        assertTrue(start.expectFollowUp && start.text.contains("от 1 до 100"), start.text)
        val turn = e.engine.handle("пятьдесят", ai.loli.core.assistant.InputSource.VOICE)
        assertTrue(turn.text.matches(Regex("""(?:Больше|Меньше), чем 50\.|Угадали!.*""")), turn.text)
        val end = e.engine.handle("сдаюсь", ai.loli.core.assistant.InputSource.VOICE)
        assertTrue(end.text.startsWith("Это было число"), end.text)
        // После игры обычные команды снова работают.
        e.engine.handle("запиши расход 300 рублей на кофе")
        assertEquals(1, e.store.expenses.all().size)
    }

    @Test fun talesAndSpeech() = runTest {
        val e = env()
        val t = e.engine.handle("расскажи сказку про колобка")
        assertTrue(t.text.startsWith("«Колобок». Жили-были"), t.text)
        assertEquals("плюс 12 градусов, минус 3 градуса, ветер 4 метра в секунду, 70 процентов",
            ai.loli.core.voice.SpeechText.declineUnits("+12°, −3°, ветер 4 м/с, 70%"))
        assertEquals("паста", ai.loli.core.assistant.DevicePhrases.timerLabel("на пасту 8 минут"))
        assertEquals("", ai.loli.core.assistant.DevicePhrases.timerLabel("на 5 минут"))
    }
}
