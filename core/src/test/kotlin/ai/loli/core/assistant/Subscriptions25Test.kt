package ai.loli.core.assistant

import ai.loli.core.TestEnv
import ai.loli.core.model.Recurrence
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 2.5.0: подписки и платежи. Сегодня пятница 25.09.2026. */
class Subscriptions25Test {
    private fun env() = TestEnv().apply { settings = AssistantSettings(useAI = false) }

    @Test fun addCreatesMonthlyReminderAndMemory() = runTest {
        val e = env()
        val text = e.engine.handle("подписка на музыку 299 рублей раз в месяц 5 числа").text
        val r = e.store.reminders.all().single()
        assertEquals(Recurrence.Frequency.MONTHLY, r.recurrence?.frequency)
        assertEquals(5, r.recurrence?.dayOfMonth)
        assertTrue(r.text.startsWith("Подписка Музыка: сегодня спишется 299"), r.text)
        assertTrue(e.store.memories.all().single().content.startsWith("Подписка Музыка: 299 ₽ в месяц"), e.store.memories.all().toString())
        assertTrue(text.contains("Музыка") && text.contains("299"), text)
    }

    @Test fun yearlyAndTotals() = runTest {
        val e = env()
        e.engine.handle("подписка на музыку 300 рублей раз в месяц 5 числа")
        e.engine.handle("подписка на антивирус 1200 рублей раз в год 3 марта")
        val list = e.engine.handle("мои подписки").text
        assertTrue(list.contains("Музыка") && list.contains("Антивирус"), list)
        val total = e.engine.handle("сколько уходит на подписки").text
        assertTrue(total.contains("400 ₽ в месяц") && total.contains("4800 ₽ в год"), total)
    }

    @Test fun cancelRemovesBoth() = runTest {
        val e = env()
        e.engine.handle("подписка на музыку 299 рублей раз в месяц 5 числа")
        val text = e.engine.handle("отмени подписку на музыку").text
        assertTrue(text.contains("Убрала"), text)
        assertTrue(e.store.reminders.all().isEmpty())
        assertTrue(e.store.memories.all().isEmpty())
        assertTrue(e.engine.handle("отмени подписку на музыку").text.contains("Не нашла"))
    }

    @Test fun noSubscriptionsYet() = runTest {
        assertTrue(env().engine.handle("мои подписки").text.contains("Подписок пока нет"))
    }
}
