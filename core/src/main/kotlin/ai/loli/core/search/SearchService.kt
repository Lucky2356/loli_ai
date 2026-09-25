package ai.loli.core.search

import ai.loli.core.ai.EmbeddingProvider
import ai.loli.core.data.EmbeddingStore
import ai.loli.core.domain.MemoryRepository
import ai.loli.core.domain.NoteRepository
import ai.loli.core.domain.ReminderRepository
import ai.loli.core.domain.TaskRepository
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import ai.loli.core.nlp.TextAnalysis
import ai.loli.core.util.Logger
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.sqrt

data class SearchDoc(val type: RecordType, val id: String, val title: String, val body: String, val updatedAt: Instant) {
    val text: String get() = if (body.isBlank()) title else "$title\n$body"
}

data class SearchHit(val doc: SearchDoc, val score: Double, val lexical: Double, val semantic: Double?)

/**
 * Гибридный поиск по памяти ассистента:
 *  1) лексический — по основам слов (стеммер Snowball) с нечётким сравнением форм;
 *  2) семантический — косинусная близость эмбеддингов (если провайдер AI их поддерживает).
 * AI дополнительно передаёт синонимы запроса ([extraKeywords]), что даёт поиск «по смыслу» даже без эмбеддингов.
 */
class SearchService(
    private val notes: NoteRepository,
    private val tasks: TaskRepository,
    private val reminders: ReminderRepository,
    private val memories: MemoryRepository,
    private val embeddingStore: EmbeddingStore?,
    private val embeddingProvider: () -> EmbeddingProvider?,
) {
    suspend fun documents(types: Set<RecordType>? = null): List<SearchDoc> {
        fun want(t: RecordType) = types == null || t in types
        val docs = ArrayList<SearchDoc>()
        if (want(RecordType.NOTE) || want(RecordType.IDEA)) {
            notes.all().forEach { n ->
                val t = if (n.kind == NoteKind.IDEA) RecordType.IDEA else RecordType.NOTE
                if (want(t)) docs += SearchDoc(t, n.id, n.title, n.content + if (n.tags.isEmpty()) "" else "\n" + n.tags.joinToString(" "), n.updatedAt)
            }
        }
        if (want(RecordType.TASK)) tasks.all().forEach { docs += SearchDoc(RecordType.TASK, it.id, it.title, it.details, it.updatedAt) }
        if (want(RecordType.REMINDER)) reminders.all().forEach { docs += SearchDoc(RecordType.REMINDER, it.id, it.text, "", it.updatedAt) }
        if (want(RecordType.MEMORY)) memories.all().forEach { docs += SearchDoc(RecordType.MEMORY, it.id, it.content, it.category, it.updatedAt) }
        return docs
    }

    suspend fun search(
        query: String,
        types: Set<RecordType>? = null,
        extraKeywords: List<String> = emptyList(),
        limit: Int = 10,
        minScore: Double = 0.2,
        useEmbeddings: Boolean = true,
    ): List<SearchHit> {
        val docs = documents(types)
        if (docs.isEmpty() || (query.isBlank() && extraKeywords.isEmpty())) return emptyList()
        val lexical = docs.associate { it.id to lexicalScore(query, extraKeywords, it) }
        val semantic = if (useEmbeddings) semanticScores(query, docs) else null
        return docs.map { d ->
            val lex = lexical.getValue(d.id)
            val sem = semantic?.get(d.id)
            val score = if (sem != null) maxOf(lex, 0.45 * lex + 0.55 * sem) else lex
            SearchHit(d, score, lex, sem)
        }.filter { it.score >= minScore }
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.doc.updatedAt })
            .take(limit)
    }

    /** Доля слов запроса, найденных в записи (заголовок весит больше). 0..1. */
    fun lexicalScore(query: String, extraKeywords: List<String>, doc: SearchDoc): Double {
        val q = TextAnalysis.stems(query).distinct()
        val extra = extraKeywords.flatMap { TextAnalysis.stems(it) }.distinct().filter { it !in q }
        if (q.isEmpty() && extra.isEmpty()) return 0.0
        val title = TextAnalysis.stems(doc.title).toSet()
        val body = TextAnalysis.stems(doc.body).toSet()
        fun best(stem: String): Double {
            val t = title.maxOfOrNull { TextAnalysis.stemSimilarity(stem, it) } ?: 0.0
            val b = body.maxOfOrNull { TextAnalysis.stemSimilarity(stem, it) } ?: 0.0
            return maxOf(t, b * 0.8)
        }
        val main = if (q.isEmpty()) 0.0 else q.sumOf { best(it) } / q.size
        val syn = if (extra.isEmpty()) 0.0 else extra.maxOf { best(it) } * 0.7
        return minOf(1.0, maxOf(main, syn, main * 0.8 + syn * 0.4))
    }

    private suspend fun semanticScores(query: String, docs: List<SearchDoc>): Map<String, Double>? {
        val provider = embeddingProvider() ?: return null
        val store = embeddingStore ?: return null
        return try {
            val model = provider.embeddingModel
            val cached = store.load(model)
            val missing = docs.filter { cached[it.id]?.contentHash != hash(it.text) }
            val fresh = HashMap<String, FloatArray>()
            missing.chunked(64).forEach { batch ->
                val vectors = provider.embed(batch.map { it.text })
                batch.zip(vectors).forEach { (doc, vec) ->
                    store.save(model, doc.id, hash(doc.text), vec)
                    fresh[doc.id] = vec
                }
            }
            val queryVec = provider.embed(listOf(query)).first()
            docs.associate { d ->
                val v = fresh[d.id] ?: cached[d.id]?.vector
                d.id to (v?.let { normalizeCosine(cosine(queryVec, it)) } ?: 0.0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Семантический поиск недоступен, используется лексический", e)
            null
        }
    }

    companion object {
        private const val TAG = "Search"

        fun cosine(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size || a.isEmpty()) return 0.0
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
        }

        /** Косинус современных эмбеддингов у несвязанных текстов ~0.1–0.25, у близких — 0.5+. */
        fun normalizeCosine(c: Double): Double = ((c - 0.2) / 0.45).coerceIn(0.0, 1.0)

        fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }
}
