package ai.loli.core.personal

import ai.loli.core.domain.MemoryRepository
import ai.loli.core.model.MemoryItem
import ai.loli.core.nlp.Calculator
import ai.loli.core.nlp.RuTokenizer
import ai.loli.core.nlp.TextAnalysis

/** Похожи ли два названия (вещи, человека, списка) с точностью до падежа: «зарядку» = «зарядка», «Саше» = «Саша». */
internal fun sameName(a: String, b: String): Boolean {
    val x = TextAnalysis.stems(a)
    val y = TextAnalysis.stems(b)
    if (x.isEmpty() || y.isEmpty()) return RuTokenizer.normalize(a).trim() == RuTokenizer.normalize(b).trim()
    // Каждое значимое слово короткого названия есть в длинном: «паспорт» = «мой паспорт», «синяя папка» ≠ «папка с чеками».
    val (short, long) = if (x.size <= y.size) x to y else y to x
    return short.all { s -> long.any { l -> TextAnalysis.stemSimilarity(s, l) >= 0.75 || nameStem(s) == nameStem(l) } }
}

/** «саш» и «сашей»: у имён Snowball режет по-разному — сравниваем по первым буквам, если слово короткое. */
private fun nameStem(s: String): String = if (s.length <= 5) s.take(3) else s.take(s.length - 2)

/** «зарядку» → «Зарядка», «синюю папку» → «Синяя папка»: название вещи в именительном падеже (для обычных случаев). */
internal fun nominative(raw: String): String {
    val words = raw.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        .dropWhile { RuTokenizer.normalize(it) in POSSESSIVE }
    val out = words.map { w ->
        val l = w.lowercase()
        when {
            l.endsWith("ую") && l.length > 3 -> w.dropLast(2) + "ая"
            l.endsWith("юю") && l.length > 3 -> w.dropLast(2) + "яя"
            Regex("""[а-яё]+[бвгджзклмнпрстфхцчшщ]у""").matches(l) && l.length > 3 -> w.dropLast(1) + "а"
            Regex("""[а-яё]+[лн]ю""").matches(l) && l.length > 3 -> w.dropLast(1) + "я"
            else -> w
        }
    }
    return out.joinToString(" ").replaceFirstChar { it.uppercase() }
}

private val POSSESSIVE = setOf("мой", "моя", "моё", "мое", "мои", "мою", "моего", "моих", "наш", "наша", "наши", "нашу", "свой", "свою", "свои")

/**
 * «Где лежит»: куда вы положили вещь. Хранится в памяти (категория «Вещи»), одна запись на вещь —
 * новое место заменяет старое.
 */
class ThingsBook(private val memories: MemoryRepository) {
    data class Thing(val id: String, val item: String, val place: String)

    suspend fun all(): List<Thing> = memories.all().filter { it.category == CATEGORY }.mapNotNull(::parse)

    /** Запоминает место; возвращает текст записи. */
    suspend fun put(item: String, place: String): String {
        val name = nominative(item)
        all().filter { sameName(it.item, name) }.forEach { memories.delete(it.id) }
        val content = "$name — ${place.trim()}"
        memories.create(content, CATEGORY)
        return content
    }

    suspend fun find(query: String): List<Thing> {
        val q = query.trim().trimEnd('?', '.', '!')
        if (TextAnalysis.stems(q).isEmpty()) return emptyList()
        return all().filter { sameName(it.item, q) }
    }

    suspend fun forget(query: String): Int {
        val hits = find(query)
        hits.forEach { memories.delete(it.id) }
        return hits.size
    }

    private fun parse(m: MemoryItem): Thing? {
        val i = m.content.indexOf(" — ")
        if (i <= 0) return null
        return Thing(m.id, m.content.substring(0, i), m.content.substring(i + 3))
    }

    companion object {
        const val CATEGORY = "Вещи"
    }
}

/**
 * Долги: кто должен вам и кому должны вы. Одна запись в памяти на человека (категория «Долги»),
 * встречные долги взаимозачитываются: «Саша должен мне 500» + «я должна Саше 200» = Саша должен 300.
 */
class DebtBook(private val memories: MemoryRepository) {
    /** [amount] > 0 — должны вам, < 0 — должны вы. */
    data class Debt(val id: String, val person: String, val amount: Double)

