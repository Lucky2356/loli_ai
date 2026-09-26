package ai.loli.core.assistant

import ai.loli.core.TestEnv
import ai.loli.core.ai.ChatMessage
import ai.loli.core.ai.LocalChat
import ai.loli.core.skills.ActiveTimer
import ai.loli.core.skills.CalendarItem
import ai.loli.core.skills.ContactInfo
import ai.loli.core.skills.IncomingMessage
import ai.loli.core.skills.PlaceReminder
import ai.loli.core.skills.RadioStation
import ai.loli.core.skills.SavedPlace
import ai.loli.core.skills.SkillHost
import ai.loli.core.skills.Skills
import ai.loli.core.model.NoteKind
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.fail

/**
 * Корпус живых фраз: каждая проходит весь путь (навыки → разбор команд → офлайн-модель) и должна
 * попасть в нужное намерение. Защищает от того, что новая функция «перехватит» чужие фразы.
 * Сегодня пятница 25.09.2026, 12:00 МСК. Облачного AI нет, офлайн-модель есть (подделка).
 */
class CorpusTest {
    private class Recorder : SkillHost, DeviceController {
        val calls = ArrayList<String>()
        override suspend fun perform(command: DeviceCommand): DeviceResult { calls += "device:" + command::class.simpleName; return DeviceResult("ок") }
        override suspend fun location() = null
        override suspend fun messages(): List<IncomingMessage> { calls += "skill:messages"; return listOf(IncomingMessage("k", "Telegram", "Маша", "привет", Instant.EPOCH, true)) }
        override suspend fun reply(message: IncomingMessage, text: String): Boolean { calls += "skill:reply"; return true }
        override suspend fun screenText(): String { calls += "skill:screen"; return "Текст на экране" }
        override suspend fun contact(name: String): ContactInfo { calls += "skill:contact"; return ContactInfo("Мама", listOf("+79160000000")) }
        override suspend fun calendar(from: Instant, to: Instant): List<CalendarItem> { calls += "skill:calendar"; return emptyList() }
        override suspend fun playRadio(station: RadioStation): Boolean { calls += "skill:radio"; return true }
        override fun stopRadio(): Boolean { calls += "skill:radio"; return true }
        override suspend fun ringPhone(): Boolean { calls += "skill:find"; return true }
        override fun timers(): List<ActiveTimer> { calls += "skill:timers"; return emptyList() }
        override fun cancelTimers(label: String?): Int { calls += "skill:timers"; return 1 }
        override suspend fun savePlace(name: String): SavedPlace { calls += "skill:place"; return SavedPlace(name, 1.0, 1.0) }
        override fun places(): List<SavedPlace> = listOf(SavedPlace("дом", 1.0, 1.0), SavedPlace("работа", 2.0, 2.0))
        override suspend fun addPlaceReminder(text: String, place: String, onLeave: Boolean): PlaceReminder { calls += "skill:place"; return PlaceReminder("p", text, place, onLeave) }
        override fun setUserName(name: String?) { calls += "skill:name" }
        override fun setCity(city: String?) { calls += "skill:city" }
    }

    private class Chat : LocalChat {
        var asked = 0
        override val available = true
        override suspend fun reply(system: String, history: List<ChatMessage>, maxTokens: Int): String { asked++; return "Ответ модели." }
    }

