package ai.loli.desktop

import ai.loli.core.backup.BackupExtras
import ai.loli.core.data.LoliJson
import ai.loli.core.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Проверка обновлений: последний релиз на GitHub. Новая версия — карточка «Скачать» в боковой панели;
 * установщик ставится поверх, записи и настройки сохраняются. Ничего не скачивается и не ставится само.
 */
class UpdateChecker(private val http: HttpClient) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val version: String, val url: String, val notes: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Версия установленной программы; «dev» — запуск из исходников. */
    val current: String = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev"

    suspend fun check() {
        _state.value = State.Checking
        _state.value = try {
            val body = http.get("https://api.github.com/repos/Lucky2356/loli_ai/releases/latest") {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "LoliAssistant")
            }.bodyAsText()
            val json = LoliJson.parseToJsonElement(body).jsonObject
            val tag = json["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: error("нет версии в ответе")
            val msi = json["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith("-windows.msi") == true }
                ?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
            val page = json["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/Lucky2356/loli_ai/releases/latest"
            if (current != "dev" && newer(tag, current)) {
                State.Available(tag, msi ?: page, json["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(600))
            } else {
                State.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("Updates", "Проверка обновлений не удалась", e)
            State.Failed("Не удалось проверить: нет связи с GitHub")
        }
    }

    companion object {
        /** «2.12.0» новее «2.11.3»: сравнение по числам, хвосты вроде «-beta.1» не учитываются. */
        fun newer(candidate: String, installed: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate); val b = parts(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Что кроме базы входит в резервную копию на компьютере: имена и город. Ключи AI — никогда. */
class DesktopBackupExtras(private val settings: DesktopSettings) : BackupExtras {
    override suspend fun export(): Map<String, String> {
        val s = settings.value
        return buildMap {
            put("assistantName", s.assistantName)
            if (s.userName.isNotBlank()) put("userName", s.userName)
            if (s.city.isNotBlank()) put("city", s.city)
        }
    }

    override suspend fun restore(values: Map<String, String>) {
        // Как на телефоне: имена из копии подставляются, только если здесь они ещё не заданы.
        settings.update { s ->
            s.copy(
                userName = s.userName.ifBlank { values["userName"].orEmpty() },
                city = s.city.ifBlank { values["city"].orEmpty() },
                assistantName = if (s.assistantName == "Лоли") values["assistantName"]?.ifBlank { null } ?: s.assistantName else s.assistantName,
            )
        }
    }
}
