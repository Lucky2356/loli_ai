package ai.loli.core.finance

import ai.loli.core.domain.ExpenseRepository
import ai.loli.core.model.Expense
import ai.loli.core.nlp.ExpenseCategories
import ai.loli.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate

/**
 * Трата для «Финансового помощника» (github.com/Lucky2356/financeapps) — в том виде,
 * в каком он её принимает. Номер — номер траты в Лоли: по нему помощник находит ту же
 * операцию, когда трату поправили или отменили, и не заводит вторую, если прислали снова.
 */
data class FinanceItem(
    val id: String,
    val op: Op,
    val amountMinor: Long = 0,
    val currency: String = "RUB",
    val category: String = "",
    val description: String = "",
    val date: LocalDate? = null,
) {
    enum class Op { UPSERT, DELETE }

    val income: Boolean get() = category == ExpenseCategories.INCOME

    fun toJson(): String = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("op", JsonPrimitive(if (op == Op.DELETE) "delete" else "upsert"))
        if (op == Op.UPSERT) {
            put("type", JsonPrimitive(if (income) "INCOME" else "EXPENSE"))
            put("amountMinor", JsonPrimitive(amountMinor))
            put("currency", JsonPrimitive(currency))
            put("category", JsonPrimitive(category))
            put("description", JsonPrimitive(description))
            put("date", JsonPrimitive(date.toString()))
        }
    }.toString()

    companion object {
        fun of(e: Expense) = FinanceItem(e.id, Op.UPSERT, e.amountMinor, e.currency, e.category, e.description, e.occurredOn)
        fun deleted(id: String) = FinanceItem(id, Op.DELETE)
    }
}

/** Куда уходят траты. Реализует Android-приложение (вызов помощника на том же телефоне). */
interface FinanceSink {
    /** Передавать ли сейчас: связь включена в настройках Лоли. */
    fun active(): Boolean

    /** Уходила ли уже эта трата в помощник: правку и отмену шлём только для таких. */
    fun known(id: String): Boolean

    /** Отправить. Не вышло — отправка сама повторит позже; трата в Лоли от этого не страдает. */
    suspend fun send(item: FinanceItem)
}

/**
 * Траты Лоли, которые заодно уходят в «Финансовый помощник».
 *
 * Новая трата — передаётся, если связь включена. Правка и удаление — только для тех,
 * что уже передавались: трата, записанная до включения связи, в помощнике не появится
 * задним числом от одной правки (там её, возможно, уже вписали руками).
 *
 * Сначала всегда пишется сама Лоли: связь не может ни сорвать запись, ни задержать её
 * ошибкой — сбой отправки только пишется в журнал.
 */
class FinanceLinkedExpenses(
    private val inner: ExpenseRepository,
    private val sink: FinanceSink,
) : ExpenseRepository by inner {

    override suspend fun create(amountMinor: Long, currency: String, category: String, description: String, occurredOn: LocalDate): Expense {
        val e = inner.create(amountMinor, currency, category, description, occurredOn)
        forward { if (sink.active()) sink.send(FinanceItem.of(e)) }
        return e
    }

    override suspend fun update(expense: Expense): Expense {
        val e = inner.update(expense)
        forward { if (sink.active() && sink.known(e.id)) sink.send(FinanceItem.of(e)) }
        return e
    }

    override suspend fun delete(id: String): Boolean {
        val ok = inner.delete(id)
        if (ok) forward { if (sink.active() && sink.known(id)) sink.send(FinanceItem.deleted(id)) }
        return ok
    }

    private suspend fun forward(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("FinanceLink", "Не передалось в Финансовый помощник", e)
        }
    }
}
