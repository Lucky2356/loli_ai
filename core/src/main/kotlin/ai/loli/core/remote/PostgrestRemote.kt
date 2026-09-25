package ai.loli.core.remote

import ai.loli.core.data.LoliJson
import ai.loli.core.util.Redactor
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.time.Instant

/** Удалённое хранилище для синхронизации. Реализация — Supabase PostgREST; в тестах — фейк. */
interface RemoteDataSource {
    /** Вставка или обновление строк по id. Сервер сам проверяет владельца (RLS) и last-write-wins (триггер). */
    suspend fun upsert(table: String, rows: List<JsonObject>)
    /** Строки, изменённые на сервере после [since] (по server_updated_at), по возрастанию. */
    suspend fun fetchChanges(table: String, since: Instant?, limit: Int): List<JsonObject>
    suspend fun fetchSingle(table: String): JsonObject?
}

sealed class RemoteException(message: String) : Exception(message) {
    class Offline : RemoteException("Нет подключения к серверу.")
    class Unauthorized : RemoteException("Требуется вход в аккаунт.")
    class Http(val code: Int, detail: String) : RemoteException("Сервер вернул $code: $detail")
}

class PostgrestRemote(
    private val http: HttpClient,
    private val config: SupabaseConfig,
    /** Возвращает access token; при forceRefresh=true обновляет его (после 401). */
    private val token: suspend (forceRefresh: Boolean) -> String?,
) : RemoteDataSource {

    override suspend fun upsert(table: String, rows: List<JsonObject>) {
        if (rows.isEmpty()) return
        rows.chunked(BATCH).forEach { batch ->
            request { t ->
                http.post("${config.url}/rest/v1/$table") {
                    auth(t)
                    parameter("on_conflict", "id")
                    header("Prefer", "resolution=merge-duplicates,return=minimal")
                    contentType(ContentType.Application.Json)
                    setBody(JsonArray(batch).toString())
                }
            }
        }
    }

    override suspend fun fetchChanges(table: String, since: Instant?, limit: Int): List<JsonObject> {
        val response = request { t ->
            http.get("${config.url}/rest/v1/$table") {
                auth(t)
                parameter("select", "*")
                if (since != null) parameter("server_updated_at", "gt.$since")
                parameter("order", "server_updated_at.asc")
                parameter("limit", limit)
            }
        }
        return LoliJson.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
    }

    override suspend fun fetchSingle(table: String): JsonObject? {
        val response = request { t ->
            http.get("${config.url}/rest/v1/$table") {
                auth(t)
                parameter("select", "*")
                parameter("limit", 1)
            }
        }
        return LoliJson.parseToJsonElement(response.bodyAsText()).jsonArray.firstOrNull()?.jsonObject
    }

    private fun HttpRequestBuilder.auth(token: String) {
        header("apikey", config.anonKey)
        header("Authorization", "Bearer $token")
    }

    private suspend fun request(block: suspend (String) -> HttpResponse): HttpResponse {
        var t = token(false) ?: throw RemoteException.Unauthorized()
        repeat(2) { attempt ->
            val response = try {
                block(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw RemoteException.Offline()
            } catch (e: Exception) {
                val name = e::class.simpleName.orEmpty()
                if (name.contains("Timeout") || name.contains("Connect") || name.contains("UnresolvedAddress")) throw RemoteException.Offline()
                throw e
            }
            when (response.status.value) {
                in 200..299 -> return response
                401 -> if (attempt == 0) {
                    t = token(true) ?: throw RemoteException.Unauthorized()
                } else throw RemoteException.Unauthorized()
                else -> throw RemoteException.Http(response.status.value, Redactor.redact(response.bodyAsText()).take(300))
            }
        }
        throw RemoteException.Unauthorized()
    }

    companion object { const val BATCH = 200 }
}