    suspend fun all(): List<Debt> = memories.all().filter { it.category == CATEGORY }.mapNotNull { m ->
        val r = Regex("""^(Долг мне|Мой долг): (.+?) — (\d+(?:[.,]\d+)?) ₽""").find(m.content) ?: return@mapNotNull null
        val v = r.groupValues[3].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
        Debt(m.id, r.groupValues[2], if (r.groupValues[1] == "Долг мне") v else -v)
    }

    suspend fun find(person: String): Debt? = all().firstOrNull { sameName(it.person, person) }

    /** Добавляет долг (со знаком, как у [Debt.amount]); возвращает итог по человеку (null — в расчёте). */
    suspend fun add(person: String, delta: Double): Debt? {
        val old = find(person)
        // Имя оставляем в той форме, в какой его назвали впервые в именительном («Саша должен мне»), иначе — как сказали.
        val name = old?.person ?: person.trim().replaceFirstChar { it.uppercase() }
        val total = (old?.amount ?: 0.0) + delta
        old?.let { memories.delete(it.id) }
        if (Math.abs(total) < 0.005) return null
        val m = memories.create(content(name, total), CATEGORY)
        return Debt(m.id, name, total)
    }

    /**
     * Возврат: [theyPaid] — вернули вам, false — вернули вы, null — закрыть долг в любую сторону. [amount] = null — весь долг.
     * Возвращает (сколько засчитано, остаток) или null, если такого долга нет.
     */
    suspend fun settle(person: String, amount: Double?, theyPaid: Boolean?): Pair<Double, Debt?>? {
        val old = find(person) ?: return null
        val owedToMe = old.amount > 0
        if (theyPaid != null && theyPaid != owedToMe) return null
        val paid = amount?.coerceAtMost(Math.abs(old.amount)) ?: Math.abs(old.amount)
        val left = add(old.person, if (owedToMe) -paid else paid)
        return paid to left
    }

    companion object {
        const val CATEGORY = "Долги"

        fun money(v: Double): String = "${Calculator.format(Math.round(Math.abs(v) * 100) / 100.0)} ₽"

        private fun content(person: String, total: Double): String =
            if (total > 0) "Долг мне: $person — ${Calculator.format(Math.round(total * 100) / 100.0)} ₽"
            else "Мой долг: $person — ${Calculator.format(Math.round(-total * 100) / 100.0)} ₽"
    }
}

/** Готовые чек-листы: «собери список в отпуск». */
object ListTemplates {
    data class Template(val name: String, val items: List<String>)

    private val TEMPLATES = listOf(
        Regex("""мор|пляж|курорт""") to Template(
            "На море",
            listOf("Паспорт", "Билеты", "Купальник или плавки", "Солнцезащитный крем", "Солнечные очки", "Панама", "Пляжное полотенце", "Шлёпанцы", "Зарядка для телефона", "Аптечка"),
        ),
        Regex("""командировк""") to Template(
            "Командировка",
            listOf("Паспорт", "Билеты", "Ноутбук и зарядка", "Зарядка для телефона", "Документы для встречи", "Деловая одежда", "Бритва или косметичка", "Лекарства"),
        ),
        Regex("""больниц|стационар|госпитал""") to Template(
            "В больницу",
            listOf("Паспорт", "Полис ОМС", "СНИЛС", "Направление и медкарта", "Тапочки", "Халат или удобная одежда", "Кружка, ложка, тарелка", "Зарядка для телефона", "Зубная щётка и паста", "Лекарства, которые принимаете"),
        ),
        Regex("""поход|кемпинг|палатк""") to Template(
            "В поход",
            listOf("Палатка", "Спальник", "Коврик", "Фонарик", "Спички или зажигалка", "Аптечка", "Нож", "Вода", "Еда", "Дождевик", "Средство от комаров"),
        ),
        Regex("""переезд""") to Template(
            "Переезд",
            listOf("Коробки", "Скотч", "Маркер", "Плёнка для мебели", "Пакеты", "Заказать машину", "Передать показания счётчиков", "Сменить адрес в банке и на почте"),
        ),
        Regex("""поездк|путешеств|отпуск|дорог|отъезд""") to Template(
            "В поездку",
            listOf("Паспорт", "Билеты", "Деньги и карты", "Зарядка для телефона", "Наушники", "Лекарства", "Зубная щётка и паста", "Одежда на смену", "Подтверждение брони"),
        ),
    )

    fun find(text: String): Template? {
        val n = RuTokenizer.normalize(text)
        return TEMPLATES.firstOrNull { it.first.containsMatchIn(n) }?.second
    }
}
