package ai.loli.core.assistant

import ai.loli.core.TestEnv
import ai.loli.core.data.LocalStore
import ai.loli.core.db.LoliDatabase
import ai.loli.core.model.NoteKind
import ai.loli.core.model.Recurrence
import ai.loli.core.nlp.Calculator
import ai.loli.core.nlp.RuDateTimeParser
import ai.loli.core.util.FixedTimeSource
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Регрессионные тесты на найденные при ревью ошибки. */
class RegressionTest {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }
    private val p = LocalCommandParser()
    private val now = Instant.parse("2026-09-25T09:00:00Z") // пт 12:00 МСК
    private val zone = ZoneId.of("Europe/Moscow")
    private fun one(s: String) = assertNotNull(p.parse(s, now, zone), s).actions.single()

    @Test fun newCommandIsNotConfirmation() = runTest {
        val env = env()
        env.store.notes.create(NoteKind.NOTE, "Отпуск в Сочи", "")
        env.store.notes.create(NoteKind.NOTE, "Рецепт борща", "")
        assertTrue(env.engine.handle("удали заметку про отпуск").awaitingConfirmation)
        env.engine.handle("удали заметку про борщ")
        // ничего не удалено без явного «да»; теперь ждём подтверждения про борщ
        assertEquals(2, env.store.notes.all().size)
        env.engine.handle("да")
        assertEquals(listOf("Отпуск в Сочи"), env.store.notes.all().map { it.title })
    }

    @Test fun yesNoAreStrict() {
        assertTrue(LocalCommandParser.isYes("да"))
        assertTrue(LocalCommandParser.isYes("да, удаляй"))
        assertTrue(LocalCommandParser.isYes("конечно"))
        assertTrue(LocalCommandParser.isYes("удали"))
        assertFalse(LocalCommandParser.isYes("удали заметку про борщ"))
        assertFalse(LocalCommandParser.isYes("запиши расход 500"))
        assertFalse(LocalCommandParser.isYes("давай придумаем приложение"))
        assertTrue(LocalCommandParser.isNo("нет"))
        assertTrue(LocalCommandParser.isNo("не надо"))
        assertFalse(LocalCommandParser.isNo("не забудь купить хлеб"))
    }

    @Test fun newCommandDuringChoiceIsNotAnOrdinal() = runTest {
        val env = env()
        env.store.tasks.create("Купить хлеб белый")
        env.store.tasks.create("Купить хлеб черный")
        assertTrue(env.engine.handle("отметь задачу купить хлеб выполненной").awaitingAnswer)
        env.engine.handle("напомни через 2 часа позвонить маме")
        assertTrue(env.store.tasks.all().none { it.done }, "ни одна задача не должна отметиться")
        assertEquals("Позвонить маме", env.store.reminders.all().single().text)
    }

    @Test fun addToTaskListCreatesTask() {
        assertEquals("Позвонить маме", assertIs<AssistantAction.CreateTask>(one("добавь в список дел позвонить маме")).title)
        assertEquals("Купить хлеб", assertIs<AssistantAction.CreateTask>(one("добавь в мои задачи купить хлеб")).title)
    }

    @Test fun taskListQuestionsDoNotCreateTasks() {
        assertIs<AssistantAction.QueryTasks>(one("список дел на сегодня"))
        assertIs<AssistantAction.QueryTasks>(one("список задач на завтра"))
    }

    @Test fun partOfDayBeforeHour() {
        val parser = RuDateTimeParser()
        val today = LocalDate.of(2026, 9, 25)
        assertEquals(LocalTime.of(19, 0), parser.parse("вечером в 7", today).spec.time)
        assertEquals(LocalTime.of(15, 0), parser.parse("днем в 3", today).spec.time)
        assertEquals(LocalTime.of(19, 0), parser.parse("в 7 вечером", today).spec.time)
        val r = assertIs<AssistantAction.CreateReminder>(one("напомни завтра вечером в 7 позвонить маме"))
        assertEquals(Instant.parse("2026-09-26T16:00:00Z"), r.triggerAt)
    }

    @Test fun ambiguousHourPicksNearestFuture() {
        val r = assertIs<AssistantAction.CreateReminder>(one("напомни в 5 позвонить"))
        assertEquals(Instant.parse("2026-09-25T14:00:00Z"), r.triggerAt) // 17:00 сегодня, а не 05:00 завтра
        val morning = assertIs<AssistantAction.CreateReminder>(one("напомни завтра в 9 проверить почту"))
        assertEquals(Instant.parse("2026-09-26T06:00:00Z"), morning.triggerAt)
    }

    @Test fun compoundWithIEshche() {
        val actions = assertNotNull(p.parse("потратила 500 на такси и ещё 300 на кофе", now, zone)).actions
        assertEquals(2, actions.size)
        val coffee = assertIs<AssistantAction.CreateExpense>(actions[1])
        assertEquals(30_000, coffee.amountMinor)
        assertEquals("Кафе и рестораны", coffee.category)
        assertEquals("Транспорт", assertIs<AssistantAction.CreateExpense>(actions[0]).category)
    }

    @Test fun spokenMinutes() {
        val r = assertIs<AssistantAction.CreateReminder>(one("в десять тридцать напомни позвонить"))
        assertEquals("Позвонить", r.text)
        assertEquals(Instant.parse("2026-09-25T19:30:00Z"), r.triggerAt) // в 12:00 «в десять тридцать» — ближайшее: 22:30
        val parser = RuDateTimeParser()
        assertEquals(LocalTime.of(8, 15), parser.parse("в восемь пятнадцать", LocalDate.of(2026, 9, 25)).spec.time)
    }

    @Test fun monthlyReminderKeepsDayOfMonth() = runTest {
        val env = env()
        val jan31 = Instant.parse("2027-01-31T07:00:00Z")
        val r = env.store.reminders.create("Оплатить аренду", jan31, Recurrence(Recurrence.Frequency.MONTHLY), "Europe/Moscow")
        assertEquals(31, r.recurrence?.dayOfMonth)
        val feb = env.store.reminders.markFired(r.id, jan31)!!
        assertEquals(Instant.parse("2027-02-28T07:00:00Z"), feb.triggerAt)
        val mar = env.store.reminders.markFired(r.id, feb.triggerAt)!!
        assertEquals(Instant.parse("2027-03-31T07:00:00Z"), mar.triggerAt)
    }

    @Test fun guardedSyncWriteDoesNotOverwriteConcurrentEdit() = runTest {
        val time = FixedTimeSource(now, zone)
        val store = LocalStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LoliDatabase.Schema.create(it) }, time, Dispatchers.Unconfined)
        val note = store.notes.create(NoteKind.NOTE, "Заметка", "")
        val before = store.notes.row(note.id)!!
        store.notes.append(note.id, "правка пользователя")
        // Синхронизация прочитала старую версию и пытается записать серверную
        val serverPayload = kotlinx.serialization.json.JsonObject(before.payload + ("content" to JsonPrimitive("серверная версия")))
        store.notes.write(serverPayload, dirty = false, syncedUpdatedAt = before.updatedAt, expectedLocalUpdatedAt = before.updatedAt, guard = true)
        assertEquals("• правка пользователя", store.notes.get(note.id)!!.content)
    }

    @Test fun expenseEntryWithTratyIsNotQuery() {
        val e = assertIs<AssistantAction.CreateExpense>(one("траты на кофе 300 рублей"))
        assertEquals(30_000, e.amountMinor)
    }

    @Test fun hourlyReminderDoesNotFireImmediately() {
        val r = assertIs<AssistantAction.CreateReminder>(one("каждый час напоминай пить воду"))
        assertEquals(now.plusSeconds(3600), r.triggerAt)
    }

    @Test fun calculatorDoesNotHijackPhrases() {
        assertNull(Calculator.evaluate("500 на 2"))
        assertEquals(1000.0, Calculator.evaluate("сколько будет 500 на 2"))
        assertEquals(12.0, Calculator.evaluate("3 х 4"))
        assertNull(Calculator.evaluate("купить хлеб"))
    }

    @Test fun noClearsPendingChoiceToo() = runTest {
        val env = env()
        env.store.notes.create(NoteKind.IDEA, "Приложение для склада", "")
        env.store.notes.create(NoteKind.IDEA, "Приложение для кафе", "")
        env.store.tasks.create("Старая задача")
        env.ai = null
        env.settings = AssistantSettings(useAI = true)
        env.ai = ai.loli.core.ScriptedAI {
            """{"actions":[{"type":"delete_task","query":"старая задача"},{"type":"append_note","query":"приложение","kind":"idea","content":"тёмная тема"}]}"""
        }
        env.engine.handle("удали старую задачу и добавь к идее приложения тёмную тему")
        env.engine.handle("нет")
        assertNull(env.engine.context.pendingChoice)
        assertNull(env.engine.context.pendingConfirmation)
    }

    @Test fun probeFindings() {
        assertIs<AssistantAction.Remember>(one("мой день рождения 12 марта"))
        assertEquals(ai.loli.core.finance.ReportMode.AVERAGE, assertIs<AssistantAction.QueryExpenses>(one("сколько в среднем я трачу в день")).mode)
        assertEquals("Дом и ЖКХ", assertIs<AssistantAction.CreateExpense>(one("заплатила за квартиру 35000")).category)
        assertIs<AssistantAction.CreateNote>(one("запиши, что мне понравилась идея с автоматическим планировщиком"))
        val list = assertNotNull(p.parse("добавь в список покупок хлеб и масло", now, zone)).actions
        assertEquals(listOf("хлеб", "масло"), list.map { (it as AssistantAction.AppendNote).content })
        val task = assertIs<AssistantAction.CreateTask>(one("надо не забыть оплатить квартиру до 10 числа"))
        assertEquals("Оплатить квартиру", task.title)
        assertEquals(LocalDate.of(2026, 10, 10), task.dueDate)
        assertEquals(Instant.parse("2026-09-26T03:30:00Z"), assertIs<AssistantAction.CreateReminder>(one("поставь будильник на 6:30")).triggerAt)
        assertEquals(null, assertIs<AssistantAction.QueryExpenses>(one("покажи расходы за последнюю неделю")).category)
        assertEquals("Транспорт", assertIs<AssistantAction.CreateExpense>(one("такси 380")).category)
        assertIs<AssistantAction.QueryReminders>(one("какие у меня напоминания"))
        assertIs<AssistantAction.CompleteTask>(one("отметь что я позвонила маме"))
        assertEquals("Купить корм коту", assertIs<AssistantAction.CreateTask>(one("купить корм коту")).title)
        assertEquals("Хорошо.", assertNotNull(p.parse("хватит", now, zone)).reply)
    }
}
