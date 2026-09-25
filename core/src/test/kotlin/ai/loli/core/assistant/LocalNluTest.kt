package ai.loli.core.assistant

import ai.loli.core.finance.PeriodPreset
import ai.loli.core.finance.ReportMode
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Понимание свободных формулировок без AI. Время: пятница 25.09.2026, 12:00 МСК. */
class LocalNluTest {
    private val p = LocalCommandParser()
    private val now = Instant.parse("2026-09-25T09:00:00Z")
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 9, 25)

    private fun plan(s: String) = assertNotNull(p.parse(s, now, zone), "не распознано: $s")
    private fun one(s: String) = plan(s).actions.also { assertEquals(1, it.size, "ожидалось одно действие: $s → $it") }.single()

    @Test fun freeFormExpenses() {
        fun exp(s: String) = assertIs<AssistantAction.CreateExpense>(one(s), s)
        exp("закинула в продуктовый 850").let { assertEquals(85_000, it.amountMinor); assertEquals("Продукты", it.category) }
        exp("отдала за такси 450 рублей").let { assertEquals("Транспорт", it.category) }
        exp("кофе 250 рублей").let { assertEquals("Кафе и рестораны", it.category) }
        exp("обед в столовой обошёлся в 390").let { assertEquals(39_000, it.amountMinor) }
        exp("вчера ушло полтинник на проезд").let { assertEquals(5_000, it.amountMinor); assertEquals(today.minusDays(1), it.date) }
        exp("купила кроссовки за 5 косарей").let { assertEquals(500_000, it.amountMinor); assertEquals("Одежда и обувь", it.category) }
        exp("заказала на озоне на 1200").let { assertEquals("Маркетплейсы", it.category) }
        exp("расход 300 аптека").let { assertEquals("Здоровье", it.category) }
        exp("потратила 20 долларов на подписку").let { assertEquals("USD", it.currency); assertEquals("Подписки", it.category) }
    }

    @Test fun expenseWithoutAmountAsksAmount() {
        val plan = plan("потратила на продукты")
        val slot = assertIs<SlotRequest.ExpenseAmount>(plan.slot)
        assertEquals("Продукты", slot.category)
        val filled = assertNotNull(p.fillSlot(slot, "пятьсот", now, zone))
        assertEquals(50_000, assertIs<AssistantAction.CreateExpense>(filled.actions.single()).amountMinor)
    }

    @Test fun compoundCommands() {
        val actions = plan("запиши расход 500 на такси и напомни завтра в 9 позвонить маме").actions
        assertEquals(2, actions.size)
        assertIs<AssistantAction.CreateExpense>(actions[0])
        assertEquals("Позвонить маме", assertIs<AssistantAction.CreateReminder>(actions[1]).text)

        val two = plan("потратила 300 на кофе и 500 на такси").actions
        assertEquals(listOf(30_000L, 50_000L), two.map { (it as AssistantAction.CreateExpense).amountMinor })
        assertEquals("Транспорт", (two[1] as AssistantAction.CreateExpense).category)

        val ref = plan("добавь задачу оплатить интернет и напомни об этом завтра в 10").actions
        assertEquals("Оплатить интернет", assertIs<AssistantAction.CreateReminder>(ref[1]).text)

        // «хлеб и молоко» — не две команды
        assertEquals("Купить хлеб и молоко", assertIs<AssistantAction.CreateTask>(one("добавь задачу купить хлеб и молоко")).title)
    }

    @Test fun implicitTasksAndReminders() {
        assertEquals("Позвонить в банк", assertIs<AssistantAction.CreateTask>(one("мне нужно позвонить в банк")).title)
        val due = assertIs<AssistantAction.CreateTask>(one("надо завтра отвезти документы"))
        assertEquals(today.plusDays(1), due.dueDate)
        assertEquals("Отвезти документы", due.title)
        assertIs<AssistantAction.CreateReminder>(one("не забыть в 18:00 забрать посылку")).let { assertEquals("Забрать посылку", it.text) }
        assertIs<AssistantAction.CreateReminder>(one("разбуди меня завтра в 7 утра")).let { assertEquals(Instant.parse("2026-09-26T04:00:00Z"), it.triggerAt) }
        assertIs<AssistantAction.CreateReminder>(one("не дай мне забыть через 20 минут выключить плиту")).let { assertEquals(now.plusSeconds(1200), it.triggerAt) }
        assertIs<AssistantAction.CreateReminder>(one("напомни в обед поесть")).let { assertEquals(Instant.parse("2026-09-25T10:00:00Z"), it.triggerAt) }
    }

    @Test fun reminderTimeSlot() {
        val slot = assertIs<SlotRequest.ReminderTime>(plan("напомни полить цветы").slot)
        val filled = assertNotNull(p.fillSlot(slot, "завтра в 8 утра", now, zone))
        assertEquals(Instant.parse("2026-09-26T05:00:00Z"), assertIs<AssistantAction.CreateReminder>(filled.actions.single()).triggerAt)
        assertNull(p.fillSlot(slot, "добавь задачу помыть машину", now, zone), "новая команда вместо ответа")
    }

    @Test fun agenda() {
        assertEquals(today, assertIs<AssistantAction.Agenda>(one("что у меня на сегодня")).date)
        assertEquals(today.plusDays(1), assertIs<AssistantAction.Agenda>(one("какие планы на завтра")).date)
        assertEquals(LocalDate.of(2026, 9, 28), assertIs<AssistantAction.Agenda>(one("что у меня в понедельник")).date)
        val morning = plan("доброе утро")
        assertEquals("Доброе утро!", morning.preface)
        assertIs<AssistantAction.Agenda>(morning.actions.single())
    }

    @Test fun expenseQuestionsVariety() {
        assertEquals(ReportMode.BY_CATEGORY, assertIs<AssistantAction.QueryExpenses>(one("на что я больше всего трачу")).mode)
        val taxi = assertIs<AssistantAction.QueryExpenses>(one("сколько ушло на такси за неделю"))
        assertEquals("Транспорт", taxi.category)
        assertEquals(PeriodPreset.LAST_7_DAYS, taxi.preset)
        val august = assertIs<AssistantAction.QueryExpenses>(one("сколько я потратила в августе"))
        assertEquals(LocalDate.of(2026, 8, 1), august.from)
        assertEquals(LocalDate.of(2026, 8, 31), august.to)
        val tenDays = assertIs<AssistantAction.QueryExpenses>(one("сколько я потратил за 10 дней"))
        assertEquals(today.minusDays(9), tenDays.from)
    }

    @Test fun undoAndCorrections() {
        assertNull(assertIs<AssistantAction.DeleteLast>(one("отмени последнее")).type)
        assertEquals(RecordType.EXPENSE, assertIs<AssistantAction.DeleteLast>(one("удали последний расход")).type)
        assertEquals(90_000, assertIs<AssistantAction.UpdateLastExpense>(one("исправь последний расход на 900")).amountMinor)
        assertEquals(95_000, assertIs<AssistantAction.UpdateLastExpense>(one("не 850, а 950")).amountMinor)
        assertEquals("Кафе и рестораны", assertIs<AssistantAction.UpdateLastExpense>(one("поменяй категорию на кафе")).category)
    }

    @Test fun memoryFactsAndQuestions() {
        assertEquals("person", assertIs<AssistantAction.Remember>(one("мою маму зовут Ольга")).category)
        assertEquals("preference", assertIs<AssistantAction.Remember>(one("я не ем мясо")).category)
        assertEquals("Меня зовут Аня", assertIs<AssistantAction.Remember>(one("меня зовут Аня")).content)
        assertIs<AssistantAction.Remember>(one("имей в виду, что у меня аллергия на орехи"))
        assertEquals("как меня зовут", assertIs<AssistantAction.QueryMemories>(one("как меня зовут?")).query)
        assertIs<AssistantAction.QueryMemories>(one("когда у мамы день рождения"))
        assertNull(assertIs<AssistantAction.QueryMemories>(one("что ты обо мне знаешь")).query)
        assertIs<AssistantAction.ForgetMemory>(one("забудь про аллергию"))
    }

    @Test fun listsAndAppends() {
        val list = assertIs<AssistantAction.CreateNote>(one("список покупок: молоко, хлеб и сыр"))
        assertEquals("Список покупок", list.title)
        assertEquals("• молоко\n• хлеб\n• сыр", list.content)
        val tasks = plan("список дел: помыть машину, оплатить свет").actions
        assertEquals(2, tasks.size)
        val add = assertIs<AssistantAction.AppendNote>(one("добавь молоко в список покупок"))
        assertEquals("молоко", add.content)
        assertEquals("список покупок", add.target.query)
        val toIdea = assertIs<AssistantAction.AppendNote>(one("запиши штрихкоды в идею про склад"))
        assertEquals(setOf(RecordType.IDEA), toIdea.target.types)
        assertEquals("склад", toIdea.target.query)
        val there = assertIs<AssistantAction.AppendNote>(one("там нужно учитывать остатки"))
        assertEquals("Нужно учитывать остатки", there.content)
        assertTrue(there.target.isEmpty)
        // «ещё напомни…» — новая команда, а не дополнение
        assertIs<AssistantAction.CreateReminder>(one("ещё напомни через час проверить почту"))
    }

    @Test fun ideasVariety() {
        assertEquals(NoteKind.IDEA, assertIs<AssistantAction.CreateNote>(one("есть мысль сделать подкаст про финансы")).kind)
        assertEquals("Что если продавать варенье онлайн", assertIs<AssistantAction.CreateNote>(one("а что если продавать варенье онлайн")).title)
        assertEquals(NoteKind.IDEA, assertIs<AssistantAction.CreateNote>(one("придумала название для кафе: Лампа")).kind)
    }

    @Test fun brainstormMode() {
        val plan = plan("давай придумаем приложение для склада")
        assertTrue(plan.expectFollowUp)
        assertEquals("Приложение для склада", assertIs<AssistantAction.CreateNote>(plan.actions.single()).title)
        val existing = plan("давай подумаем над моей идеей про холодильник")
        assertTrue(existing.expectFollowUp)
        assertIs<AssistantAction.Search>(existing.actions.single())
    }

    @Test fun smallTalkAndUtilities() {
        assertEquals("Сейчас 12:00.", plan("который час").reply)
        assertTrue(plan("какое сегодня число").reply.contains("25 сентября 2026"))
        assertTrue(plan("что ты умеешь").reply.contains("без интернета"))
        assertEquals("Получается 300.", plan("сколько будет 15 процентов от 2000").reply)
        assertEquals("Получается 1000.", plan("сколько будет 250 умножить на 4").reply)
        assertEquals("Получается 1000.", plan("(1200+800)/2").reply)
        assertTrue(plan("расскажи анекдот").reply.isNotBlank())
    }

    @Test fun questionsSearchNotes() {
        val q = assertIs<AssistantAction.Search>(one("что я хотела подарить маме?"))
        assertTrue(q.query.contains("подарить"))
        assertIs<AssistantAction.Search>(one("где рецепт борща"))
    }

    @Test fun timeParsingExtras() {
        val weekend = assertIs<AssistantAction.CreateTask>(one("на выходных надо разобрать шкаф"))
        assertEquals(LocalDate.of(2026, 9, 26), weekend.dueDate)
        val task = assertIs<AssistantAction.CreateTask>(one("добавь задачу после обеда созвониться с Петей"))
        assertEquals(LocalTime.of(14, 0), task.dueTime)
    }

    @Test fun politeWrappers() {
        assertIs<AssistantAction.CreateTask>(one("слушай, можешь добавить задачу купить корм коту, пожалуйста").let { it })
    }
}
