package ai.loli.core.assistant

import ai.loli.core.TestEnv
import ai.loli.core.model.NoteKind
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Сквозные сценарии полностью локального режима (облачный AI выключен — это режим по умолчанию). */
class LocalModeEngineTest {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun defaultIsLocal() {
        assertFalse(AssistantSettings().useAI)
    }

    @Test fun brainstormAppendsEverythingToIdea() = runTest {
        val env = env()
        val start = env.engine.handle("Лоли, давай придумаем приложение для склада", InputSource.VOICE)
        assertTrue(start.expectFollowUp)
        env.engine.handle("Там нужно учитывать остатки", InputSource.VOICE)
        env.engine.handle("И ещё добавить сканирование штрихкодов", InputSource.VOICE)
        env.engine.handle("уведомления, когда товар заканчивается", InputSource.VOICE)
        env.engine.handle("хватит", InputSource.VOICE)
        val idea = env.store.notes.all(NoteKind.IDEA).single()
        assertEquals("Приложение для склада", idea.title)
        assertEquals(
            "• Нужно учитывать остатки\n• Добавить сканирование штрихкодов\n• Уведомления, когда товар заканчивается",
            idea.content,
        )
        assertFalse(env.engine.context.dialogMode)
    }

    @Test fun slotFillingThroughEngine() = runTest {
        val env = env()
        val ask = env.engine.handle("потратила на продукты")
        assertTrue(ask.awaitingAnswer)
        assertTrue(ask.text.startsWith("Сколько"))
        env.engine.handle("850")
        assertEquals(85_000, env.store.expenses.all().single().amountMinor)

        val when_ = env.engine.handle("напомни полить цветы")
        assertTrue(when_.text.startsWith("Когда напомнить"))
        env.engine.handle("завтра в 9 утра")
        assertEquals("Полить цветы", env.store.reminders.all().single().text)
        assertEquals(1, env.scheduler.scheduled.size)
    }

    @Test fun newCommandInsteadOfSlotAnswer() = runTest {
        val env = env()
        env.engine.handle("напомни полить цветы")
        env.engine.handle("добавь задачу купить землю")
        assertEquals("Купить землю", env.store.tasks.all().single().title)
        assertTrue(env.store.reminders.all().isEmpty())
    }

    @Test fun undoLastAndCorrectExpense() = runTest {
        val env = env()
        env.engine.handle("потратила 850 на продукты")
        env.engine.handle("не 850, а 950")
        assertEquals(95_000, env.store.expenses.all().single().amountMinor)
        val ask = env.engine.handle("отмени последнее")
        assertTrue(ask.awaitingConfirmation)
        env.engine.handle("да")
        assertTrue(env.store.expenses.all().isEmpty())
    }

    @Test fun agendaCombinesTasksRemindersExpenses() = runTest {
        val env = env()
        env.engine.handle("добавь задачу на сегодня отправить отчёт")
        env.engine.handle("напомни сегодня в 18:00 забрать посылку")
        env.engine.handle("потратила 300 на кофе")
        env.store.tasks.create("Старое дело", dueDate = LocalDate.of(2026, 9, 20))
        val agenda = env.engine.handle("что у меня на сегодня")
        assertTrue(agenda.text.contains("Отправить отчёт"), agenda.text)
        assertTrue(agenda.text.contains("Забрать посылку"), agenda.text)
        assertTrue(agenda.text.contains("Просрочено"), agenda.text)
        assertTrue(agenda.text.contains("300"), agenda.text)
    }

    @Test fun memoryQuestionAnswered() = runTest {
        val env = env()
        env.engine.handle("меня зовут Аня")
        env.engine.handle("мою маму зовут Ольга")
        val name = env.engine.handle("как меня зовут?")
        assertTrue(name.text.contains("Аня"), name.text)
        val mom = env.engine.handle("как зовут мою маму?")
        assertTrue(mom.text.contains("Ольга"), mom.text)
    }

    @Test fun unknownPhraseOfferedAsNote() = runTest {
        val env = env()
        val offer = env.engine.handle("у Пети отличный вкус на книги")
        assertTrue(offer.text.contains("Сохранить это как заметку"), offer.text)
        env.engine.handle("да")
        assertTrue(env.store.notes.all().single().title.startsWith("У Пети отличный вкус"))
        val offer2 = env.engine.handle("у соседей опять шумный ремонт")
        assertTrue(offer2.awaitingAnswer)
        env.engine.handle("нет")
        assertEquals(1, env.store.notes.all().size)
    }

