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
        val offer = env.engine.handle("завтра у Пети концерт в филармонии")
        assertTrue(offer.text.contains("Сохранить это как заметку"), offer.text)
        env.engine.handle("да")
        assertTrue(env.store.notes.all().single().title.startsWith("Завтра у Пети концерт"))
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

    @Test fun helpAndIdentityUseAssistantName() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(assistantName = "Кира", useAI = false) }
        val who = env.engine.handle("кто ты")
        assertTrue(who.text.startsWith("Я Кира"), who.text)
    }
}
