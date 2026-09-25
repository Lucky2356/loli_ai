package ai.loli.core.nlp

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Money {
    val currencyWords: Map<String, String> = buildMap {
        listOf("руб", "рубль", "рубля", "рублей", "р", "₽", "rub", "рублях").forEach { put(it, "RUB") }
        listOf("долларов", "доллар", "доллара", "бакс", "баксов", "$", "usd").forEach { put(it, "USD") }
        listOf("евро", "€", "eur").forEach { put(it, "EUR") }
        listOf("тенге", "kzt").forEach { put(it, "KZT") }
        listOf("гривен", "гривна", "гривны", "uah").forEach { put(it, "UAH") }
        listOf("юаней", "юань", "юаня", "cny").forEach { put(it, "CNY") }
    }

    private val symbols = mapOf("RUB" to "₽", "USD" to "$", "EUR" to "€", "KZT" to "₸", "UAH" to "₴", "CNY" to "¥")

    fun toMinor(amount: Double): Long = BigDecimal(amount.toString()).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()

    fun format(amountMinor: Long, currency: String): String {
        val fmt = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale("ru", "RU")).apply { groupingSeparator = ' ' })
        val value = BigDecimal.valueOf(amountMinor).movePointLeft(2)
        return fmt.format(value) + " " + (symbols[currency] ?: currency)
    }
}
