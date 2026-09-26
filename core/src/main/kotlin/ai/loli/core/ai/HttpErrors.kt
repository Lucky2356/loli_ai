package ai.loli.core.ai

import ai.loli.core.data.LoliJson
import ai.loli.core.util.Redactor
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

internal suspend fun HttpResponse.throwIfError() {
    val code = status.value
    if (code in 200..299) return
    val body = runCatching { bodyAsText() }.getOrDefault("")
    val detail = Redactor.redact(extractErrorMessage(body)).take(300)
    // Gemini и некоторые другие сервисы отвечают на неверный ключ кодом 400 — это тоже «ключ не принят».
    val badKey = Regex("""api[ _-]?key|API_KEY_INVALID|invalid[_ ]?(api[_ ]?)?key|incorrect api key|authentication""", RegexOption.IGNORE_CASE).containsMatchIn(detail)
    throw when {
        code == 401 || code == 403 || (code == 400 && badKey) -> AIException.Unauthorized(detail.ifBlank { "HTTP $code" })
        code == 429 -> AIException.RateLimited()
        else -> AIException.Server(code, detail.ifBlank { "без описания" })
    }
}

internal fun extractErrorMessage(body: String): String = runCatching {
    val obj = LoliJson.parseToJsonElement(body) as JsonObject
    val err = obj["error"]
    when {
        err is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
        err != null -> err.jsonPrimitive.contentOrNull ?: err.toString()
        else -> obj["message"]?.jsonPrimitive?.contentOrNull ?: body
    }
}.getOrDefault(body)

/** Оборачивает сетевые исключения в понятные [AIException]. */
internal suspend fun <T> aiCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: AIException) {
    throw e
} catch (e: IOException) {
    throw AIException.Network(e)
} catch (e: Exception) {
    // Ktor бросает собственные исключения таймаутов/соединения; всё сетевое считаем проблемой сети.
    val name = e::class.simpleName.orEmpty()
    if (name.contains("Timeout") || name.contains("Connect") || name.contains("UnresolvedAddress")) throw AIException.Network(e)
    throw AIException.InvalidResponse(Redactor.redact(e.message ?: name))
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key]?.let { runCatching { it.jsonObject }.getOrNull() }
