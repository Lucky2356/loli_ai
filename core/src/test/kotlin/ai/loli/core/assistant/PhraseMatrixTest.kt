package ai.loli.core.assistant

import ai.loli.core.TestEnv
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Живые формулировки: в задачу/напоминание попадает только действие в начальной форме,
 * дата и время — отдельно. Сегодня пятница 25.09.2026, 12:00 МСК.
 */
class PhraseMatrixTest {
    private val now = Instant.parse("2026-09-25T09:00:00Z")
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 9, 25)
    private val tomorrow = today.plusDays(1)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    private data class TaskCase(val phrase: String, val title: String, val date: LocalDate?, val time: LocalTime? = null)

    private val tasks = listOf(
        TaskCase("Лоли добавь в задачу что мне завтра нужно сходить в парикмахерскую", "Сходить в парикмахерскую", tomorrow),
        TaskCase("добавь в задачу сходить в парикмахерскую", "Сходить в парикмахерскую", null),
        TaskCase("добавь в задачи что завтра надо сходить в парикмахерскую", "Сходить в парикмахерскую", tomorrow),
        TaskCase("запиши задачу на послезавтра в семнадцать ноль ноль забрать посылку", "Забрать посылку", today.plusDays(2), t(17)),
        TaskCase("запиши задачу на послезавтра в 17:00 забрать посылку", "Забрать посылку", today.plusDays(2), t(17)),
        TaskCase("добавь задачу завтра в семнадцать тридцать позвонить врачу", "Позвонить врачу", tomorrow, t(17, 30)),
        TaskCase("добавь задачу завтра в девять пятнадцать утра позвонить врачу", "Позвонить врачу", tomorrow, t(9, 15)),
        TaskCase("мне нужно в понедельник в пол шестого позвонить врачу, добавь в задачи", "Позвонить врачу", LocalDate.of(2026, 9, 28), t(17, 30)),
        TaskCase("Лоли, завтра схожу в парикмахерскую, запиши", "Сходить в парикмахерскую", tomorrow),
        TaskCase("поставь задачу чтобы я завтра забрала посылку", "Забрать посылку", tomorrow),
        TaskCase("создай задачу о том что нужно оплатить интернет до пятницы", "Оплатить интернет", LocalDate.of(2026, 10, 2)),
        TaskCase("добавь в список дел купить подарок маме на выходных", "Купить подарок маме", LocalDate.of(2026, 9, 26)),
        TaskCase("добавь в планы на завтра записаться к стоматологу", "Записаться к стоматологу", tomorrow),
        TaskCase("новая задача: в среду отвезти документы", "Отвезти документы", LocalDate.of(2026, 9, 30)),
        TaskCase("Лоли, мне завтра надо заехать в банк, добавь задачу", "Заехать в банк", tomorrow),
        TaskCase("задача на завтра куплю корм коту", "Купить корм коту", tomorrow),
        TaskCase("добавь задачу я должна завтра позвонить маме", "Позвонить маме", tomorrow),
        TaskCase("добавь задачу хочу в субботу сходить в кино", "Сходить в кино", LocalDate.of(2026, 9, 26)),
        TaskCase("запиши в задачи что 3 октября нужно продлить страховку", "Продлить страховку", LocalDate.of(2026, 10, 3)),
        TaskCase("добавь задачу через неделю сдать отчёт", "Сдать отчёт", today.plusWeeks(1)),
        TaskCase("добавь задачу в полдень созвониться с командой", "Созвониться с командой", today, t(12)),
    )

    @Test fun tasks() = runTest {
        val failures = ArrayList<String>()
        for (c in tasks) {
            val env = TestEnv().apply { settings = AssistantSettings(useAI = false) }
            env.engine.handle(c.phrase)
            val all = env.store.tasks.all()
            val task = all.singleOrNull()
            if (task == null) { failures += "«${c.phrase}» → задач: ${all.map { it.title }}, напоминаний: ${env.store.reminders.all().map { it.text }}"; continue }
            if (task.title != c.title || task.dueDate != c.date || task.dueTime != c.time) {
                failures += "«${c.phrase}» → «${task.title}» ${task.dueDate} ${task.dueTime}; ожидалось «${c.title}» ${c.date} ${c.time}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private data class RemCase(val phrase: String, val text: String, val at: String)

    private val reminders = listOf(
        RemCase("напомни завтра в семнадцать ноль ноль забрать посылку", "Забрать посылку", "2026-09-26T17:00"),
        RemCase("напомни в восемнадцать тридцать выключить духовку", "Выключить духовку", "2026-09-25T18:30"),
        RemCase("напомни без пятнадцати восемь вечера выключить утюг", "Выключить утюг", "2026-09-25T19:45"),
        RemCase("напомни в пол шестого вечера позвонить маме", "Позвонить маме", "2026-09-25T17:30"),
        RemCase("напомни в полшестого вечера позвонить маме", "Позвонить маме", "2026-09-25T17:30"),
        RemCase("напомни без четверти девять вечера принять таблетки", "Принять таблетки", "2026-09-25T20:45"),
        RemCase("напомни в четверть девятого вечера принять таблетки", "Принять таблетки", "2026-09-25T20:15"),
        RemCase("напомни мне что завтра в десять нужно позвонить в банк", "Позвонить в банк", "2026-09-26T10:00"),
        RemCase("напомни через 10 минут поставить чайник", "Поставить чайник", "2026-09-25T12:10"),
        RemCase("напомни в пятнадцать ноль пять забрать сына", "Забрать сына", "2026-09-25T15:05"),
        RemCase("Лоли напомни чтобы я завтра в 9 утра оплатила квартиру", "Оплатить квартиру", "2026-09-26T09:00"),
    )

    @Test fun reminders() = runTest {
        val failures = ArrayList<String>()
        for (c in reminders) {
            val env = TestEnv().apply { settings = AssistantSettings(useAI = false) }
            env.engine.handle(c.phrase)
            val r = env.store.reminders.all().singleOrNull()
            if (r == null) { failures += "«${c.phrase}» → напоминаний: ${env.store.reminders.all().map { it.text }}, задач: ${env.store.tasks.all().map { it.title }}"; continue }
            val at = r.triggerAt.atZone(zone).toLocalDateTime().toString()
            if (r.text != c.text || at != c.at) failures += "«${c.phrase}» → «${r.text}» $at; ожидалось «${c.text}» ${c.at}"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test fun replyNamesDate() = runTest {
        val env = TestEnv().apply { settings = AssistantSettings(useAI = false) }
        val reply = env.engine.handle("добавь задачу завтра сходить в парикмахерскую")
        assertTrue("26 сентября" in reply.text, reply.text)
    }

    @Test fun aiTitleIsCleaned() {
        assertEquals("Сходить в парикмахерскую", cleanTaskTitle("В задачу мне завтра нужно сходить в парикмахерскую"))
        assertEquals("Сходить в парикмахерскую", cleanTaskTitle("Что мне нужно сходить в парикмахерскую"))
        assertEquals("Позвонить маме", cleanTaskTitle("позвоню маме"))
    }

    private data class ExpCase(val phrase: String, val rub: Long, val category: String, val date: LocalDate)

    private val expenses = listOf(
        ExpCase("потратила 850 рублей на продукты", 850, "Продукты", today),
        ExpCase("вчера заправился на две тысячи", 2000, "Транспорт", today.minusDays(1)),
        ExpCase("2 дня назад потратил 500 на такси", 500, "Транспорт", today.minusDays(2)),
        ExpCase("в прошлую пятницу отдала 1200 за стрижку", 1200, "Красота", LocalDate.of(2026, 9, 18)),
        ExpCase("купила лекарства в аптеке за триста пятьдесят рублей", 350, "Здоровье", today),
        ExpCase("расход 300 на кофе", 300, "Кафе и рестораны", today),
        ExpCase("Лоли, запиши трату 1500 на подарок маме", 1500, "Подарки", today),
    )

    @Test fun expenses() = runTest {
        val failures = ArrayList<String>()
        for (c in expenses) {
            val env = TestEnv().apply { settings = AssistantSettings(useAI = false) }
            env.engine.handle(c.phrase)
            val e = env.store.expenses.all().singleOrNull()
            if (e == null) { failures += "«${c.phrase}» → расходов нет"; continue }
            if (e.amountMinor != c.rub * 100 || e.category != c.category || e.occurredOn != c.date) {
                failures += "«${c.phrase}» → ${e.amountMinor / 100} ${e.category} ${e.occurredOn}; ожидалось ${c.rub} ${c.category} ${c.date}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }
}
