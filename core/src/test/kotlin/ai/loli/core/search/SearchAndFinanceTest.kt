package ai.loli.core.search

import ai.loli.core.TestEnv
import ai.loli.core.ai.EmbeddingProvider
import ai.loli.core.finance.ExpenseAnalytics
import ai.loli.core.finance.PeriodPreset
import ai.loli.core.finance.ReportMode
import ai.loli.core.model.NoteKind
import ai.loli.core.model.RecordType
import ai.loli.core.util.Logger
import ai.loli.core.util.Redactor
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchServiceTest {
    @Test fun findsByWordFormsAndSynonyms() = runTest {
        val env = TestEnv()
        env.store.notes.create(NoteKind.IDEA, "Приложение для холодильника", "учёт продуктов и сроков годности")
        env.store.notes.create(NoteKind.NOTE, "Отпуск в Японии", "маршрут: Токио, Киото")
        env.store.tasks.create("Купить продукты")
        val hits = env.search.search("холодильнике", limit = 5)
        assertEquals("Приложение для холодильника", hits.first().doc.title)
        val bySynonym = env.search.search("приложения для еды", extraKeywords = listOf("продукты", "холодильник"), types = setOf(RecordType.IDEA))
        assertEquals(1, bySynonym.size)
        assertTrue(env.search.search("отпуск").first().doc.title.contains("Японии"))
    }

    @Test fun semanticSearchUsesEmbeddings() = runTest {
        val env = TestEnv()
        env.store.notes.create(NoteKind.NOTE, "Рецепт борща", "свёкла, капуста")
        env.store.notes.create(NoteKind.NOTE, "Созвон с клиентом", "обсудить договор")
        // Игрушечные эмбеддинги: «еда» близка к борщу.
        env.embeddings = object : EmbeddingProvider {
            override val embeddingModel = "toy"
            override suspend fun embed(texts: List<String>) = texts.map { t ->
                if (t.contains("борщ", true) || t.contains("еда", true) || t.contains("кулинар", true)) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
            }
        }
        val hits = env.search.search("кулинария")
        assertEquals("Рецепт борща", hits.first().doc.title)
        assertTrue(hits.first().semantic!! > 0.9)
        // Векторы кэшируются: второй поиск не пересчитывает документы.
        assertEquals(2, env.store.embeddings.load("toy").size)
    }

    @Test fun embeddingFailureFallsBackToLexical() = runTest {
        val env = TestEnv()
        env.store.notes.create(NoteKind.NOTE, "Список покупок", "")
        env.embeddings = object : EmbeddingProvider {
            override val embeddingModel = "broken"
            override suspend fun embed(texts: List<String>): List<FloatArray> = throw java.io.IOException("offline")
        }
        assertEquals("Список покупок", env.search.search("покупки").first().doc.title)
    }
}

class ExpenseAnalyticsTest {
    private val today = LocalDate.of(2026, 9, 25)

    @Test fun reportsAndPeriods() = runTest {
        val env = TestEnv()
        env.store.expenses.create(120_000, "RUB", "Продукты", "", today)
        env.store.expenses.create(50_000, "RUB", "Продукты", "", today.minusDays(1))
        env.store.expenses.create(30_000, "RUB", "Транспорт", "такси", today.minusDays(1))
        env.store.expenses.create(999_900, "RUB", "Путешествия", "билеты", LocalDate.of(2026, 8, 3))
        val all = env.store.expenses.all()

        val month = ExpenseAnalytics.report(all, ExpenseAnalytics.range(PeriodPreset.THIS_MONTH, today), null)
        assertEquals(200_000, month.totalMinor)
        assertEquals("Продукты", month.byCategory.first().category)
        val food = ExpenseAnalytics.report(all, ExpenseAnalytics.range(PeriodPreset.THIS_MONTH, today), "продукты")
        assertEquals(170_000, food.totalMinor)
        val yesterday = ExpenseAnalytics.report(all, ExpenseAnalytics.range(PeriodPreset.YESTERDAY, today))
        assertEquals(80_000, yesterday.totalMinor)
        val lastMonth = ExpenseAnalytics.report(all, ExpenseAnalytics.range(PeriodPreset.LAST_MONTH, today))
        assertEquals(999_900, lastMonth.totalMinor)
        val text = ExpenseAnalytics.describe(month, ReportMode.TOTAL)
        assertTrue(text.contains("2 000 ₽"), text)
        assertTrue(text.contains("3 операции"), text)
        assertTrue(ExpenseAnalytics.describe(month, ReportMode.TOP).startsWith("Самые большие расходы"))
    }

    @Test fun emptyPeriod() {
        val r = ExpenseAnalytics.report(emptyList(), ExpenseAnalytics.range(PeriodPreset.TODAY, today))
        assertEquals("Расходов сегодня не нашла.", ExpenseAnalytics.describe(r, ReportMode.TOTAL))
    }
}

class RedactorTest {
    @Test fun secretsAreMasked() {
        val text = "key sk-proj-ABCDEFGH12345678 auth Bearer eyJhbGciOi.eyJzdWIiOi.sig password=hunter2 {\"api_key\":\"xyz\"} AIzaSyA1234567890123456789012"
        val red = Redactor.redact(text)
        listOf("ABCDEFGH12345678", "hunter2", "xyz", "eyJzdWIiOi", "AIzaSyA1234567890").forEach { assertFalse(red.contains(it), red) }
    }

    @Test fun loggerRedacts() {
        val lines = mutableListOf<String>()
        val old = Logger.sink
        Logger.sink = object : ai.loli.core.util.LogSink {
            override fun log(level: Logger.Level, tag: String, message: String, error: Throwable?) { lines += message + (error?.message ?: "") }
        }
        try {
            Logger.e("t", "ошибка с ключом sk-ant-api03-SECRETSECRET", RuntimeException("Bearer abc.def.ghi"))
        } finally { Logger.sink = old }
        assertFalse(lines.single().contains("SECRETSECRET"))
        assertFalse(lines.single().contains("abc.def.ghi"))
    }
}
