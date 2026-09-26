package ai.loli.core.assistant

/**
 * Название задачи или напоминания — только действие в начальной форме:
 * «в задачу что мне нужно сходить в парикмахерскую» → «Сходить в парикмахерскую»,
 * «чтобы я забрала посылку» → «Забрать посылку», «схожу в банк» → «Сходить в банк».
 * Дата и время к этому моменту уже вынуты из текста разбором дат.
 */
object ActionTitle {
    private val COMMAND = Regex(
        """^(?:(?:лоли|лолли|лоля)[,\s]+)?(?:(?:добавь|добавить|запиши|записать|поставь|поставить|создай|создать|заведи|внеси|сделай)\s+)?(?:мне\s+)?(?:(?:в\s+)?(?:новую\s+)?(?:задачу|задачи|задачку|список\s+дел|дела|планы|туду)|новая\s+задача|задача)[:,]?\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val LINKS = Regex("""^(?:о\s+том,?\s+что|о\s+том,?\s+чтобы|про\s+то,?\s+что|что\s+бы|чтобы|что|о\s+том)[,:]?\s+""", RegexOption.IGNORE_CASE)
    private val PRONOUNS = Regex("""^(?:мне|нам|я|мы|себе|нужно\s+мне|надо\s+мне)\s+""", RegexOption.IGNORE_CASE)
    private val MODALS = Regex(
        """^(?:нужно|надо|необходимо|следует|должна|должен|должны|хочу|хотела\s+бы|хотел\s+бы|хочется|пора|стоит|бы|обязательно|не\s+забыть|не\s+забудь|точно|срочно|ещё|еще|уже|сегодня\s+же)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING = Regex("""[,\s]+(?:запиши|добавь|запомни|сохрани|поставь)(?:\s+(?:это|в\s+задачи|задачу|в\s+дела))?\s*$""", RegexOption.IGNORE_CASE)

    /** Будущее время 1-го лица → инфинитив. */
    private val FUTURE = mapOf(
        "схожу" to "сходить", "позвоню" to "позвонить", "куплю" to "купить", "заберу" to "забрать", "запишусь" to "записаться",
        "оплачу" to "оплатить", "отвезу" to "отвезти", "заеду" to "заехать", "сделаю" to "сделать", "напишу" to "написать",
        "отправлю" to "отправить", "приготовлю" to "приготовить", "уберу" to "убрать", "помою" to "помыть", "постираю" to "постирать",
        "погуляю" to "погулять", "съезжу" to "съездить", "поеду" to "поехать", "пойду" to "пойти", "зайду" to "зайти",
        "встречусь" to "встретиться", "закажу" to "заказать", "продлю" to "продлить", "сдам" to "сдать", "починю" to "починить",
        "найду" to "найти", "проверю" to "проверить", "выучу" to "выучить", "отнесу" to "отнести", "принесу" to "принести",
        "подготовлю" to "подготовить", "сниму" to "снять", "верну" to "вернуть", "поменяю" to "поменять", "заплачу" to "заплатить",
        "забронирую" to "забронировать", "запишу" to "записать", "поздравлю" to "поздравить", "полью" to "полить", "выброшу" to "выбросить",
        "вынесу" to "вынести", "посмотрю" to "посмотреть", "прочитаю" to "прочитать", "разберу" to "разобрать", "соберу" to "собрать",
        "покормлю" to "покормить", "отведу" to "отвести", "приведу" to "привести", "успею" to "успеть",
        "займусь" to "заняться", "запущу" to "запустить", "получу" to "получить", "передам" to "передать", "отдам" to "отдать",
        "схожу-ка" to "сходить", "сходим" to "сходить", "позвоним" to "позвонить", "купим" to "купить", "поедем" to "поехать",
    )

    fun clean(raw: String): String {
        var t = raw.trim().trim(',', '.', ' ', '«', '»', '"')
        var afterPronoun = false
        repeat(4) {
            val before = t
            t = t.replace(TRAILING, "").trim()
            t = t.replace(COMMAND, "").trim()
            t = t.replace(LINKS, "").trim()
            PRONOUNS.find(t)?.let { m -> afterPronoun = true; t = t.substring(m.range.last + 1).trim() }
            t = t.replace(MODALS, "").trim()
            t = t.trim(',', ':', ' ')
            if (t == before) return@repeat
        }
        if (t.isEmpty()) return raw.trim().replaceFirstChar { it.uppercase() }
        val first = t.substringBefore(' ')
        val rest = t.substring(first.length)
        val lower = first.lowercase().replace('ё', 'е')
        val verb = FUTURE[lower] ?: if (afterPronoun) pastToInfinitive(lower) else null
        if (verb != null) t = verb + rest
        return t.replaceFirstChar { it.uppercase() }
    }

    /** «забрала» → «забрать», «оплатил» → «оплатить», «записалась» → «записаться» (только после «я», «чтобы я»). */
    private fun pastToInfinitive(w: String): String? {
        val reflexive = w.endsWith("лась") || w.endsWith("лся")
        val base = when {
            w.endsWith("лась") -> w.dropLast(4)
            w.endsWith("лся") -> w.dropLast(3)
            w.endsWith("ла") -> w.dropLast(2)
            w.endsWith("л") -> w.dropLast(1)
            else -> return null
        }
        if (base.length < 3 || base.last() !in "аеиоуяы") return null
        return base + "ть" + (if (reflexive) "ся" else "")
    }
}
