package ai.loli.core.finance

import ai.loli.core.assistant.RuFormat
import ai.loli.core.data.LoliJson
import ai.loli.core.model.Expense
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.nlp.Money
import ai.loli.core.nlp.RuTokenizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/**
 * Сводка «Финансового помощника» — итоги без единой операции (договор версии 1).
 * Суммы — в копейках (в сводке они в рублях), даты — как есть.
 */
data class FinanceSummary(
    val updatedAt: Instant,
    val currency: String,
    val today: Today?,
    val month: Month,
    val categories: List<Category>,
    val balance: Balance,
    val payday: Payday?,
) {
    data class Today(val canSpend: Long, val perDay: Long, val spent: Long, val status: String)
    data class Month(val month: String, val income: Long, val expense: Long, val daysLeft: Int?)
    data class Category(val name: String, val spent: Long, val limit: Long?)
    data class Balance(val total: Long, val accounts: List<Account>)
    data class Account(val name: String, val balance: Long, val currency: String)
    data class Payday(val date: LocalDate, val daysLeft: Int, val free: Long, val perDay: Long, val status: String)

    companion object {
        /** null — не сводка или другой версии договора: лучше промолчать, чем ответить неправду. */
        fun parse(json: String?): FinanceSummary? = try {
            val o = LoliJson.parseToJsonElement(json ?: "").jsonObject
            if (o.int("v") != 1) null
            else FinanceSummary(
                updatedAt = Instant.parse(o.str("updatedAt")!!),
                currency = o.str("currency")?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: "RUB",
                today = (o["today"] as? JsonObject)?.let { t ->
                    Today(t.minor("canSpend"), t.minor("perDay"), t.minor("spent"), t.str("status") ?: "ok")
                },
                month = (o["month"] as JsonObject).let { m ->
                    Month(m.str("month") ?: "", m.minor("income"), m.minor("expense"), m.int("daysLeft"))
                },
                categories = (o["categories"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { c ->
                    val name = c.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Category(name, c.minor("spent"), c.minorOrNull("limit"))
                },
                balance = (o["balance"] as JsonObject).let { b ->
                    Balance(b.minor("total"), (b["accounts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { a ->
                        val name = a.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        Account(name, a.minor("balance"), a.str("currency") ?: "RUB")
                    })
                },
                payday = (o["payday"] as? JsonObject)?.let { p ->
                    Payday(LocalDate.parse(p.str("date")!!), p.int("daysLeft") ?: 0, p.minor("free"), p.minor("perDay"), p.str("status") ?: "ok")
                },
            )
        } catch (_: Exception) {
            null
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.minorOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.doubleOrNull?.let { (it * 100).roundToLong() }
        private fun JsonObject.minor(key: String): Long = minorOrNull(key) ?: 0
    }
}

/** О чём спросили. */
enum class FinanceAsk { TODAY, PAYDAY, BALANCE, CATEGORY, OVERVIEW }

/**
 * Ответы Лоли о деньгах по сводке помощника.
 *
 * Сводку помощник обновляет, когда его открывают. Поэтому:
 *  - траты, сказанные Лоли после этого, помощник ещё не видел — их досчитываем сами ([pending]);
 *  - сводка вчерашняя — «сегодня» по ней не считаем, а честно говорим, от какого она числа.
 */
object FinanceAnswers {

    /**
     * [subject] — категория или счёт из вопроса («сколько осталось на продукты»).
     * null — сводка не знает ответа (нет такой категории/счёта): пусть отвечает сама Лоли.
     */
    fun answer(s: FinanceSummary, ask: FinanceAsk, subject: String?, pending: List<Expense>, today: LocalDate, zone: ZoneId): String? {
        val fresh = s.updatedAt.atZone(zone).toLocalDate()
        val spentLater = pending.filter { it.category != ExpenseCategories.INCOME && it.currency == s.currency }
        return when (ask) {
            FinanceAsk.TODAY -> todayAnswer(s, spentLater, fresh, today)
            FinanceAsk.PAYDAY -> paydayAnswer(s, spentLater, fresh, today)
            FinanceAsk.BALANCE -> balanceAnswer(s, subject, pending, fresh, today)
            FinanceAsk.CATEGORY -> categoryAnswer(s, subject ?: return null, spentLater, today)
            FinanceAsk.OVERVIEW -> listOfNotNull(
                todayAnswer(s, spentLater, fresh, today),
                monthAnswer(s, spentLater, today),
                s.payday?.let { paydayAnswer(s, spentLater, fresh, today) },
            ).joinToString(" ")
        }
    }

    private fun money(minor: Long, currency: String) = Money.format(minor, currency)

    /** Пустая строка, если сводка сегодняшняя. */
    private fun staleNote(fresh: LocalDate, today: LocalDate): String = when (fresh) {
        today -> ""
        today.minusDays(1) -> " Цифры помощника — вчерашние."
        else -> " Цифры помощника — на ${RuFormat.date(fresh, today)}."
    }

    private fun pendingNote(n: Int) = if (n == 0) "" else
        " Учла и ${RuFormat.count(n, "трату", "траты", "трат")} из Лоли, которых помощник ещё не видел."

    private fun todayAnswer(s: FinanceSummary, spentLater: List<Expense>, fresh: LocalDate, today: LocalDate): String? {
        val t = s.today ?: return "В Финансовом помощнике не задан бюджет на месяц, поэтому «сколько можно сегодня» он не считает."
        if (fresh != today) {
            val todays = spentLater.filter { it.occurredOn == today }
            val extra = if (todays.isEmpty()) "" else " Сегодня вы уже потратили ${money(todays.sumOf { it.amountMinor }, s.currency)}."
            return "Свежих цифр на сегодня нет: Финансовый помощник последний раз открывали ${RuFormat.date(fresh, today)}. " +
                "Тогда выходило ${money(t.perDay, s.currency)} в день.$extra Откройте его — пересчитаю."
        }
        val todays = spentLater.filter { it.occurredOn == today }
        val extra = todays.sumOf { it.amountMinor }
        if (t.perDay <= 0) {
            val spentNow = t.spent + extra
            return "Бюджет этого месяца уже израсходован — по расчёту помощника на сегодня свободных денег нет." +
                (if (spentNow > 0) " Сегодня потрачено ${money(spentNow, s.currency)}." else "") + pendingNote(todays.size)
        }
        // Помощник не даёт «можно» уйти ниже нуля; перерасход видно по «на день» и «потрачено».
        val left = (if (t.canSpend > 0) t.canSpend else t.perDay - t.spent) - extra
        val spent = t.spent + extra
        val head = when {
            left > 0 -> "Сегодня можно потратить ещё ${money(left, s.currency)}"
            left == 0L -> "На сегодня всё: потрачено ровно столько, сколько можно"
            else -> "Сегодня уже перерасход на ${money(-left, s.currency)}"
        }
        val tail = if (spent > 0) " — уже потрачено ${money(spent, s.currency)} из ${money(t.perDay, s.currency)} на день." else " — это весь дневной бюджет."
        return head + tail + pendingNote(todays.size)
    }

    private fun paydayAnswer(s: FinanceSummary, spentLater: List<Expense>, fresh: LocalDate, today: LocalDate): String {
        val p = s.payday ?: return "В Финансовом помощнике не указан день зарплаты — его можно задать на главной, в карточке «Хватит ли до зарплаты»."
        val days = ChronoUnit.DAYS.between(today, p.date).toInt()
        if (days < 0) return "Зарплата по плану была ${RuFormat.date(p.date, today)}. Откройте Финансовый помощник — он пересчитает до следующей."
        // Любая трата после сводки уменьшает свободное до зарплаты — за какое бы число её ни записали.
        val later = spentLater
        val free = p.free - later.sumOf { it.amountMinor }
        val whenText = when (days) { 0 -> "сегодня"; 1 -> "завтра"; else -> "через ${RuFormat.count(days, "день", "дня", "дней")}" }
        val stale = staleNote(fresh, today)
        return if (free >= 0) {
            val perDay = if (days > 0) " — примерно ${money(free / days, s.currency)} в день" else ""
            "До зарплаты $whenText хватает: свободно ${money(free, s.currency)}$perDay.$stale" + pendingNote(later.size)
        } else {
            "До зарплаты $whenText не хватает ${money(-free, s.currency)} — с учётом обязательных платежей.$stale" + pendingNote(later.size)
        }
    }

    private fun monthAnswer(s: FinanceSummary, spentLater: List<Expense>, today: LocalDate): String? {
        val current = "%04d-%02d".format(today.year, today.monthValue)
        if (s.month.month != current) return null
        val later = spentLater.filter { it.occurredOn.year == today.year && it.occurredOn.monthValue == today.monthValue }
        val expense = s.month.expense + later.sumOf { it.amountMinor }
        return "За месяц потрачено ${money(expense, s.currency)}, пришло ${money(s.month.income, s.currency)}."
    }

    private fun balanceAnswer(s: FinanceSummary, subject: String?, pending: List<Expense>, fresh: LocalDate, today: LocalDate): String? {
        val stale = staleNote(fresh, today)
        if (subject != null) {
            val account = s.balance.accounts.firstOrNull { same(it.name, subject) } ?: return null
            return "На «${account.name}» — ${money(account.balance, account.currency)}.$stale"
        }
        val mine = pending.filter { it.currency == s.currency }
        val delta = mine.sumOf { if (it.category == ExpenseCategories.INCOME) it.amountMinor else -it.amountMinor }
        val total = s.balance.total + delta
        val accounts = s.balance.accounts.sortedByDescending { it.balance }.take(3)
            .joinToString(", ") { "«${it.name}» ${money(it.balance, it.currency)}" }
        val split = if (s.balance.accounts.size > 1 && accounts.isNotEmpty()) " Больше всего: $accounts." else ""
        return "Всего на счетах ${money(total, s.currency)}.$split$stale" + pendingNote(mine.size)
    }

    private fun categoryAnswer(s: FinanceSummary, subject: String, spentLater: List<Expense>, today: LocalDate): String? {
        val c = s.categories.firstOrNull { same(it.name, subject) } ?: return null
        val current = "%04d-%02d".format(today.year, today.monthValue)
        if (s.month.month != current) {
            return "Свежих цифр за этот месяц нет — откройте Финансовый помощник, и я отвечу про «${c.name}»."
        }
        val later = spentLater.filter { same(it.category, c.name) && it.occurredOn.monthValue == today.monthValue && it.occurredOn.year == today.year }
        val spent = c.spent + later.sumOf { it.amountMinor }
        val limit = c.limit ?: return "На «${c.name}» в этом месяце потрачено ${money(spent, s.currency)}. Лимита на эту статью нет." + pendingNote(later.size)
        val left = limit - spent
        return if (left >= 0) {
            "На «${c.name}» осталось ${money(left, s.currency)} из ${money(limit, s.currency)}." + pendingNote(later.size)
        } else {
            "На «${c.name}» лимит превышен на ${money(-left, s.currency)}: потрачено ${money(spent, s.currency)} из ${money(limit, s.currency)}." + pendingNote(later.size)
        }
    }

    /**
     * Одно и то же название, сказанное по-разному: «продукты» и «Продукты»,
     * «кафе» и «Кафе и рестораны», «на ресторанах» и «Рестораны», «ЖКХ» и «Дом и ЖКХ»,
     * «на Т-Банке» и «Т-Банк», «Сбера» и «Сбер».
     */
    fun same(a: String, b: String): Boolean {
        val whole = RuTokenizer.normalize(a).trim()
        if (whole.isNotEmpty() && whole == RuTokenizer.normalize(b).trim()) return true
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x.any { w -> y.any { v -> alike(w, v) } }
    }

    private val SKIP = setOf("и", "на", "в", "по", "для", "мой", "моя", "мои", "моей", "моем", "карта", "карте", "карту", "карты", "счет", "счете", "счета", "счету")

    /** Слова от трёх букв: однобуквенные куски («т» из «Т-Банк») ничего не различают. */
    private fun words(s: String): List<String> =
        RuTokenizer.normalize(s).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 && it !in SKIP }

    /** Общее начало: до пяти букв, но не длиннее короткого слова («банке» и «банк», «сбера» и «сбер»). */
    private fun alike(w: String, v: String): Boolean {
        val n = minOf(5, w.length, v.length)
        return w.take(n) == v.take(n)
    }
}
