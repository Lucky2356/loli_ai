package ai.loli.core.skills

import ai.loli.core.data.LoliJson
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Рубрика новостей. */
enum class NewsTopic(val title: String, val feeds: List<String>) {
    MAIN("Главные новости", listOf("https://lenta.ru/rss/news", "https://tass.ru/rss/v2.xml", "https://ria.ru/export/rss2/archive/index.xml")),
    SPORT("Новости спорта", listOf("https://lenta.ru/rss/news/sport")),
    SCIENCE("Новости науки и техники", listOf("https://lenta.ru/rss/news/science", "https://habr.com/ru/rss/news/?fl=ru")),
    TECH("Новости технологий", listOf("https://habr.com/ru/rss/news/?fl=ru", "https://lenta.ru/rss/news/science")),
    ECONOMY("Новости экономики", listOf("https://lenta.ru/rss/news/economics", "https://rssexport.rbc.ru/rbcnews/news/30/full.rss")),
}

/** Заголовки новостей из RSS (Лента, ТАСС, РИА, Хабр, РБК) — без ключей и регистрации. */
class NewsService(private val http: HttpClient) {
    data class Item(val title: String, val link: String?)

    private val cache = TtlCache<NewsTopic, List<Item>>(10 * 60_000L)

    suspend fun headlines(topic: NewsTopic, limit: Int = 5): List<Item> {
        cache.get(topic)?.let { return it.take(limit) }
        var lastError: Exception? = null
        for (url in topic.feeds) {
            try {
                val items = parseRss(http.fetchText(url))
                if (items.isNotEmpty()) { cache.put(topic, items); return items.take(limit) }
            } catch (e: InfoUnavailable) {
                lastError = e
            }
        }
        throw lastError ?: InfoUnavailable("новостей нет")
    }

    companion object {
        private val ITEM = Regex("""<item\b[^>]*>(.*?)</item>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private fun tag(name: String) = Regex("""<$name\b[^>]*>(.*?)</$name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val TITLE = tag("title")
        private val LINK = tag("link")

        fun parseRss(xml: String): List<Item> = ITEM.findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            val title = TITLE.find(body)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Item(title, LINK.find(body)?.groupValues?.get(1)?.let(::clean)?.takeIf { it.startsWith("http") })
        }.distinctBy { it.title }.toList()

        fun clean(s: String): String = s.trim()
            .removePrefix("<![CDATA[").removeSuffix("]]>")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&laquo;", "«").replace("&raquo;", "»").replace("&nbsp;", " ").replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace(Regex("""\s+"""), " ").trim()

        fun answer(topic: NewsTopic, items: List<Item>): String {
            if (items.isEmpty()) return "Свежих новостей не нашла."
            return "${topic.title}:\n" + items.mapIndexed { i, it -> "${i + 1}. ${it.title.trimEnd('.')}." }.joinToString("\n")
        }
    }
}

/** Короткая справка из Википедии: «кто такой Гагарин», «что такое фотосинтез». */
class WikiService(private val http: HttpClient) {
    data class Article(val title: String, val extract: String, val url: String)

    private val cache = TtlCache<String, Article?>(6 * 3600_000L)

    suspend fun lookup(query: String): Article? {
        val key = query.lowercase().trim()
        cache.get(key)?.let { return it }
        // Сначала прямое совпадение названия (с перенаправлениями), затем полнотекстовый поиск.
        val direct = extract(query.replaceFirstChar { it.uppercase() })
        val article = direct ?: searchTitle(query)?.let { extract(it) }
        cache.put(key, article)
        return article
    }

    private suspend fun searchTitle(query: String): String? {
        val url = "https://ru.wikipedia.org/w/api.php?action=query&list=search&srsearch=${query.encodeURLParameter()}&srlimit=3&format=json&utf8=1"
        return parseSearch(http.fetchText(url)).firstOrNull()
    }

    private suspend fun extract(title: String): Article? {
        val url = "https://ru.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exsentences=4&redirects=1" +
            "&titles=${title.encodeURLParameter()}&format=json&utf8=1"
        return parseExtract(http.fetchText(url))
    }

    companion object {
        fun parseSearch(json: String): List<String> {
            val q = LoliJson.parseToJsonElement(json).jsonObject["query"] as? JsonObject ?: return emptyList()
            return (q["search"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.text("title") } ?: emptyList()
        }

        fun parseExtract(json: String): Article? {
            val q = LoliJson.parseToJsonElement(json).jsonObject["query"] as? JsonObject ?: return null
            val pages = q["pages"] as? JsonObject ?: return null
            val page = pages.values.firstOrNull() as? JsonObject ?: return null
            if (page.containsKey("missing")) return null
            val title = page.text("title") ?: return null
            val text = page.text("extract")?.let(::shorten)?.takeIf { it.length > 20 } ?: return null
            // Страница-неоднозначность («Меркурий — может означать…») бесполезна для ответа.
            if (Regex("""(?:может означать|может относиться|может иметь значения)""").containsMatchIn(text.take(200))) return null
            return Article(title, text, "https://ru.wikipedia.org/wiki/" + title.replace(' ', '_').encodeURLParameter())
        }

        /** Убирает ударения, скобки с датами и произношением, оставляет 2–3 предложения. */
        fun shorten(extract: String, maxChars: Int = 420): String {
            var t = extract.replace("́", "").replace(Regex("""\n+"""), " ")
            // Вложенные скобки снимаем изнутри наружу.
            repeat(3) { t = t.replace(Regex("""\s*\([^()]*\)"""), "") }
            t = t.replace(Regex("""\s+"""), " ").replace(Regex("""\s+([,.;:])"""), "$1").trim()
            val sentences = Regex("""(?<=[.!?])\s+(?=[А-ЯЁA-Z0-9«])""").split(t)
            val sb = StringBuilder()
            for (s in sentences) {
                if (sb.isNotEmpty() && sb.length + s.length + 1 > maxChars) break
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
            return sb.toString().ifEmpty { t.take(maxChars) }
        }
    }
}
