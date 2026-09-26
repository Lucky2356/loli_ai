package ai.loli.core.skills

import ai.loli.core.data.LoliJson
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/** Интернет-радиостанция. */
data class RadioStation(val name: String, val url: String)

/**
 * Поиск радио: сначала популярные российские станции (адреса проверены), затем открытый каталог radio-browser.info.
 * Незашифрованные потоки (http) берём только с известных хостингов радио — остальное приложению запрещено.
 */
class RadioCatalog(private val http: HttpClient?) {
    private val cache = TtlCache<String, List<RadioStation>>(6 * 3600_000L)

    suspend fun find(query: String?): List<RadioStation> {
        val q = query?.trim()?.lowercase()?.replace('ё', 'е').orEmpty()
        if (q.isEmpty()) return POPULAR.map { it.second }
        builtIn(q)?.let { return listOf(it) }
        genre(q)?.let { tag -> return search("tag=${tag.encodeURLParameter()}", q).ifEmpty { POPULAR.map { it.second } } }
        return search("name=${q.encodeURLParameter()}", q)
    }

    private suspend fun search(param: String, key: String): List<RadioStation> {
        val client = http ?: return emptyList()
        cache.get(param)?.let { return it }
        val url = "https://all.api.radio-browser.info/json/stations/search?$param&limit=15&hidebroken=true&order=clickcount&reverse=true"
        val found = parseStations(client.fetchText(url)).filter { allowed(it.url) }
        cache.put(param, found)
        return found
    }

    companion object {
        /** Хостинги российских радиостанций, где потоки бывают только по http. */
        val CLEARTEXT_HOSTS = listOf(
            "hostingradio.ru", "streamr.ru", "cdnvideo.ru", "101.ru", "myradio24.com", "rusongs.ru", "volna.top", "mixto.ru",
            "radiorecord.ru", "fmplayer.ru", "emgsound.ru", "tavrmedia.ru",
        )

        fun allowed(url: String): Boolean {
            val u = url.trim().lowercase()
            if (u.startsWith("https://")) return true
            if (!u.startsWith("http://")) return false
            val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
            return CLEARTEXT_HOSTS.any { host == it || host.endsWith(".$it") }
        }

        /** Популярные станции: ключевые слова → станция. */
        val POPULAR: List<Pair<List<String>, RadioStation>> = listOf(
            listOf("европа плюс", "европу плюс", "европа+", "europa plus") to RadioStation("Европа Плюс", "http://ep256.hostingradio.ru:8052/europaplus256.mp3"),
            listOf("русское радио", "русское") to RadioStation("Русское Радио", "https://rusradio.hostingradio.ru/rusradio96.aacp"),
            listOf("ретро фм", "ретро", "retro") to RadioStation("Ретро FM", "http://retroserver.streamr.ru:8043/retro256.mp3"),
            listOf("дорожное", "дорожное радио") to RadioStation("Дорожное радио", "http://dorognoe.hostingradio.ru:8000/radio"),
            listOf("наше радио", "наше") to RadioStation("Наше Радио", "http://nashe.streamr.ru/nashe-128.mp3"),
            listOf("рок фм", "rock fm", "рок") to RadioStation("Rock FM", "http://nashe1.hostingradio.ru/rock-128.mp3"),
            listOf("шансон", "радио шансон") to RadioStation("Радио Шансон", "http://chanson.hostingradio.ru:8041/chanson128.mp3"),
            listOf("вести фм", "вести") to RadioStation("Вести FM", "http://icecast.vgtrk.cdnvideo.ru/vestifm_mp3_192kbps"),
            listOf("маяк", "радио маяк") to RadioStation("Радио Маяк", "http://icecast.vgtrk.cdnvideo.ru/mayakfm_mp3_192kbps"),
            listOf("комсомольская правда", "радио кп", "кп") to RadioStation("Комсомольская правда", "http://kpradio.hostingradio.ru:8000/russia.radiokp128.mp3"),
            listOf("спутник", "радио спутник") to RadioStation("Радио Спутник", "https://icecast-rian.cdnvideo.ru/voicerus"),
            listOf("релакс", "relax", "релакс фм") to RadioStation("Relax FM", "https://pub0201.101.ru/stream/trust/mp3/128/24?"),
            listOf("рекорд", "radio record", "радио рекорд") to RadioStation("Радио Рекорд · Russian Mix", "https://radiorecord.hostingradio.ru/rus96.aacp"),
            listOf("дискотека 90", "90-х", "девяностых", "90х") to RadioStation("Дискотека 90-х", "https://radiorecord.hostingradio.ru/sd9096.aacp"),
            listOf("джаз", "jazz") to RadioStation("Instrumental Jazz", "https://jfm1.hostingradio.ru:14536/ijstream.mp3"),
            listOf("радио книга", "аудиокниг", "книга") to RadioStation("Радио Книга", "http://bookradio.hostingradio.ru:8069/fm"),
            listOf("радио ваня", "ваня") to RadioStation("Радио Ваня", "https://icecast-radiovanya.cdnvideo.ru/radiovanya"),
            listOf("русские песни") to RadioStation("Радио Русские Песни", "http://listen.rusongs.ru/ru-mp3-128"),
        )

        fun builtIn(q: String): RadioStation? {
            val clean = q.removePrefix("радио ").trim()
            return POPULAR.firstOrNull { (keys, _) -> keys.any { k -> q == k || clean == k || q.contains(k) && k.length >= 5 } }?.second
        }

        /** Жанр → тег каталога. */
        fun genre(q: String): String? = when {
            q.contains("классик") -> "classical"
            q.contains("джаз") -> "jazz"
            q.contains("рок") -> "rock"
            q.contains("поп") -> "pop"
            q.contains("новост") -> "news"
            q.contains("детск") -> "children"
            q.contains("электрон") || q.contains("танцев") -> "dance"
            q.contains("лаунж") || q.contains("спокойн") || q.contains("для сна") -> "chillout"
            q.contains("рэп") || q.contains("хип") -> "hip hop"
            q.contains("кантри") -> "country"
            q.contains("блюз") -> "blues"
            q.contains("метал") -> "metal"
            else -> null
        }

        fun parseStations(json: String): List<RadioStation> =
            (LoliJson.parseToJsonElement(json) as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val url = o.text("url_resolved")?.takeIf { it.isNotBlank() } ?: o.text("url") ?: return@mapNotNull null
                val name = o.text("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RadioStation(name, url.trim())
            }.distinctBy { it.url }
    }
}
