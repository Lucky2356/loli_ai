package ai.loli.core.finance

import ai.loli.core.TestEnv
import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.model.Expense
import ai.loli.core.skills.SkillCommand
import ai.loli.core.skills.SkillHost
import ai.loli.core.skills.SkillOutcome
import ai.loli.core.skills.SkillPhrases
import ai.loli.core.skills.Skills
import ai.loli.core.util.FixedTimeSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Связь с Финансовым помощником. Сегодня пятница 25.09.2026, 12:00 МСК. */
class FinanceLinkTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 9, 25)

    /** Помощник на телефоне: что получил и что уже знает. */
    private class FakeSink(var on: Boolean = true, var broken: Boolean = false) : FinanceSink {
        val sent = mutableListOf<FinanceItem>()
        val ids = mutableSetOf<String>()
        override fun active() = on
        override fun known(id: String) = id in ids
        override suspend fun send(item: FinanceItem) {
            if (broken) error("помощник не отвечает")
            sent += item
            if (item.op == FinanceItem.Op.DELETE) ids -= item.id else ids += item.id
        }
    }

    // ------------------------------------------------------------------ Передача трат

    @Test fun spokenExpenseEditAndUndoReachTheSameOperation() = runTest {
        val sink = FakeSink()
        val e = TestEnv(wrapExpenses = { FinanceLinkedExpenses(it, sink) }).apply { settings = AssistantSettings(useAI = false) }

        e.engine.handle("потратила 850 на продукты")
        val created = e.store.expenses.all().single()
        assertEquals(1, sink.sent.size)
        val first = sink.sent.single()
        assertEquals(created.id, first.id)
        assertEquals(FinanceItem.Op.UPSERT, first.op)
        assertEquals(85_000, first.amountMinor)
        assertEquals("Продукты", first.category)
        assertEquals(today, first.date)

        e.engine.handle("исправь последний расход на 900")
        assertEquals(2, sink.sent.size)
        assertEquals(created.id, sink.sent.last().id, "правка — та же трата, а не новая")
        assertEquals(90_000, sink.sent.last().amountMinor)

        val r = e.engine.handle("отмени последнее")
        if (r.awaitingConfirmation) e.engine.handle("да")
        assertEquals(0, e.store.expenses.all().size, r.text)
        assertEquals(FinanceItem.deleted(created.id), sink.sent.last())
    }

    @Test fun incomeGoesAsIncome() = runTest {
        val sink = FakeSink()
        val e = TestEnv(wrapExpenses = { FinanceLinkedExpenses(it, sink) }).apply { settings = AssistantSettings(useAI = false) }
        e.engine.handle("зарплата пришла 80000")
        val item = sink.sent.single()
        assertTrue(item.income)
        val json = Json.parseToJsonElement(item.toJson()).jsonObject
        assertEquals("INCOME", json["type"]!!.jsonPrimitive.content)
        assertEquals(8_000_000, json["amountMinor"]!!.jsonPrimitive.content.toLong())
        assertEquals("2026-09-25", json["date"]!!.jsonPrimitive.content)
    }

    @Test fun deleteJsonCarriesOnlyTheNumber() {
        val json = Json.parseToJsonElement(FinanceItem.deleted("abc-1").toJson()).jsonObject
        assertEquals(setOf("id", "op"), json.keys)
        assertEquals("delete", json["op"]!!.jsonPrimitive.content)
    }

    @Test fun linkOffMeansNothingLeaves() = runTest {
        val sink = FakeSink(on = false)
        val e = TestEnv(wrapExpenses = { FinanceLinkedExpenses(it, sink) }).apply { settings = AssistantSettings(useAI = false) }
        e.engine.handle("потратила 300 на такси")
        assertEquals(1, e.store.expenses.all().size)
        assertTrue(sink.sent.isEmpty())
    }

    @Test fun oldExpensesAreNotPushedByAnEdit() = runTest {
        val sink = FakeSink(on = false)
        val e = TestEnv(wrapExpenses = { FinanceLinkedExpenses(it, sink) })
        val old = e.expenses.create(50_000, "RUB", "Кафе и рестораны", "обед", today)
        sink.on = true
        e.expenses.update(old.copy(amountMinor = 60_000))
        e.expenses.delete(old.id)
        assertTrue(sink.sent.isEmpty(), "трата до включения связи не должна появиться в помощнике")
    }

    @Test fun brokenLinkNeverBreaksLoli() = runTest {
        val sink = FakeSink(broken = true)
        val e = TestEnv(wrapExpenses = { FinanceLinkedExpenses(it, sink) }).apply { settings = AssistantSettings(useAI = false) }
        val reply = e.engine.handle("потратила 300 на такси")
        assertEquals(1, e.store.expenses.all().size, reply.text)
        assertTrue(!reply.text.contains("Не получилось"), reply.text)
    }

    // ------------------------------------------------------------------ Сводка

    private fun summaryJson(
        updatedAt: String = "2026-09-25T08:00:00Z",
        today: String? = """{"canSpend":1200.5,"perDay":2000,"spent":799.5,"status":"ok"}""",
        month: String = "2026-09",
        payday: String? = """{"date":"2026-10-05","daysLeft":10,"free":15000,"perDay":1500,"status":"ok"}""",
    ) = """{"v":1,"updatedAt":"$updatedAt","currency":"RUB","today":${today ?: "null"},
        "month":{"month":"$month","income":90000,"expense":41234.56,"daysLeft":6},
        "categories":[{"name":"Продукты","spent":8000,"limit":12000},{"name":"Рестораны","spent":5200,"limit":5000},{"name":"Такси","spent":900,"limit":null}],
        "balance":{"total":154321.1,"accounts":[{"name":"Т-Банк","balance":100000,"currency":"RUB"},{"name":"Наличные","balance":4321.1,"currency":"RUB"},{"name":"Сбер","balance":50000,"currency":"RUB"}]},
        "payday":${payday ?: "null"}}"""

    private fun ask(json: String, ask: FinanceAsk, subject: String? = null, pending: List<Expense> = emptyList()) =
        FinanceAnswers.answer(assertNotNull(FinanceSummary.parse(json)), ask, subject, pending, today, zone)

    private fun spent(minor: Long, category: String = "Продукты", day: LocalDate = today, id: String = "p$minor") =
        Expense(id, minor, "RUB", category, "", day, Instant.parse("2026-09-25T08:30:00Z"), Instant.parse("2026-09-25T08:30:00Z"))

    @Test fun parsesContractV1AndRejectsOthers() {
        val s = assertNotNull(FinanceSummary.parse(summaryJson()))
        assertEquals(120_050, s.today!!.canSpend)
        assertEquals(4_123_456, s.month.expense)
        assertEquals(null, s.categories.last().limit)
        assertEquals(LocalDate.of(2026, 10, 5), s.payday!!.date)
        assertNull(FinanceSummary.parse(summaryJson().replace("\"v\":1", "\"v\":2")))
        assertNull(FinanceSummary.parse("не json"))
        assertNull(FinanceSummary.parse(null))
    }

    @Test fun todayCountsWhatLoliSentAfterTheSummary() {
        assertEquals(
            "Сегодня можно потратить ещё 1 200,5 ₽ — уже потрачено 799,5 ₽ из 2 000 ₽ на день.",
            ask(summaryJson(), FinanceAsk.TODAY),
        )
        val withPending = ask(summaryJson(), FinanceAsk.TODAY, pending = listOf(spent(50_000)))!!
        assertTrue(withPending.startsWith("Сегодня можно потратить ещё 700,5 ₽ — уже потрачено 1 299,5 ₽"), withPending)
        assertTrue(withPending.endsWith("Учла и 1 трату из Лоли, которых помощник ещё не видел."), withPending)
        val over = ask(summaryJson(today = """{"canSpend":0,"perDay":2000,"spent":2500,"status":"over"}"""), FinanceAsk.TODAY)!!
        assertTrue(over.startsWith("Сегодня уже перерасход на 500 ₽"), over)
    }

    @Test fun yesterdaysSummaryIsNotPassedOffAsToday() {
        val stale = ask(summaryJson(updatedAt = "2026-09-24T18:00:00Z"), FinanceAsk.TODAY, pending = listOf(spent(30_000)))!!
        assertTrue(stale.startsWith("Свежих цифр на сегодня нет: Финансовый помощник последний раз открывали вчера."), stale)
        assertTrue(stale.contains("2 000 ₽ в день") && stale.contains("Сегодня вы уже потратили 300 ₽"), stale)
        // 23:30 по Москве 24-го — это ещё 24-е, хотя по UTC уже 20:30.
        val lateEvening = ask(summaryJson(updatedAt = "2026-09-24T20:30:00Z"), FinanceAsk.TODAY)!!
        assertTrue(lateEvening.startsWith("Свежих цифр"), lateEvening)
        // 00:30 по Москве 25-го — уже сегодня, хотя по UTC ещё 24-е.
        val earlyMorning = ask(summaryJson(updatedAt = "2026-09-24T21:30:00Z"), FinanceAsk.TODAY)!!
        assertTrue(earlyMorning.startsWith("Сегодня можно"), earlyMorning)
    }

    @Test fun noBudgetSaysSo() {
        assertTrue(ask(summaryJson(today = null), FinanceAsk.TODAY)!!.contains("не задан бюджет"))
    }

    @Test fun payday() {
        assertEquals(
            "До зарплаты через 10 дней хватает: свободно 15 000 ₽ — примерно 1 500 ₽ в день.",
            ask(summaryJson(), FinanceAsk.PAYDAY),
        )
        val short = ask(summaryJson(), FinanceAsk.PAYDAY, pending = listOf(spent(1_600_000, "Электроника")))!!
        assertTrue(short.startsWith("До зарплаты через 10 дней не хватает 1 000 ₽"), short)
        assertTrue(ask(summaryJson(payday = null), FinanceAsk.PAYDAY)!!.contains("не указан день зарплаты"))
        val passed = ask(summaryJson(payday = """{"date":"2026-09-20","daysLeft":0,"free":100,"perDay":100,"status":"ok"}"""), FinanceAsk.PAYDAY)!!
        assertTrue(passed.startsWith("Зарплата по плану была"), passed)
    }

    @Test fun balanceAndAccounts() {
        val all = ask(summaryJson(), FinanceAsk.BALANCE)!!
        assertTrue(all.startsWith("Всего на счетах 154 321,1 ₽."), all)
        assertTrue(all.contains("«Т-Банк» 100 000 ₽, «Сбер» 50 000 ₽"), all)
        assertEquals("На «Т-Банк» — 100 000 ₽.", ask(summaryJson(), FinanceAsk.BALANCE, "т-банке"))
        assertEquals("На «Сбер» — 50 000 ₽.", ask(summaryJson(), FinanceAsk.BALANCE, "сбере"))
        assertNull(ask(summaryJson(), FinanceAsk.BALANCE, "часах"), "не счёт — пусть отвечает Лоли")
    }

    @Test fun categoriesByEverydayNames() {
        assertEquals("На «Продукты» осталось 4 000 ₽ из 12 000 ₽.", ask(summaryJson(), FinanceAsk.CATEGORY, "продукты"))
        val restaurants = ask(summaryJson(), FinanceAsk.CATEGORY, "рестораны")!!
        assertTrue(restaurants.startsWith("На «Рестораны» лимит превышен на 200 ₽"), restaurants)
        assertTrue(ask(summaryJson(), FinanceAsk.CATEGORY, "такси")!!.contains("Лимита на эту статью нет"))
        assertNull(ask(summaryJson(), FinanceAsk.CATEGORY, "космос"))
        // Трата, сказанная Лоли после сводки, уже уменьшает остаток.
        val after = ask(summaryJson(), FinanceAsk.CATEGORY, "продукты", listOf(spent(100_000)))!!
        assertTrue(after.startsWith("На «Продукты» осталось 3 000 ₽"), after)
        // Сводка прошлого месяца — цифры месяца не выдаём за нынешние.
        assertTrue(ask(summaryJson(month = "2026-08"), FinanceAsk.CATEGORY, "продукты")!!.startsWith("Свежих цифр за этот месяц нет"))
    }

    @Test fun sameNameSpokenDifferently() {
        assertTrue(FinanceAnswers.same("кафе", "Кафе и рестораны"))
        assertTrue(FinanceAnswers.same("ресторанах", "Рестораны"))
        assertTrue(FinanceAnswers.same("жкх", "Дом и ЖКХ"))
        assertTrue(FinanceAnswers.same("т-банке", "Т-Банк"))
        assertTrue(!FinanceAnswers.same("продукты", "Рестораны"))
        assertTrue(!FinanceAnswers.same("", "Рестораны"))
        assertTrue(FinanceAnswers.same("вб", "ВБ"))
        assertTrue(!FinanceAnswers.same("т-банке", "Тинькофф"))
    }

    // ------------------------------------------------------------------ Фразы

    @Test fun moneyQuestionsAreRecognised() {
        fun cmd(text: String) = SkillPhrases.parse(text, today)
        mapOf(
            "сколько можно тратить сегодня" to FinanceAsk.TODAY,
            "сколько я могу потратить сегодня" to FinanceAsk.TODAY,
            "сколько мне ещё можно потратить" to FinanceAsk.TODAY,
            "Лоли, сколько осталось на сегодня?" to FinanceAsk.TODAY,
            "можно ли ещё сегодня тратить" to FinanceAsk.TODAY,
            "хватит ли мне денег до зарплаты" to FinanceAsk.PAYDAY,
            "а хватит до зарплаты?" to FinanceAsk.PAYDAY,
            "сколько дней до зарплаты" to FinanceAsk.PAYDAY,
            "сколько у меня денег" to FinanceAsk.BALANCE,
            "какой у меня баланс" to FinanceAsk.BALANCE,
            "сколько денег на счетах" to FinanceAsk.BALANCE,
            "как у меня с деньгами" to FinanceAsk.OVERVIEW,
        ).forEach { (text, kind) ->
            val c = assertIs<SkillCommand.Finance>(cmd(text.removePrefix("Лоли, ")), text)
            assertEquals(kind, c.ask, text)
            assertNull(c.subject, text)
        }
        assertEquals(SkillCommand.Finance(FinanceAsk.CATEGORY, "продукты"), cmd("сколько осталось на продукты"))
        assertEquals(SkillCommand.Finance(FinanceAsk.CATEGORY, "кафе"), cmd("сколько ещё можно потратить на кафе в этом месяце"))
        assertEquals(SkillCommand.Finance(FinanceAsk.CATEGORY, "такси"), cmd("какой лимит на такси"))
        assertEquals(SkillCommand.Finance(FinanceAsk.BALANCE, "т-банке"), cmd("сколько денег на т-банке"))
        assertEquals(SkillCommand.Finance(FinanceAsk.BALANCE, "сбера"), cmd("сколько на карте сбера"))
    }

    @Test fun lolisOwnPhrasesAreLeftAlone() {
        listOf(
            "сколько я потратил сегодня", "сколько я потратила на продукты в этом месяце", "потратила 500 на продукты",
            "запиши расход 300", "сколько стоит доллар", "поставь лимит на продукты 20000", "сколько времени",
        ).forEach { text -> assertTrue(SkillPhrases.parse(text, today) !is SkillCommand.Finance, text) }
    }

    // ------------------------------------------------------------------ Навык целиком

    private class Host(var json: String?, val pending: List<Expense> = emptyList()) : SkillHost {
        override suspend fun financeSummary() = json
        override suspend fun financePending(since: Instant) = pending.filter { it.createdAt.isAfter(since) }
    }

    private val time = FixedTimeSource(Instant.parse("2026-09-25T09:00:00Z"), zone)

    @Test fun answersOnlyWhenTheAssistantShares() = runTest {
        val host = Host(summaryJson(), listOf(spent(20_000)))
        val skills = Skills(host, null, time)
        val out = assertIs<SkillOutcome.Say>(skills.handle("сколько можно тратить сегодня", AssistantSettings(), null))
        assertTrue(out.text.startsWith("Сегодня можно потратить ещё 1 000,5 ₽"), out.text)
        assertTrue(out.sensitive, "деньги не пересказываем облачному AI")
        // Сводки нет (помощника нет, связь выключена) — навык молчит, отвечает сама Лоли.
        host.json = null
        assertNull(skills.handle("сколько можно тратить сегодня", AssistantSettings(), null))
        assertNull(Skills(host, null, time).handle("сколько денег на т-банке", AssistantSettings(), null))
    }

    @Test fun lockedPhoneHidesMoney() = runTest {
        val skills = Skills(Host(summaryJson()), null, time)
        val out = assertIs<SkillOutcome.Say>(skills.handle("сколько у меня денег", AssistantSettings(locked = true), null))
        assertTrue(out.text.startsWith("Разблокируйте телефон"), out.text)
        assertTrue(!out.text.contains("154"), out.text)
    }

    @Test fun unknownCategoryFallsBackToLoli() = runTest {
        val skills = Skills(Host(summaryJson()), null, time)
        assertNull(skills.handle("сколько осталось на космос", AssistantSettings(), null))
    }

    @Test fun engineStillReportsLolisOwnSpending() = runTest {
        val e = TestEnv(skillsFactory = { t -> Skills(Host(summaryJson()), null, t) }).apply { settings = AssistantSettings(useAI = false) }
        e.engine.handle("потратила 500 рублей на такси")
        val own = e.engine.handle("сколько я потратила сегодня").text
        assertTrue(own.contains("500"), own)
        // Сразу после вопроса о своих тратах: «а …» — новый вопрос, а не уточнение к прошлому.
        val money = e.engine.handle("а сколько можно тратить сегодня?").text
        assertTrue(money.startsWith("Сегодня можно потратить ещё"), money)
        val payday = e.engine.handle("а хватит до зарплаты?").text
        assertTrue(payday.startsWith("До зарплаты через 10 дней хватает"), payday)
    }
}
