package ai.loli.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.Group
import ai.loli.app.ui.components.GroupDivider
import ai.loli.app.ui.components.Hint
import ai.loli.app.ui.components.LoliScreen
import ai.loli.app.ui.components.RowItem
import ai.loli.app.ui.components.SectionLabel

private data class Ability(val title: String, val examples: String)

private val LOCAL = listOf(
    Ability("Расходы и доходы", "«Потратила 850 на продукты», «зарплата пришла 80000», «сколько я потратила на кафе в этом месяце», «на что больше всего трачу»"),
    Ability("Задачи и списки", "«Нужно сходить в зал в понедельник», «купи молоко, яйца и хлеб», «отметь задачу купить хлеб выполненной», «какие задачи на сегодня»"),
    Ability("Напоминания и будильники", "«Напомни через час выпить воды», «каждый понедельник в 9 напоминай про планёрку», «разбуди меня в 7:30», «таймер на 5 минут»"),
    Ability("Заметки, идеи, память", "«Запиши заметку…», «у меня идея…», «добавь к идее…», «запомни, что я не ем сладкое», «найди всё про отпуск»"),
    Ability("Долги, накопления, дни рождения", "«Саша должен мне 500», «отложил 2000 на ремонт», «у Маши день рождения 12 октября»"),
    Ability("Списки покупок", "«Добавь в покупки молоко, хлеб и яйца», «что купить?», «купила молоко», «убери купленное». Виджет «Покупки» на рабочий стол"),
    Ability("Сценарии", "«Когда я говорю „спокойной ночи“ — поставь будильник на 7 и включи не беспокоить», потом просто «спокойной ночи»"),
    Ability("Диктовка", "«Надиктую заметку» — говорите сколько нужно, с паузами; «готово» — и заметка сохранена"),
    Ability("Секретные заметки", "«Запиши секретную заметку…» — только на телефоне, по отпечатку, не уходят в облако и AI"),
    Ability("Даты словами", "«2 дня назад потратила 500 на такси», «в прошлую пятницу», «на следующей неделе в среду», «через 3 недели», «через час двадцать»"),
    Ability("Телефон", "Звонки и сообщения, открыть приложение, фонарик, громкость, яркость, музыка, камера, скриншот, маршрут, календарь, «не беспокоить»"),
    Ability("Быстрые ответы", "Время и дата, время в других городах, сколько дней до даты, калькулятор и проценты, перевод единиц, монетка и кубик"),
    Ability("Сообщения", "«Прочитай сообщения», «что написала мама», «ответь Маше: буду через 10 минут» — Telegram, WhatsApp, VK, SMS"),
    Ability("Таймеры", "«Таймер на пасту 8 минут», «сколько осталось», «отмени таймер» — громкий сигнал даже в беззвучном режиме"),
    Ability("Напоминания по месту", "«Запомни, я дома», потом «напомни, когда буду дома, позвонить маме» или «когда уйду с работы — купить хлеб»"),
    Ability("Игры и сказки", "«Давай в города», «загадай число», «загадай загадку», «расскажи сказку про колобка»"),
    Ability("Найти телефон", "«Лоли, где ты?» — громкий сигнал и фонарик (когда включено слово «Лоли»)"),
    Ability("Календарь и контакты", "«Что у меня в календаре завтра», «когда ближайшая встреча», «какой номер у мамы». Утром — план с погодой"),
    Ability("О вас", "«Называй меня Лёша», «я живу в Казани» — для приветствий и погоды"),
    Ability("Сложные фразы", "«Потратил 200 на кофе, нужно в понедельник в зал, забрать дочь из садика» — Лоли разложит на расход и задачи"),
)

private val ONLINE = listOf(
    Ability("Погода", "«Какая погода», «будет ли завтра дождь», «нужен ли зонт», «погода в Казани на выходные»"),
    Ability("Курсы валют", "«Курс доллара», «почём евро», «сколько будет 100 долларов в рублях», «сколько стоит биткоин»"),
    Ability("Новости", "«Новости», «новости спорта», «что нового в науке», «подробнее» — открыть первую"),
    Ability("Справка", "«Кто такой Гагарин», «что такое фотосинтез» — коротко из Википедии"),
    Ability("Радио", "«Включи радио», «включи Европу Плюс», «включи джаз», «следующая станция», «выключи радио»"),
)

private val AI = listOf(
    Ability("Экран", "«Перескажи эту статью», «переведи экран» — нужен AI и включённые спецвозможности; «что на экране» — и без AI"),
    Ability("Сказки на заказ", "«Придумай сказку про кота-космонавта»"),
    Ability("Любые вопросы", "Объяснить, посоветовать, сравнить, «почему небо голубое», «как приготовить плов», «что подарить маме»"),
    Ability("Тексты", "Написать поздравление, письмо, пост, сократить или исправить текст"),
    Ability("Переводчик", "«Переведи на английский где вокзал», «как по-немецки спасибо» — ответ голосом нужного языка. Без AI — только частые фразы"),
    Ability("Свободная речь", "Команды любыми словами, даже непривычными: AI поймёт намерение и выполнит те же действия, что и локально"),
    Ability("Мозговой штурм", "«Давай придумаем приложение для склада» — AI развивает идею и сам дописывает её в заметку"),
    Ability("Планы и анализ", "Составить план на неделю, разобрать расходы, предложить, где сэкономить, спланировать поездку"),
    Ability("Поиск по смыслу", "Находит записи не по словам, а по смыслу: «что я хотела подарить маме» найдёт заметку «шарф для мамы»"),
)

/** «Что умеет Лоли»: что работает на устройстве, а что добавляет AI. */
@Composable
fun CapabilitiesPage(c: AppContainer, onBack: () -> Unit, openAi: () -> Unit) {
    val s by c.settings.settings.collectAsStateWithLifecycle()
    LoliScreen(title = "Что умеет ${s.assistantName}", subtitle = "На устройстве и с AI", onBack = onBack) {
        item(key = "local") {
            SectionLabel("Без интернета, на устройстве")
            AbilityGroup(LOCAL, Icons.Rounded.Bolt)
            Hint("Всё это работает всегда — даже без интернета и без AI. Данные не покидают телефон.")
        }
        item(key = "online") {
            SectionLabel("С интернетом — без AI и без ключей")
            AbilityGroup(ONLINE, Icons.Rounded.Bolt)
            Hint("Бесплатные открытые источники: Open-Meteo, ЦБ РФ, Лента.ру, ТАСС, Википедия, каталог radio-browser. Никаких регистраций.")
        }
        item(key = "ai") {
            SectionLabel("С подключённым AI — дополнительно")
            AbilityGroup(AI, Icons.Rounded.AutoAwesome)
            Hint(
                if (s.useAI) "AI включён. Если он недоступен (нет сети, лимит), команды всё равно выполнятся на устройстве — под ответом будет видна причина."
                else "AI выключен. Подключите ключ любого провайдера — Лоли станет полноценным AI-помощником, а команды продолжат работать и без сети.",
            )
            Group(Modifier.padding(top = 4.dp)) {
                RowItem(title = if (s.useAI) "Настройки AI" else "Подключить AI", icon = Icons.Rounded.AutoAwesome, chevron = true, onClick = openAi)
            }
        }
    }
}

@Composable
private fun AbilityGroup(items: List<Ability>, icon: ImageVector) {
    Group {
        items.forEachIndexed { i, a ->
            if (i > 0) GroupDivider(inset = 52.dp)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(a.title, style = MaterialTheme.typography.titleSmall)
                    Text(a.examples, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}
