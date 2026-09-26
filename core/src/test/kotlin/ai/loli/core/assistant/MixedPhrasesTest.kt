package ai.loli.core.assistant

import ai.loli.core.finance.ReportMode
import ai.loli.core.model.RecordType
import ai.loli.core.nlp.ExpenseCategories
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Смешанные и разговорные фразы: одна фраза — несколько записей разных типов. */
class MixedPhrasesTest {
    private val now = Instant.parse("2026-09-25T09:00:00Z") // пятница
    private val zone = ZoneId.of("Europe/Moscow")
    private val parser = LocalCommandParser()
    private val monday = LocalDate.of(2026, 9, 28)
    private val tomorrow = LocalDate.of(2026, 9, 26)

    private fun actions(phrase: String) = parser.parse(phrase, now, zone)?.actions.orEmpty()
    private fun reply(phrase: String) = parser.parse(phrase, now, zone)?.reply.orEmpty()

    @Test fun expenseAndTasksInOnePhrase() {
        val a = actions("Потратил 200 рублей на кофе, нужно сходить в зал в понедельник, забрать дочь из садика")
        assertEquals(3, a.size)
        val e = assertIs<AssistantAction.CreateExpense>(a[0])
        assertEquals(20000, e.amountMinor)
        assertEquals(AssistantAction.CreateTask("Сходить в зал", "", monday, null), a[1])
        // Дата стоит в конце первой задачи — на вторую её не переносим.
        assertEquals(AssistantAction.CreateTask("Забрать дочь из садика", "", null, null), a[2])
    }

    @Test fun leadingDateAppliesToWholeList() {
        val a = actions("завтра купить продукты и забрать посылку")
        assertEquals(listOf("Купить продукты", "Забрать посылку"), a.map { (it as AssistantAction.CreateTask).title })
        assertTrue(a.all { (it as AssistantAction.CreateTask).dueDate == tomorrow })
    }

    @Test fun introBecomesTaskDetails() {
        assertEquals(AssistantAction.CreateTask("Купить лекарства", "Дочь заболела", null, null), actions("дочь заболела, нужно купить лекарства").single())
        val a = actions("дочь заболела, завтра позвонить врачу и написать учительнице")
        assertEquals(AssistantAction.CreateTask("Позвонить врачу", "Дочь заболела", tomorrow, null), a[0])
        assertEquals(AssistantAction.CreateTask("Написать учительнице", "", tomorrow, null), a[1])
    }

    @Test fun openAppThenMessage() {
        val a = actions("открой телеграм и напиши Саше привет")
        assertEquals(AssistantAction.Device(DeviceCommand.OpenApp("телеграм")), a[0])
        assertEquals(AssistantAction.Device(DeviceCommand.Message("Саше", "привет")), a[1])
    }

    @Test fun namesKeepSpokenCase() {
        assertEquals(AssistantAction.Device(DeviceCommand.Call("Маме")), actions("позвони Маме").single())
        val ev = (actions("добавь в календарь день рождения Пети в субботу").single() as AssistantAction.Device).command
        assertEquals("День рождения Пети", (ev as DeviceCommand.CalendarEvent).title)
    }

    @Test fun reminderWithDontForget() {
        val r = assertIs<AssistantAction.CreateReminder>(actions("не забудь напомнить мне завтра про встречу").single())
        assertEquals("Встречу", r.text)
    }

    @Test fun onlineQuestionsOpenSearch() {
        assertIs<DeviceCommand.WebSearch>((actions("какая погода завтра").single() as AssistantAction.Device).command)
        assertIs<DeviceCommand.WebSearch>((actions("курс доллара").single() as AssistantAction.Device).command)
    }

    @Test fun worldTimeAndLocalTime() {
        assertTrue(reply("время в Нью-Йорке").startsWith("В Нью-Йорке сейчас"))
        assertTrue(reply("сколько времени в Токио").startsWith("В Токио сейчас"))
        assertTrue(reply("сколько времени").startsWith("Сейчас"))
    }

    @Test fun feelingsGetAnswerNotTask() {
        listOf("я устал", "мне грустно", "сегодня был тяжёлый день").forEach { p ->
            val plan = parser.parse(p, now, zone)!!
            assertTrue(plan.actions.isEmpty(), p)
            assertTrue(plan.reply.isNotBlank(), p)
        }
    }

