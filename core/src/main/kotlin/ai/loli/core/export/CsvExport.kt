package ai.loli.core.export

import ai.loli.core.model.Expense
import ai.loli.core.model.TaskItem
import java.time.LocalDate
import java.time.ZoneId

/**
 * Выгрузка в таблицу (CSV для Excel и Google Таблиц). Разделитель «;» и метка BOM —
 * так русский Excel открывает файл двойным щелчком сразу по столбцам и без «кракозябр».
 */
object CsvExport {
    private const val BOM = "﻿"

    fun cell(v: String): String {
        val needsQuotes = v.contains(';') || v.contains('"') || v.contains('\n') || v.contains('\r')
        // Формулы в чужих таблицах — частая дыра: «=…» в ячейке Excel выполнит. Экранируем апострофом.
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
        return if (needsQuotes || safe != v) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun row(vararg cells: String) = cells.joinToString(";") { cell(it) }

    /** 12345 копеек → «123,45»: русский Excel понимает запятую как дробную часть. */
    private fun amount(minor: Long): String = java.math.BigDecimal.valueOf(minor, 2).toPlainString().replace('.', ',')

    fun expenses(list: List<Expense>): String = BOM + buildString {
        appendLine(row("Дата", "Категория", "Описание", "Сумма", "Валюта"))
        list.sortedBy { it.occurredOn }.forEach { e -> appendLine(row(e.occurredOn.toString(), e.category, e.description, amount(e.amountMinor), e.currency)) }
    }

    fun tasks(list: List<TaskItem>, zone: ZoneId): String = BOM + buildString {
        appendLine(row("Задача", "Подробности", "Срок", "Время", "Выполнена", "Когда выполнена", "Создана"))
        list.sortedBy { it.createdAt }.forEach { t ->
            appendLine(
                row(
                    t.title, t.details, t.dueDate?.toString().orEmpty(), t.dueTime?.toString().orEmpty(), if (t.done) "да" else "нет",
                    t.completedAt?.atZone(zone)?.toLocalDate()?.toString().orEmpty(), t.createdAt.atZone(zone).toLocalDate().toString(),
                ),
            )
        }
    }

    /** Долги: [amount] > 0 — должны вам. */
    fun debts(list: List<Pair<String, Double>>): String = BOM + buildString {
        appendLine(row("Человек", "Кто должен", "Сумма, ₽"))
        list.forEach { (who, v) -> appendLine(row(who, if (v > 0) "вам" else "вы", String.format(java.util.Locale("ru"), "%.2f", Math.abs(v)))) }
    }

    fun fileName(kind: String, from: LocalDate?, to: LocalDate?): String =
        "loli-$kind" + (if (from != null && to != null) "-$from-$to" else "-" + LocalDate.now()) + ".csv"
}