    private suspend fun classify(phrase: String): String {
        val rec = Recorder()
        val chat = Chat()
        val env = TestEnv(device = rec, skillsFactory = { t -> Skills(rec, null, t) }, localChat = chat)
        env.settings = AssistantSettings(useAI = false)
        val reply = env.engine.handle(phrase)
        val t = reply.text
        return when {
            chat.asked > 0 -> "chat"
            env.store.expenses.all().isNotEmpty() -> "expense"
            env.store.tasks.all().isNotEmpty() -> "task"
            env.store.reminders.all().isNotEmpty() -> "reminder"
            env.store.shopping.all().isNotEmpty() -> "list"
            env.store.memories.all().isNotEmpty() -> "memory"
            env.store.notes.all().any { it.kind == NoteKind.IDEA } -> "idea"
            env.store.notes.all().isNotEmpty() -> "note"
            t.contains("В Википедии") -> "skill:fact"
            rec.calls.isNotEmpty() -> rec.calls.first()
            t.startsWith("Чтобы узнать погоду") -> "skill:weather"
            t.startsWith("Чтобы узнать курс") -> "skill:rates"
            t.startsWith("Чтобы узнать новости") -> "skill:news"
            t.startsWith("Играем в города") || t.contains("загадала число") || t.startsWith("Отгадайте загадку") || t.contains("Во что играем") -> "skill:game"
            Regex("""^«[^»]+»\. """).containsMatchIn(t) -> "skill:tale"
            t.contains("Не совсем поняла") || t.contains("Сохранить это как заметку") || t.startsWith("Не поняла") -> "unknown"
            else -> "answer"
        }
    }

