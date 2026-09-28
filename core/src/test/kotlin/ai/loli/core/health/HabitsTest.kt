package ai.loli.core.health

import ai.loli.core.TestEnv
import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.assistant.RelaxKind
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Привычки и здоровье (2.2). Сегодня пятница 25.09.2026, 12:00 МСК. */
class HabitsTest {
    private fun p(t: String) = HabitPhrases.parse(t)

    @Test fun phrases() {
        assertEquals(HabitCommand.Water(1.0), p("выпила стакан воды"))
        assertEquals(HabitCommand.Water(2.0), p("я выпил два стакана воды"))
        assertEquals(HabitCommand.Water(1.0), p("попила водички"))
        assertEquals(HabitCommand.Water(4.0), p("выпила литр воды"))
        assertEquals(HabitCommand.Water(2.0), p("+2 стакана"))
        assertEquals(HabitCommand.Water(1.0), p("отметь стакан воды"))
        assertNull(p("выпила стакан кофе"))
        assertNull(p("купи воду"))
        assertEquals(HabitCommand.WaterToday, p("сколько я выпила воды сегодня"))
        assertEquals(HabitCommand.WaterToday, p("сколько воды я выпил"))
        assertEquals(HabitCommand.WaterGoal(10), p("моя норма воды 10 стаканов"))
        assertEquals(HabitCommand.Pill("таблетка"), p("я приняла таблетку"))
        assertEquals(HabitCommand.Pill("таблетка от давления"), p("выпила таблетку от давления"))
        assertTrue(p("я принимала сегодня таблетки?") is HabitCommand.PillAsk)
        assertTrue(p("пила ли я витамины") is HabitCommand.PillAsk)
        assertEquals(HabitCommand.Mark("зарядка"), p("я сделала зарядку"))
        assertEquals(HabitCommand.Mark("пробежка"), p("отметь пробежку"))
        assertEquals(HabitCommand.Mark("пробежка"), p("я побегала"))
        assertEquals(HabitCommand.Mark("йога"), p("позанималась йогой"))
        assertEquals(HabitCommand.Streak("зарядка"), p("сколько дней подряд я делаю зарядку"))
        assertEquals(HabitCommand.Summary, p("мои привычки"))
        assertEquals(HabitCommand.Relax(RelaxKind.BREATHING, 2), p("давай подышим"))
        assertEquals(HabitCommand.Relax(RelaxKind.MEDITATION, 10), p("медитация на 10 минут"))
        assertEquals(HabitCommand.Relax(RelaxKind.BREATHING, 2), p("помоги успокоиться"))
        assertTrue(p("стоп медитация") is HabitCommand.Relax)
        assertNull(p("отметь в календаре встречу"))
        assertNull(p("сделала отчёт"))
        assertNull(p("напомни выпить таблетку в 9"))
    }

    @Test fun waterDay() = runTest {
        val e = TestEnv().apply { settings = AssistantSettings(useAI = false) }
        assertTrue(e.engine.handle("выпила стакан воды").text.contains("1 из 8"))
        assertTrue(e.engine.handle("выпила ещё два стакана воды").text.contains("3 из 8"))
        assertTrue(e.engine.handle("сколько я выпила воды?").text.contains("3 стакана"))
        val undo = e.engine.handle("отмени последнее").text
        assertTrue(undo.startsWith("Убрала отметку"), undo)
        assertTrue(e.engine.handle("сколько воды я выпила").text.contains("1 стакан"))
    }

    @Test fun pillsAndStreaks() = runTest {
        val e = TestEnv().apply { settings = AssistantSettings(useAI = false) }
        assertTrue(e.engine.handle("я принимала сегодня таблетки?").text.startsWith("Сегодня отметок нет"))
        e.engine.handle("я приняла таблетку от давления")
        val ask = e.engine.handle("я принимала сегодня таблетки?").text
        assertTrue(ask.startsWith("Да, сегодня") && ask.contains("от давления"), ask)
        // Зарядка три дня подряд.
        e.engine.handle("я сделала зарядку")
        e.time.advanceMillis(Duration.ofDays(1).toMillis()); e.engine.handle("я сделала зарядку")
        e.time.advanceMillis(Duration.ofDays(1).toMillis())
        val r = e.engine.handle("я сделала зарядку").text
        assertTrue(r.contains("3 дня подряд"), r)
        assertTrue(e.engine.handle("я сделала зарядку").text.contains("уже отмечена"))
        assertTrue(e.engine.handle("мои привычки").text.contains("зарядка"))
    }

    @Test fun healthIsPrivateWhenLocked() = runTest {
        val e = TestEnv().apply { settings = AssistantSettings(useAI = false, locked = true) }
        assertTrue(e.engine.handle("я принимала сегодня таблетки?").text.startsWith("Разблокируйте"))
    }
}
