package ai.loli.core.skills

import ai.loli.core.assistant.RuFormat
import ai.loli.core.data.LoliJson
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Вопрос о курсе: «курс доллара», «сколько будет 100 евро в рублях», «сколько долларов в 5000 рублей». */
data class RatesQuery(
    /** Коды валют (USD, EUR…); пусто — основные. */
    val currencies: List<String> = emptyList(),
    /** Пересчёт: сумма и направление. */
    val amount: Double? = null,
    /** Сумма указана в рублях — пересчитать в валюту. */
    val fromRub: Boolean = false,
)

/** Курсы ЦБ РФ (cbr-xml-daily.ru) и криптовалюты (CoinGecko) — бесплатно и без ключа. */
class RatesService(private val http: HttpClient) {
    data class Rate(val code: String, val name: String, val nominal: Int, val value: Double, val previous: Double?)
    data class Board(val date: LocalDate?, val rates: Map<String, Rate>)

    private val cbrCache = TtlCache<String, Board>(30 * 60_000L)
    private val cryptoCache = TtlCache<String, Map<String, Double>>(10 * 60_000L)

    suspend fun cbr(): Board = cbrCache.get("cbr") ?: parseCbr(http.fetchText("https://www.cbr-xml-daily.ru/daily_json.js")).also { cbrCache.put("cbr", it) }

    /** Цена криптовалют в рублях: BTC, ETH. */
    suspend fun crypto(): Map<String, Double> = cryptoCache.get("c") ?: parseCrypto(
        http.fetchText("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,tether&vs_currencies=rub"),
    ).also { cryptoCache.put("c", it) }

    suspend fun answer(q: RatesQuery): String {
        val crypto = q.currencies.filter { it in CRYPTO }
        val fiat = q.currencies.filter { it !in CRYPTO }
        val board = if (fiat.isNotEmpty() || crypto.isEmpty()) cbr() else null
        val coins = if (crypto.isNotEmpty()) crypto() else emptyMap()
        return format(q, board, coins)
    }