    @Test fun debtsKeepVerbForm() {
        val a = assertIs<AssistantAction.AppendNote>(actions("Оля должна мне 700").single())
        assertTrue(a.content.startsWith("Оля должна мне"))
    }

    @Test fun expenseReports() {
        assertEquals(ReportMode.TOP, (actions("какой расход был самый большой").single() as AssistantAction.QueryExpenses).mode)
        val avg = actions("сколько в среднем я трачу в день").single() as AssistantAction.QueryExpenses
        assertEquals(ReportMode.AVERAGE, avg.mode)
        assertNull(avg.category)
    }

    @Test fun shoppingListQueryAndAlarmsOff() {
        val s = assertIs<AssistantAction.Search>(actions("что в списке покупок").single())
        assertEquals("список покупок", s.query)
        assertEquals(setOf(RecordType.NOTE), s.types)
        assertEquals(AssistantAction.Device(DeviceCommand.ShowAlarms), actions("выключи будильник").single())
    }

    @Test fun incomeIsNotSpending() {
        val e = assertIs<AssistantAction.CreateExpense>(actions("зарплата пришла 80000").single())
        assertEquals(ExpenseCategories.INCOME, e.category)
    }

    @Test fun nounsAreNotVerbs() {
        assertNull(parser.parse("дочь", now, zone)?.actions?.firstOrNull { it is AssistantAction.CreateTask })
    }
}

class SpeechFixesTest {
    @Test fun fixesName() {
        assertEquals("Лоли, запиши расход 500", ai.loli.core.voice.SpeechFixes.apply("Лали, запиши расход 500"))
        assertEquals("Лоли запиши", ai.loli.core.voice.SpeechFixes.apply("лоле запиши"))
        assertEquals("лапти купить", ai.loli.core.voice.SpeechFixes.apply("лапти купить"))
    }

    @Test fun fixesCommonMishearings() {
        assertEquals("потратил 5 тысяч на ремонт", ai.loli.core.voice.SpeechFixes.apply("потратил 5 тыщ на ремонт"))
        assertEquals("напомни через полчаса", ai.loli.core.voice.SpeechFixes.apply("на помни через пол часа"))
        assertEquals("потратила 300 рублей", ai.loli.core.voice.SpeechFixes.apply("потратила 300 руб"))
        assertEquals("запиши заметку", ai.loli.core.voice.SpeechFixes.apply("за пиши заметку"))
    }

    @Test fun detectsUnfinishedPhrase() {
        assertTrue(ai.loli.core.voice.SpeechFixes.looksUnfinished("купи хлеб и"))
        assertTrue(ai.loli.core.voice.SpeechFixes.looksUnfinished("напомни мне завтра в"))
        assertTrue(!ai.loli.core.voice.SpeechFixes.looksUnfinished("купи хлеб и молоко"))
    }
}

class DictionaryTest {
    private val now = java.time.Instant.parse("2026-09-25T09:00:00Z")
    private val zone = java.time.ZoneId.of("Europe/Moscow")
    private val parser = LocalCommandParser()
    private fun category(p: String) = (parser.parse(p, now, zone)!!.actions.single() as AssistantAction.CreateExpense).category

    @Test fun newCategories() {
        assertEquals("Переводы", category("перевёл маме 5000"))
        assertEquals("Автомобиль", category("шиномонтаж 3000"))
        assertEquals("Налоги и штрафы", category("заплатил штраф 500"))
        assertEquals("Связь и интернет", category("заплатил за мтс 600"))
        assertEquals("Подписки", category("кинопоиск 299 рублей"))
        assertEquals("Развлечения", category("потратила 1500 на зоопарк"))
        assertEquals("Дом и ЖКХ", category("купила в икеа посуду за 2000"))
        assertEquals("Продукты", category("пятёрочка 850"))
    }

    @Test fun noFalseMatches() {
        assertTrue(ai.loli.core.nlp.ExpenseCategories.categorize("спасибо") != "Красота")
        assertTrue(ai.loli.core.nlp.ExpenseCategories.categorize("стиральная машина") != "Автомобиль")
    }
}
