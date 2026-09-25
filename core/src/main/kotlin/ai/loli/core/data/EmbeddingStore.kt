package ai.loli.core.data

import ai.loli.core.db.Embedding
import ai.loli.core.db.LoliDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Кэш векторов для семантического поиска (float32, little-endian). */
class EmbeddingStore(private val db: LoliDatabase, private val dispatcher: CoroutineDispatcher) {
    data class Entry(val recordId: String, val contentHash: String, val vector: FloatArray)

    suspend fun load(model: String): Map<String, Entry> = withContext(dispatcher) {
        db.embeddingQueries.selectByModel(model).executeAsList().associate { it.record_id to Entry(it.record_id, it.content_hash, decode(it.vector)) }
    }

    suspend fun save(model: String, recordId: String, contentHash: String, vector: FloatArray) = withContext(dispatcher) {
        db.embeddingQueries.upsert(Embedding(recordId, model, contentHash, encode(vector)))
        Unit
    }

    companion object {
        fun encode(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()
        fun decode(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.getFloat() }
        }
    }
}