    companion object {
        private val ru = Locale("ru")
        private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", ru)
        val CRYPTO = setOf("BTC", "ETH", "USDT")
        val MAIN = listOf("USD", "EUR", "CNY")

        /** Слова → коды валют. Основы слов, чтобы подходили все падежи. */
        val NAMES: List<Pair<Regex, String>> = listOf(
            "доллар|бакс|usd" to "USD",
            "евро|eur" to "EUR",
            "юан|cny" to "CNY",
            "фунт|gbp" to "GBP",
            "иен|йен|jpy" to "JPY",
            "тенге|kzt" to "KZT",
            "белорусск\\S*\\s+рубл|byn" to "BYN",
            "гривн|uah" to "UAH",
            "лир(?:а|ы|ах|у)?(?![\\p{L}])" to "TRY",
            "франк|chf" to "CHF",
            "дирхам|aed" to "AED",
            "сом(?:а|ов|ы)?(?![\\p{L}])|kgs" to "KGS",
            "сум(?:а|ов|ы)?(?![\\p{L}])|uzs" to "UZS",
            "драм(?:а|ов|ы)?(?![\\p{L}])|amd" to "AMD",
            "лари|gel" to "GEL",
            "манат|azn" to "AZN",
            "рупи|inr" to "INR",
            "вон(?:а|ы)?(?![\\p{L}])|krw" to "KRW",
            "злот|pln" to "PLN",
            "бат(?:а|ов|ы)?(?![\\p{L}])|thb" to "THB",
            "биткоин|биткойн|битк|btc" to "BTC",
            "эфир|ethereum|eth" to "ETH",
            "тезер|usdt" to "USDT",
        ).map { (p, c) -> Regex("""(?<![\p{L}])(?:$p)""", RegexOption.IGNORE_CASE) to c }

        fun codesIn(text: String): List<String> {
            val found = ArrayList<Pair<Int, String>>()
            for ((re, code) in NAMES) re.find(text)?.let { found += it.range.first to code }
            // «белорусский рубль» — не рубль вообще.
            return found.sortedBy { it.first }.map { it.second }.distinct()
        }

        fun parseCbr(json: String): Board {
            val root = LoliJson.parseToJsonElement(json).jsonObject
            val date = root.text("Date")?.let { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }
            val v = root["Valute"] as? JsonObject ?: return Board(date, emptyMap())
            val rates = v.mapNotNull { (code, e) ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val value = o.num("Value") ?: return@mapNotNull null
                code to Rate(code, o.text("Name") ?: code, o.num("Nominal")?.toInt() ?: 1, value, o.num("Previous"))
            }.toMap()
            return Board(date, rates)
        }

        fun parseCrypto(json: String): Map<String, Double> {
            val root = LoliJson.parseToJsonElement(json).jsonObject
            fun price(id: String) = (root[id] as? JsonObject)?.num("rub")
            return buildMap {
                price("bitcoin")?.let { put("BTC", it) }
                price("ethereum")?.let { put("ETH", it) }
                price("tether")?.let { put("USDT", it) }
            }
        }

        /** 92.3456 → «92,35»; большие числа — с пробелами: «7 102 299». */
        fun money(v: Double, cents: Boolean = false): String {
            val a = abs(v)
            val s = when {
                a >= 1000 -> String.format(ru, "%,.0f", v)
                !cents && v % 1.0 == 0.0 -> String.format(ru, "%,.0f", v)
                a >= 10 || cents -> String.format(ru, "%,.2f", v)
                else -> String.format(ru, "%,.4f", v).trimEnd('0').trimEnd(',')
            }
            return s.replace(' ', ' ').replace(' ', ' ')
        }

        private fun currencyName(code: String, n: Double): String {
            val whole = n % 1.0 == 0.0
            val k = n.toLong()
            fun pl(one: String, few: String, many: String) = if (!whole) few else RuFormat.plural(k, one, few, many)
            return when (code) {
                "USD" -> pl("доллар", "доллара", "долларов")
                "EUR" -> "евро"
                "CNY" -> pl("юань", "юаня", "юаней")
                "GBP" -> pl("фунт", "фунта", "фунтов")
                "JPY" -> pl("иена", "иены", "иен")
                "KZT" -> "тенге"
                "BYN" -> pl("белорусский рубль", "белорусских рубля", "белорусских рублей")
                "UAH" -> pl("гривна", "гривны", "гривен")
                "TRY" -> pl("лира", "лиры", "лир")
                "CHF" -> pl("франк", "франка", "франков")
                "AED" -> pl("дирхам", "дирхама", "дирхамов")
                "BTC" -> pl("биткоин", "биткоина", "биткоинов")
                "ETH" -> "эфира"
                "USDT" -> "USDT"
                else -> code
            }
        }

        private fun nameGen(code: String, rate: Rate?): String = when (code) {
            "USD" -> "доллар"; "EUR" -> "евро"; "CNY" -> "юань"; "GBP" -> "фунт"; "JPY" -> "иена"; "KZT" -> "тенге"
            "BYN" -> "белорусский рубль"; "UAH" -> "гривна"; "TRY" -> "лира"; "CHF" -> "франк"; "AED" -> "дирхам"
            "BTC" -> "биткоин"; "ETH" -> "эфир"; "USDT" -> "USDT"
            else -> rate?.name?.lowercase() ?: code
        }

        private fun perUnit(r: Rate): Double = r.value / r.nominal

        fun format(q: RatesQuery, board: Board?, coins: Map<String, Double>): String {
            val codes = q.currencies.ifEmpty { MAIN }
            fun priceRub(code: String): Double? = coins[code] ?: board?.rates?.get(code)?.let(::perUnit)
            // Пересчёт.
            q.amount?.let { amount ->
                val code = codes.first()
                val p = priceRub(code) ?: return "Не нашла курс ${nameGen(code, null)}."
                return if (q.fromRub) {
                    val out = amount / p
                    "${money(amount)} ₽ — это ${money(out)} ${currencyName(code, out)} по курсу ${if (code in CRYPTO) "биржи" else "ЦБ"}."
                } else {
                    val out = amount * p
                    "${money(amount)} ${currencyName(code, amount)} — это ${money(out)} ₽ по курсу ${if (code in CRYPTO) "биржи" else "ЦБ"}."
                }
            }
            val lines = codes.mapNotNull { code ->
                if (code in CRYPTO) {
                    coins[code]?.let { "${nameGen(code, null).replaceFirstChar { it.uppercase() }} — ${money(it)} ₽" }
                } else {
                    val r = board?.rates?.get(code) ?: return@mapNotNull null
                    val unit = if (r.nominal == 1) "" else "за ${r.nominal} "
                    val diff = r.previous?.let { r.value - it }?.takeIf { abs(it) >= 0.005 }
                    val trend = diff?.let { d -> if (d > 0) " (выше на ${money(d, true)})" else " (ниже на ${money(-d, true)})" } ?: ""
                    val label = if (r.nominal == 1) nameGen(code, r).replaceFirstChar { it.uppercase() } else r.name
                    "$label — $unit${money(r.value)} ₽$trend"
                }
            }
            if (lines.isEmpty()) return "Не нашла такую валюту в курсах ЦБ."
            val head = if (codes.all { it in CRYPTO }) "Курс" else "Курс ЦБ${board?.date?.let { " на ${dayMonth.format(it)}" } ?: ""}"
            return if (lines.size == 1) "$head: ${lines.single()}." else "$head:\n" + lines.joinToString("\n")
        }
    }
}