    @Test fun offlineSemanticSearchViaSynonyms() = runTest {
        val env = env()
        env.store.notes.create(NoteKind.IDEA, "Приложение для холодильника", "учёт сроков годности")
        env.store.notes.create(NoteKind.NOTE, "Поездка в Японию", "Токио, Киото")
        val food = env.engine.handle("найди всё про еду")
        assertTrue(food.text.contains("холодильника"), food.text)
        val trip = env.engine.handle("найди мои заметки про отпуск")
        assertTrue(trip.text.contains("Японию"), trip.text)
    }

    @Test fun compoundCommandExecutesAll() = runTest {
        val env = env()
        env.engine.handle("запиши расход 500 на такси и добавь задачу позвонить в банк, а ещё напомни завтра в 10 оплатить интернет")
        assertEquals(1, env.store.expenses.all().size)
        assertEquals("Позвонить в банк", env.store.tasks.all().single().title)
        assertEquals("Оплатить интернет", env.store.reminders.all().single().text)
    }

    @Test fun datedStatementBecomesTaskOrReminder() = runTest {
        val env = env()
        env.engine.handle("завтра у Пети концерт в филармонии")
        assertEquals(LocalDate.of(2026, 9, 26), env.store.tasks.all().single().dueDate)
        env.engine.handle("у меня завтра встреча с клиентом в 15:00")
        assertEquals("Встреча с клиентом", env.store.reminders.all().single().text)
    }

    @Test fun expenseFollowUpQuestions() = runTest {
        val env = env()
        env.store.expenses.create(50_000, "RUB", "Кафе и рестораны", "", LocalDate.of(2026, 9, 20))
        env.store.expenses.create(30_000, "RUB", "Транспорт", "", LocalDate.of(2026, 9, 21))
        env.store.expenses.create(70_000, "RUB", "Транспорт", "", LocalDate.of(2026, 8, 10))
        env.engine.handle("сколько я потратила на кафе в этом месяце")
        val transport = env.engine.handle("а на транспорт?")
        assertTrue(transport.text.contains("300"), transport.text)
        val august = env.engine.handle("а в августе?")
        assertTrue(august.text.contains("700"), august.text)
    }

    @Test fun voiceDialogKeepsListeningAfterAnyCommand() = runTest {
        val env = env()
        val r1 = env.engine.handle("Лоли, потратила 300 рублей на кофе", InputSource.VOICE)
        assertTrue(r1.expectFollowUp, "в диалоговом режиме слушаем дальше после обычной команды")
        val r2 = env.engine.handle("и добавь задачу купить хлеб", InputSource.VOICE)
        assertTrue(r2.expectFollowUp)
        assertEquals(1, env.store.tasks.all().size, r2.text)
        val bye = env.engine.handle("спасибо, всё", InputSource.VOICE)
        assertTrue(bye.endsDialog)
        assertFalse(bye.expectFollowUp)
        assertFalse(env.engine.context.dialogMode)
        val offer = env.engine.handle("у соседей опять шумный ремонт")
        assertTrue(offer.text.contains("Сохранить это как заметку"), "обычный диалог не включает дописывание в записи")
    }

    @Test fun voiceWithoutDialogModeStopsAfterCommand() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(useAI = false, dialogMode = false) }
        val r = env.engine.handle("потратила 300 рублей на кофе", InputSource.VOICE)
        assertFalse(r.expectFollowUp)
        val ask = env.engine.handle("потратила на продукты", InputSource.VOICE)
        assertTrue(ask.expectFollowUp, "ждём ответа на уточняющий вопрос даже без диалогового режима")
    }

    @Test fun dialogModeRespectsSettingAndEnds() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(useAI = false, dialogMode = false) }
        env.engine.handle("давай придумаем приложение для кафе", InputSource.VOICE)
        assertFalse(env.engine.context.dialogMode, "диалог выключен в настройках")
        env.settings = AssistantSettings(useAI = false, dialogMode = true)
        env.engine.handle("давай придумаем приложение для склада", InputSource.VOICE)
        assertTrue(env.engine.context.dialogMode)
        env.engine.endDialog()
        assertFalse(env.engine.context.dialogMode)
        val offer = env.engine.handle("у соседей опять шумный ремонт")
        assertTrue(offer.text.contains("Сохранить это как заметку"), "после окончания диалога фраза не дописывается в идею")
    }

    @Test fun bareWakeWordKeepsListening() = runTest {
        val env = env()
        val r = env.engine.handle("Лоли")
        assertEquals("Слушаю!", r.text)
        assertTrue(r.awaitingAnswer)
    }

    @Test fun helpAndIdentityUseAssistantName() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(assistantName = "Кира", useAI = false) }
        val who = env.engine.handle("кто ты")
        assertTrue(who.text.startsWith("Я Кира"), who.text)
    }
}