    private val corpus: List<Pair<String, String>> = buildList {
        // ---------------- Расходы
        listOf("потратила 500 на продукты", "потратил 1200 рублей на бензин", "купила кофе за 250", "такси 380", "закинула в продуктовый 850",
            "заплатил за интернет 600 рублей", "обед 450 рублей", "вчера потратила 2000 на одежду", "запиши расход 300 на кофе", "расход 1500 аптека",
            "отдала 700 за стрижку", "заправка 2500", "оплатила коммуналку 5400", "потратил полтинник на хлеб", "купил билеты в кино за 900",
            "потратила 3 тысячи на подарок маме", "добавь расход 120 рублей на проезд", "2 дня назад потратил 500 на такси", "кофе 180", "пицца 890 рублей")
            .forEach { add(it to "expense") }
        // ---------------- Задачи
        listOf("добавь задачу купить подарок", "поставь задачу позвонить врачу завтра", "нужно в понедельник сходить в зал", "запиши задачу оплатить интернет",
            "добавь в задачи сдать отчёт в пятницу", "новая задача: отвезти документы", "надо не забыть забрать посылку, добавь задачу",
            "задача на завтра купить корм коту", "мне нужно завтра заехать в банк, добавь задачу", "создай задачу записаться к стоматологу",
            "добавь в планы на завтра сходить в аптеку", "добавь задачу помыть машину на выходных", "Лоли, поставь задачу сходить на стрижку",
            "добавь в задачу что мне завтра нужно сходить в парикмахерскую", "запланируй на среду встречу с юристом")
            .forEach { add(it to "task") }
        // ---------------- Напоминания
        listOf("напомни через 10 минут поставить чайник", "напомни завтра в 9 позвонить маме", "напомни в 18:30 выключить духовку",
            "напомни через час выпить воды", "напомни в пол шестого вечера забрать сына", "каждый понедельник в 9 напоминай про планёрку",
            "напомни послезавтра в 10 оплатить квартиру", "напомни через 2 часа проверить почту", "напомни мне в 20:00 принять таблетки",
            "напомни в субботу в 11 утра позвонить бабушке", "по будням в 8:30 напоминай выпить витамины", "напомни через полчаса снять бельё")
            .forEach { add(it to "reminder") }
        // ---------------- Заметки, идеи, память, списки
        listOf("запиши заметку купить новые шторы в спальню", "создай заметку идеи для дня рождения").forEach { add(it to "note") }
        listOf("у меня идея: приложение для холодильника", "запиши идею сделать сайт для мамы").forEach { add(it to "idea") }
        listOf("запомни, что я не ем сладкое", "запомни что у мамы аллергия на орехи", "запомни, мой любимый цвет зелёный").forEach { add(it to "memory") }
        listOf("добавь в покупки молоко, хлеб и яйца", "добавь в список покупок сыр", "купить: картошку, лук, морковь", "добавь в покупки корм для кота")
            .forEach { add(it to "list") }
        // ---------------- Телефон
        listOf("поставь таймер на 5 минут", "засеки 10 минут", "таймер на полчаса", "таймер на пасту 8 минут").forEach { add(it to "device:Timer") }
        listOf("разбуди меня в 7", "поставь будильник на 6:30", "будильник на 8 по будням").forEach { add(it to "device:Alarm") }
        listOf("позвони маме", "набери папу", "позвони на 89161234567").forEach { add(it to "device:Call") }
        listOf("напиши саше что я задержусь", "отправь смс маме я еду").forEach { add(it to "device:Message") }
        listOf("открой телеграм", "запусти калькулятор", "открой ютуб").forEach { add(it to "device:OpenApp") }
        listOf("включи фонарик", "выключи фонарик").forEach { add(it to "device:Flashlight") }
        listOf("следующий трек", "поставь на паузу", "продолжи музыку").forEach { add(it to "device:Media") }
        listOf("сделай погромче", "громкость на 50 процентов", "выключи звук").forEach { add(it to "device:Volume") }
        add("включи песню queen" to "device:Play")
        add("включи музыку" to "device:Media")
        listOf("найди в интернете рецепт борща", "загугли курс биткоина на бирже").forEach { add(it to "device:WebSearch") }
        listOf("построй маршрут до вокзала", "как доехать до аэропорта").forEach { add(it to "device:Navigate") }
        add("сколько заряда" to "device:Battery")
        add("сделай селфи" to "device:Camera")
        add("сделай скриншот" to "device:Global")
        add("включи не беспокоить" to "device:DoNotDisturb")
        // ---------------- Навыки с интернетом
        listOf("какая погода", "какая погода завтра", "погода в Казани", "будет ли завтра дождь", "нужен ли зонт", "сколько градусов на улице",
            "погода на выходные", "какая погода в субботу в Сочи", "холодно ли сегодня на улице", "что надеть сегодня")
            .forEach { add(it to "skill:weather") }
        listOf("курс доллара", "курсы валют", "почём евро", "сколько стоит биткоин", "сколько будет 100 долларов в рублях", "курс юаня")
            .forEach { add(it to "skill:rates") }
        listOf("новости", "расскажи новости", "последние новости спорта", "что нового в мире").forEach { add(it to "skill:news") }
        listOf("кто такой Гагарин", "что такое фотосинтез", "расскажи про Байкал", "кто написал войну и мир").forEach { add(it to "skill:fact") }
        // ---------------- Навыки на телефоне
        listOf("прочитай сообщения", "что мне пришло", "прочитай сообщения от маши", "есть новые сообщения").forEach { add(it to "skill:messages") }
        listOf("что на экране", "прочитай экран").forEach { add(it to "skill:screen") }
        listOf("какой номер у мамы", "продиктуй номер папы").forEach { add(it to "skill:contact") }
        listOf("что у меня в календаре завтра", "когда ближайшая встреча").forEach { add(it to "skill:calendar") }
        listOf("включи радио", "включи радио европа плюс", "выключи радио", "включи русское радио").forEach { add(it to "skill:radio") }
        listOf("где ты", "найди мой телефон").forEach { add(it to "skill:find") }
        listOf("сколько осталось на таймере", "отмени таймер").forEach { add(it to "skill:timers") }
        listOf("запомни, я дома", "напомни, когда буду дома, позвонить маме", "напомни купить хлеб, когда выйду с работы").forEach { add(it to "skill:place") }
        add("называй меня Лёша" to "skill:name")
        add("я живу в Казани" to "skill:city")
        listOf("давай в города", "загадай число", "загадай загадку", "давай поиграем").forEach { add(it to "skill:game") }
        listOf("расскажи сказку", "расскажи сказку про колобка").forEach { add(it to "skill:tale") }
        // ---------------- Вопросы к записям и быстрые ответы
        listOf("что у меня на сегодня", "сколько я потратила в этом месяце", "какие задачи на сегодня", "что ты умеешь", "который час",
            "какое сегодня число", "сколько будет 15 процентов от 2000", "сколько дней до нового года", "подбрось монетку", "время в Токио",
            "расскажи анекдот", "переведи 5 миль в километры", "мои напоминания", "что ты обо мне знаешь")
            .forEach { add(it to "answer") }
        // ---------------- Свободные вопросы → офлайн-модель
        listOf("почему небо голубое", "как приготовить плов", "посоветуй фильм на вечер", "что лучше чай или кофе", "объясни что такое инфляция простыми словами",
            "придумай поздравление для мамы", "как перестать откладывать дела", "можно ли кормить кота рыбой", "сколько воды нужно пить в день",
            "что подарить другу на день рождения", "как быстро уснуть", "почему кошки мурлычут")
            .forEach { add(it to "chat") }

        // ---------------- Шаблоны: разные формулировки, суммы, сроки, города, люди
        val amounts = listOf("150", "300", "450", "1200", "2500", "пятьсот")
        val spendOn = listOf("продукты", "такси", "кафе", "бензин", "одежду", "лекарства", "подарок", "обед")
        amounts.forEachIndexed { i, a ->
            add("потратила $a на ${spendOn[i]}" to "expense")
            add("потратил $a рублей на ${spendOn[(i + 3) % spendOn.size]}" to "expense")
            add("заплатила $a за ${listOf("интернет", "телефон", "свет", "парковку", "кружок", "курсы")[i]}" to "expense")
            add("${spendOn[(i + 5) % spendOn.size]} $a" to "expense")
        }
        val todo = listOf("позвонить маме", "выключить плиту", "забрать посылку", "выпить таблетки", "полить цветы", "оплатить счёт", "купить хлеб", "проверить почту")
        val whenR = listOf("через 5 минут", "через час", "завтра в 9", "в 18:00", "послезавтра в 10 утра", "в понедельник в 8", "через 20 минут", "сегодня в 21:30")
        todo.forEachIndexed { i, w ->
            add("напомни ${whenR[i]} $w" to "reminder")
            add("напомни мне ${whenR[(i + 3) % whenR.size]} $w" to "reminder")
        }
        val jobs = listOf("купить подарок", "записаться к врачу", "сдать отчёт", "помыть машину", "починить кран", "заказать продукты", "продлить страховку", "оплатить садик", "позвонить в банк", "отвезти документы")
        val whenT = listOf("", " на завтра", " в пятницу", " на следующей неделе")
        jobs.forEachIndexed { i, j -> add("добавь задачу $j${whenT[i % whenT.size]}" to "task"); add("поставь задачу $j${whenT[(i + 1) % whenT.size]}" to "task") }
        listOf(1, 2, 3, 5, 7, 10, 15, 20, 25, 40).forEach { n -> add("поставь таймер на $n минут" to "device:Timer") }
        listOf(30, 45, 90).forEach { n -> add("засеки $n секунд" to "device:Timer") }
        val cities = listOf("Москве", "Казани", "Сочи", "Новосибирске", "Екатеринбурге", "Самаре", "Краснодаре", "Владивостоке")
        cities.forEachIndexed { i, c ->
            add("какая погода в $c" to "skill:weather")
            add("погода ${listOf("завтра", "в субботу", "на выходные", "сегодня")[i % 4]} в $c" to "skill:weather")
        }
        listOf("доллара", "евро", "юаня", "фунта", "тенге", "лиры").forEach { c -> add("курс $c" to "skill:rates"); add("какой сейчас курс $c" to "skill:rates") }
        listOf("100 долларов", "50 евро", "1000 юаней").forEach { c -> add("сколько будет $c в рублях" to "skill:rates") }
        val people = listOf("маме", "папе", "бабушке", "Саше", "Диме", "жене", "брату", "сестре")
        people.forEach { p -> add("позвони $p" to "device:Call"); add("набери $p" to "device:Call") }
        listOf("телеграм", "ватсап", "вконтакте", "ютуб", "яндекс карты", "авито", "озон", "вайлдберриз").forEach { a -> add("открой $a" to "device:OpenApp") }
        listOf("молоко, хлеб и яйца", "сыр и масло", "корм для кота", "туалетную бумагу", "яблоки и бананы", "кофе", "стиральный порошок", "подгузники")
            .forEach { add("добавь в покупки $it" to "list") }
        listOf("Пушкин", "Эйнштейн", "Менделеев", "Илон Маск", "Чайковский").forEach { add("кто такой $it" to "skill:fact") }
        listOf("квантовый компьютер", "блокчейн", "ипотека", "чёрная дыра", "метаболизм").forEach { add("что такое $it" to "skill:fact") }
        listOf("небо голубое", "листья желтеют осенью", "люди зевают", "море солёное", "собаки лают", "хочется спать после обеда").forEach { add("почему $it" to "chat") }
        listOf("сварить борщ", "выучить английский", "накопить на отпуск", "бросить курить", "успокоиться перед экзаменом", "выбрать ноутбук", "помириться с другом", "вывести пятно с футболки")
            .forEach { add("как $it" to "chat") }
        listOf("фильм на вечер", "книгу про космос", "подарок маме", "чем заняться в выходные", "что приготовить на ужин").forEach { add("посоветуй $it" to "chat") }
        listOf("кошка или собака" to "кто лучше", "айфон или андроид" to "что лучше", "чай или кофе" to "что полезнее").forEach { (x, q) -> add("$q $x" to "chat") }
        listOf("шутку про программистов", "стих про осень", "поздравление с днём рождения", "тост на свадьбу").forEach { add("придумай $it" to "chat") }

        // ---------------- Ещё формулировки
        val buys = listOf("хлеб" to "60", "молоко" to "95", "сыр" to "420", "кофе" to "350", "билет" to "1100", "цветы" to "1500", "книгу" to "700", "наушники" to "3200")
        buys.forEach { (what, sum) -> add("купила $what за $sum" to "expense"); add("купил $what за $sum рублей" to "expense") }
        listOf("такси до работы 420", "обед в столовой 310", "бензин 3000", "стрижка 1200", "аптека 870", "кино 600", "продукты 2300", "парковка 200")
            .forEach { add(it to "expense") }
        listOf("через 15 минут", "через 3 часа", "завтра утром в 8", "в 7 вечера", "в пятницу в 12").forEach { w ->
            add("напомни $w вынести мусор" to "reminder")
            add("напомни $w позвонить в поликлинику" to "reminder")
        }
        listOf("сходить в спортзал", "купить продукты на неделю", "прочитать книгу", "записать ребёнка в кружок", "поменять резину").forEach { j ->
            add("мне нужно завтра $j" to "task")
            add("добавь в список дел $j" to "task")
        }
        listOf("сделай тише", "сделай громче", "громкость на максимум", "убавь звук").forEach { add(it to "device:Volume") }
        listOf("открой настройки wifi", "открой настройки блютуз", "открой настройки батареи").forEach { add(it to "device:OpenSettings") }
        listOf("сделай фото", "включи камеру").forEach { add(it to "device:Camera") }
        listOf("предыдущий трек", "пауза", "следующая песня").forEach { add(it to "device:Media") }
        listOf("яркость на 30 процентов", "сделай экран ярче").forEach { add(it to "device:Brightness") }
        listOf("назад", "домой", "заблокируй экран", "открой уведомления").forEach { add(it to "device:Global") }
        listOf("будет ли дождь в Москве", "нужен ли зонт завтра", "какая температура сейчас", "пойдёт ли снег в субботу", "погода на неделю", "как одеться сегодня")
            .forEach { add(it to "skill:weather") }
        listOf("новости спорта", "новости науки", "главные новости", "новости экономики", "что нового в спорте").forEach { add(it to "skill:news") }
        listOf("сколько я потратила на продукты", "сколько я потратил вчера", "на что я больше всего трачу", "какие задачи на завтра",
            "покажи просроченные задачи", "что купить", "какие у меня напоминания", "сколько дней до 8 марта", "какой день недели 31 декабря",
            "сколько будет 250 умножить на 4", "брось кубик", "случайное число от 1 до 100", "время в Нью-Йорке", "привет", "спасибо", "как дела")
            .forEach { add(it to "answer") }
        listOf("что посмотреть вечером", "как научиться плавать", "почему луна меняет форму", "зачем нужен сон", "объясни как работает интернет",
            "как похудеть без диет", "что почитать про историю", "как научиться готовить", "что лучше купить телевизор или проектор",
            "как правильно заваривать чай", "сочини стишок для дочки", "как успокоить ребёнка перед сном", "что делать если болит голова",
            "как стать увереннее", "почему небо ночью тёмное", "как вырастить помидоры на балконе", "посоветуй хобби", "как запомнить английские слова",
            "что приготовить из курицы", "как экономить деньги")
            .forEach { add(it to "chat") }
        listOf("что я люблю пить по утрам" to "answer", "запомни что день рождения у брата 3 мая" to "reminder", "запомни, пароль от вайфая на холодильнике" to "memory",
            "запомни что машина на парковке у третьего подъезда" to "memory")
            .forEach { add(it) }
        listOf("запиши заметку список фильмов на выходные", "создай заметку рецепт блинов", "запиши заметку размер обуви сына 32").forEach { add(it to "note") }
        listOf("есть идея: сделать календарь для бабушки", "идея: открыть кофейню у дома").forEach { add(it to "idea") }
        listOf("добавь в покупки зубную пасту", "в список покупок добавь сметану", "добавь в список покупок батарейки и лампочки").forEach { add(it to "list") }
        listOf("позвони Андрею", "позвони Ольге Петровне", "набери маму").forEach { add(it to "device:Call") }
        listOf("построй маршрут до работы", "как доехать до центра", "маршрут до ближайшей аптеки").forEach { add(it to "device:Navigate") }
        listOf("разбуди меня в 6:45", "поставь будильник на 9 утра", "разбуди в половину восьмого").forEach { add(it to "device:Alarm") }
        listOf("поставь таймер на час", "таймер на 90 секунд", "засеки полтора часа").forEach { add(it to "device:Timer") }
        listOf("курс евро на сегодня", "почём доллар", "сколько стоит эфир", "сколько будет 200 евро в рублях").forEach { add(it to "skill:rates") }
        listOf("кто такая Анна Ахматова", "что такое фондовый рынок", "расскажи про Эверест", "что такое ДНК").forEach { add(it to "skill:fact") }
        listOf("что написала мама", "прочитай уведомления", "кто мне писал").forEach { add(it to "skill:messages") }
        listOf("включи радио джаз", "включи ретро фм", "радио европа плюс", "следующая станция").forEach { add(it to "skill:radio") }
        listOf("загадай мне загадку", "давай поиграем в города", "давай сыграем в угадай число").forEach { add(it to "skill:game") }
        listOf("расскажи сказку про репку", "почитай сказку на ночь").forEach { add(it to "skill:tale") }
        listOf("запомни здесь моя работа", "напомни когда приду на работу отправить отчёт", "напомни когда вернусь домой полить цветы").forEach { add(it to "skill:place") }
        listOf("зови меня Катя", "меня зовут Сергей").forEach { add(it to "skill:name") }
        listOf("какая погода в Питере", "погода завтра утром", "будет ли дождь в воскресенье", "сколько градусов в Москве").forEach { add(it to "skill:weather") }
        listOf("новости технологий", "что происходит в мире").forEach { add(it to "skill:news") }
        listOf("какие задачи на неделю", "сколько я потратил на такси в этом месяце", "мои задачи", "что у меня завтра", "доброе утро", "расскажи шутку")
            .forEach { add(it to "answer") }
        listOf("зачем люди спят", "почему трава зелёная", "посоветуй сериал", "придумай имя для кота", "объясни что такое ипотека простыми словами",
            "как научиться рисовать", "как выбрать арбуз", "что делать если скучно", "как правильно отжиматься", "почему осенью грустно")
            .forEach { add(it to "chat") }
    }

    @Test fun corpus() = runTest {
        val failures = ArrayList<String>()
        for ((phrase, expected) in corpus) {
            val got = runCatching { classify(phrase) }.getOrElse { "ошибка ${it::class.simpleName}: ${it.message}" }
            if (got != expected) failures += "«$phrase» → $got (ожидалось $expected)"
        }
        println("Корпус: ${corpus.map { it.first }.distinct().size} фраз, ошибок ${failures.size}")
        if (failures.isNotEmpty()) fail("Ошибок ${failures.size} из ${corpus.size}:\n" + failures.joinToString("\n"))
    }
}
