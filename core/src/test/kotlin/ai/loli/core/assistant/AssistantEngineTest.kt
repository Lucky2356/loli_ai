package ai.loli.core.assistant

import ai.loli.core.ScriptedAI
import ai.loli.core.TestEnv
import ai.loli.core.ai.AIException
import ai.loli.core.ai.AIProvider
import ai.loli.core.ai.AIProviderType
import ai.loli.core.ai.AIRequest
import ai.loli.core.ai.AIResponse
import ai.loli.core.handleFor
import ai.loli.core.model.NoteKind
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantEngineTest {

    @Test fun expenseViaAiIsSavedAndReported() = runTest {
        val env = TestEnv()
        env.ai = ScriptedAI {
            """{"reply":"Записала 850 ₽ на продукты.","actions":[{"type":"create_expense","amount":850,"currency":"RUB","category":"Продукты","description":"продукты","date":"2026-09-25"}]}"""
        }
        val reply = env.engine.handle("Лоли, сегодня потратила 850 рублей на продукты")
        assertTrue(reply.usedAI)
        assertTrue(reply.changedData)
        val saved = env.store.expenses.all().single()
        assertEquals(85_000, saved.amountMinor)
        assertEquals(LocalDate.of(2026, 9, 25), saved.occurredOn)
        // Имя ассистента вырезано из реплики, отправленной модели.
        val request = (env.ai as ScriptedAI).requests.single()
        assertEquals("сегодня потратила 850 рублей на продукты", request.messages.last().content)
    }

    @Test fun appendToExistingIdeaInsteadOfCreatingDuplicate() = runTest {
        val env = TestEnv()
        val idea = env.store.notes.create(NoteKind.IDEA, "Приложение для учёта продуктов в холодильнике", "")
        env.ai = ScriptedAI { req ->
            val h = req.handleFor("холодильник")
            """{"reply":"Добавила.","actions":[{"type":"append_note","target":"$h","content":"Сканирование штрихкодов"}]}"""
        }
        env.engine.handle("Лоли, добавь к идее холодильника возможность сканировать штрихкоды")
        val notes = env.store.notes.all()
        assertEquals(1, notes.size)
        assertEquals(idea.id, notes.single().id)
        assertTrue(notes.single().content.contains("Сканирование штрихкодов"))
    }

    @Test fun appendByQueryWhenAiUnsureAndCreateWhenMissing() = runTest {
        val env = TestEnv()
        env.store.notes.create(NoteKind.IDEA, "Приложение для холодильника", "учёт продуктов")
        env.ai = ScriptedAI {
            """{"actions":[{"type":"append_note","query":"продукты в холодильнике","kind":"idea","content":"сканирование штрихкодов"},
               {"type":"append_note","query":"космический корабль","kind":"idea","content":"двигатель","title_if_new":"Космический корабль"}]}"""
        }
        env.engine.handle("добавь к идее про продукты в холодильнике сканирование, и к идее про корабль двигатель")
        val ideas = env.store.notes.all(NoteKind.IDEA)
        assertEquals(2, ideas.size)
        assertTrue(ideas.first { it.title.contains("холодильник") }.content.contains("сканирование штрихкодов"))
        assertEquals("Космический корабль", ideas.first { it.title.contains("корабль") }.title)
    }

    @Test fun ambiguousTargetAsksInsteadOfGuessing() = runTest {
        val env = TestEnv()
        val a = env.store.notes.create(NoteKind.IDEA, "Приложение для склада", "")
        val b = env.store.notes.create(NoteKind.IDEA, "Приложение для кафе", "")
        env.ai = ScriptedAI { """{"actions":[{"type":"append_note","query":"приложение","kind":"idea","content":"тёмная тема"}]}""" }
        val reply = env.engine.handle("добавь к идее приложения тёмную тему")
        assertTrue(reply.awaitingAnswer)
        assertTrue(env.store.notes.all().all { it.content.isEmpty() }, "ничего не должно измениться до уточнения")
        // Ответ на уточнение выполняется без AI.
        env.ai = null
        val chosen = env.engine.handle("второе")
        assertTrue(chosen.changedData)
        val second = env.engine.context.recent.first()
        val updated = env.store.notes.get(second.id)!!
        assertEquals("• тёмная тема", updated.content)
        assertTrue(second.id == a.id || second.id == b.id)
    }

    @Test fun destructiveActionRequiresConfirmation() = runTest {
        val env = TestEnv()
        env.store.expenses.create(50_000, "RUB", "Продукты", "", LocalDate.of(2026, 9, 10))
        env.store.expenses.create(30_000, "RUB", "Кафе и рестораны", "", LocalDate.of(2026, 9, 12))
        env.ai = ScriptedAI { """{"actions":[{"type":"delete_expenses","period":"this_month"}]}""" }
        val ask = env.engine.handle("удали все мои расходы за месяц")
        assertTrue(ask.awaitingConfirmation)
        assertTrue(ask.text.contains("800"))
        assertEquals(2, env.store.expenses.all().size)

        val no = env.engine.handle("нет")
        assertFalse(no.changedData)
        assertEquals(2, env.store.expenses.all().size)

        env.engine.handle("удали все мои расходы за месяц")
        val yes = env.engine.respondToConfirmation(true)
        assertTrue(yes.changedData)
        assertTrue(env.store.expenses.all().isEmpty())
        assertEquals(2, (env.ai as ScriptedAI).requests.size, "«да/нет» не отправляются в AI")
    }

    @Test fun conversationContextFocusFollowsTopic() = runTest {
        val env = TestEnv()
        var turn = 0
        env.ai = ScriptedAI { req ->
            turn++
            when (turn) {
                1 -> """{"reply":"Хорошо, записала идею.","actions":[{"type":"create_note","kind":"idea","title":"Приложение для склада","content":""}],"expect_followup":true,"topic":"приложение для склада"}"""
                else -> {
                    assertTrue(req.system.contains("ФОКУС"), "фокус должен передаваться модели")
                    assertTrue(req.messages.size >= 3, "история предыдущих реплик передаётся модели")
                    val h = req.handleFor("склад")
                    val content = if (turn == 2) "Учёт остатков" else "Сканирование штрихкодов"
                    """{"reply":"Добавила.","actions":[{"type":"append_note","target":"$h","content":"$content"}],"expect_followup":true,"topic":"приложение для склада"}"""
                }
            }
        }
        val first = env.engine.handle("Лоли, давай придумаем приложение для склада", InputSource.VOICE)
        assertTrue(first.expectFollowUp)
        assertTrue(env.engine.context.dialogMode)
        env.engine.handle("Там нужно учитывать остатки", InputSource.VOICE)
        env.engine.handle("И ещё добавить сканирование штрихкодов", InputSource.VOICE)
        val note = env.store.notes.all().single()
        assertEquals("• Учёт остатков\n• Сканирование штрихкодов", note.content)
        val bye = env.engine.handle("хватит", InputSource.VOICE)
        assertFalse(bye.expectFollowUp)
        assertFalse(env.engine.context.dialogMode)
    }

    @Test fun queryResultsComeFromDataNotFromAi() = runTest {
        val env = TestEnv()
        env.store.expenses.create(120_000, "RUB", "Продукты", "", LocalDate.of(2026, 9, 20))
        env.store.expenses.create(30_000, "RUB", "Транспорт", "такси", LocalDate.of(2026, 9, 21))
        env.ai = ScriptedAI { """{"reply":"Вы потратили 999999 рублей","actions":[{"type":"query_expenses","period":"this_month","mode":"total"}]}""" }
        val reply = env.engine.handle("сколько я потратила в этом месяце?")
        assertTrue(reply.text.contains("1 500 ₽"), reply.text)
        assertFalse(reply.text.contains("999999"))
    }

    @Test fun offlineFallbackWhenAiUnavailable() = runTest {
        val env = TestEnv()
        env.ai = object : AIProvider {
            override val type = AIProviderType.OPENAI
            override val model = "x"
            override suspend fun complete(request: AIRequest): AIResponse = throw AIException.Network(null)
        }
        val reply = env.engine.handle("потратила 300 рублей на кофе")
        assertTrue(reply.offline)
        assertFalse(reply.usedAI)
        assertEquals(30_000, env.store.expenses.all().single().amountMinor)
    }

    @Test fun worksWithoutAiConfigured() = runTest {
        val env = TestEnv()
        env.ai = null
        env.engine.handle("добавь задачу купить продукты")
        env.engine.handle("напомни завтра в 10 утра позвонить клиенту")
        env.engine.handle("запомни, что я люблю зелёный чай")
        assertEquals("Купить продукты", env.store.tasks.all().single().title)
        assertEquals(1, env.scheduler.scheduled.size)
        assertEquals("Я люблю зелёный чай", env.store.memories.all().single().content)
        val unknown = env.engine.handle("расскажи анекдот")
        assertTrue(unknown.text.contains("AI не настроен"))
    }

    @Test fun birthdayNoteScenarioOffline() = runTest {
        val env = TestEnv()
        env.engine.handle("Лоли, создай заметку «Идеи для дня рождения»")
        env.engine.handle("Добавь туда игру Secret Identity")
        env.engine.handle("Добавь ещё идею с парфюмерным адвентом")
        val note = env.store.notes.all().single()
        assertEquals("Идеи для дня рождения", note.title)
        assertTrue(note.content.contains("Secret Identity"))
        assertTrue(note.content.contains("парфюмерным адвентом"))
        val found = env.engine.handle("Найди мои идеи для дня рождения")
        assertTrue(found.text.contains("Идеи для дня рождения"), found.text)
    }

    @Test fun offlineAppendSplitsQueryAndContent() = runTest {
        val env = TestEnv()
        env.store.notes.create(NoteKind.IDEA, "Приложение для холодильника", "")
        env.engine.handle("добавь к идее холодильника возможность сканировать штрихкоды")
        val idea = env.store.notes.all().single()
        assertEquals("• возможность сканировать штрихкоды", idea.content)
    }

    @Test fun completeTaskAndOverdue() = runTest {
        val env = TestEnv()
        env.store.tasks.create("Купить продукты")
        env.store.tasks.create("Сдать отчёт", dueDate = LocalDate.of(2026, 9, 20))
        env.engine.handle("отметь задачу купить продукты выполненной")
        assertTrue(env.store.tasks.all().first { it.title == "Купить продукты" }.done)
        val overdue = env.engine.handle("какие задачи у меня просрочены")
        assertTrue(overdue.text.contains("Сдать отчёт"))
        assertFalse(overdue.text.contains("Купить продукты"))
    }

    @Test fun contextExpiresAfterInactivity() = runTest {
        val env = TestEnv()
        env.engine.handle("создай заметку Покупки")
        assertTrue(env.engine.context.focus != null)
        env.time.advanceMillis(20 * 60 * 1000)
        env.engine.handle("привет")
        assertNull(env.engine.context.focus)
    }

    @Test fun conversationHistoryIsStored() = runTest {
        val env = TestEnv()
        env.engine.handle("привет")
        val history = env.store.conversations.recent(10)
        assertEquals(2, history.size)
    }
}
