package ai.loli.core.backup

import ai.loli.core.data.LocalStore
import ai.loli.core.db.LoliDatabase
import ai.loli.core.model.MessageRole
import ai.loli.core.model.NoteKind
import ai.loli.core.model.Recurrence
import ai.loli.core.util.FixedTimeSource
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupTest {
    private val time = FixedTimeSource(Instant.parse("2026-09-25T09:00:00Z"), ZoneId.of("Europe/Moscow"))
    private val pass = "правильный пароль".toCharArray()
    private fun device() = LocalStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LoliDatabase.Schema.create(it) }, time, Dispatchers.Unconfined)

    private class Extras(val saved: MutableMap<String, String> = HashMap()) : BackupExtras {
        override suspend fun export() = mapOf("places" to """[{"name":"дом","lat":55.7,"lon":37.6}]""", "userName" to "Аня")
        override suspend fun restore(values: Map<String, String>) { saved += values }
    }

    // Быстрые итерации: проверяем формат, а не стойкость KDF.
    private fun encode(json: String, p: CharArray = pass) = BackupCodec.encode(json, p, iterations = 10_000)

    @Test fun codecRoundTripAndWrongPassword() {
        val data = encode("""{"привет":"мир"}""")
        assertEquals("""{"привет":"мир"}""", BackupCodec.decode(data, pass))
        assertFailsWith<BackupCodec.WrongPassword> { BackupCodec.decode(data, "другой пароль!".toCharArray()) }
    }

    @Test fun anyChangedByteIsDetected() {
        val data = encode("секрет")
        for (i in listOf(0, 6, 8, 20, 36, 45, data.size - 1)) {
            val broken = data.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFailsWith<BackupCodec.BackupException>("байт $i") { BackupCodec.decode(broken, pass) }
        }
    }

    @Test fun notABackupAndTooNew() {
        assertFailsWith<BackupCodec.NotABackup> { BackupCodec.decode("просто текст, не копия, длинная строка для проверки размера".toByteArray(), pass) }
        assertFailsWith<BackupCodec.NotABackup> { BackupCodec.decode(ByteArray(10), pass) }
        val newer = encode("x").also { it[6] = 9 }
        assertFailsWith<BackupCodec.TooNew> { BackupCodec.decode(newer, pass) }
    }

    @Test fun shortPasswordRejected() {
        assertFailsWith<IllegalArgumentException> { BackupCodec.encode("x", "коротко".toCharArray(), iterations = 10_000) }
    }

    @Test fun twoFilesFromSameDataDiffer() {
        assertTrue(!encode("одно и то же").contentEquals(encode("одно и то же")), "соль и IV случайные")
    }

    private suspend fun fill(s: LocalStore) {
        s.notes.create(NoteKind.NOTE, "Рецепт борща", "свёкла, капуста")
        s.notes.create(NoteKind.IDEA, "Идея", "сделать сайт")
        s.tasks.create("Купить молоко", dueDate = LocalDate.of(2026, 9, 26))
        s.reminders.create("Достать мясо", Instant.parse("2026-09-26T04:50:00Z"), Recurrence(Recurrence.Frequency.DAILY), "Europe/Moscow")
        s.expenses.create(50000, "RUB", "Транспорт", "такси", LocalDate.of(2026, 9, 25))
        s.memories.create("Я аллергик на орехи", "fact")
        s.shopping.add("Покупки", listOf("молоко", "хлеб"))
        s.routines.save("доброе утро", listOf("какая погода", "что на сегодня"))
        s.conversations.add("c1", MessageRole.USER, "личная переписка")
        s.secrets.save("Пароль", "12345")
        s.habits.log("water", "вода", 2.0, Instant.parse("2026-09-25T08:00:00Z"))
        val deleted = s.notes.create(NoteKind.NOTE, "Удалённая", "текст")
        s.notes.delete(deleted.id)
    }

    @Test fun fullRoundTripToCleanDevice() = runTest {
        val a = device(); fill(a)
        val file = BackupService(a, Extras(), "2.4.0").export(pass, includeChat = false)
        val b = device()
        val extras = Extras()
        val report = BackupService(b, extras).restore(file, pass)
        assertEquals(2, b.notes.all().size)
        assertEquals("Купить молоко", b.tasks.all().single().title)
        assertEquals(Recurrence.Frequency.DAILY, b.reminders.all().single().recurrence?.frequency)
        assertEquals(50000, b.expenses.all().single().amountMinor)
        assertEquals("Я аллергик на орехи", b.memories.all().single().content)
        assertEquals(setOf("молоко", "хлеб"), b.shopping.all().map { it.text.lowercase() }.toSet())
        assertEquals("доброе утро", b.routines.all().single().trigger)
        assertEquals("12345", b.secrets.all().single().content)
        assertEquals(2.0, b.habits.since(Instant.EPOCH).single().amount)
        assertEquals("Аня", extras.saved["userName"])
        assertTrue(report.restored >= 8 && report.secretNotes == 1 && report.habits == 1, report.toString())
        // Удалённая заметка осталась удалённой и не воскресла.
        assertFalse(b.notes.all().any { it.title == "Удалённая" })
        // Переписка без согласия в копию не попала.
        assertTrue(b.conversations.recent(10).isEmpty())
        // Всё восстановленное помечено «ждёт отправки» — при входе в аккаунт уйдёт на сервер.
        assertTrue(b.pendingChanges() > 0)
    }

    @Test fun chatIncludedOnlyWhenAsked() = runTest {
        val a = device(); fill(a)
        val file = BackupService(a).export(pass, includeChat = true)
        val b = device()
        BackupService(b).restore(file, pass)
        assertEquals(1, b.conversations.recent(10).size)
    }

    @Test fun newerLocalDataIsNotOverwritten() = runTest {
        val a = device()
        val note = a.notes.create(NoteKind.NOTE, "Заметка", "старый текст")
        val file = BackupService(a).export(pass, includeChat = false)
        time.advanceMillis(60_000)
        a.notes.update(note.copy(content = "новый текст"))
        val report = BackupService(a).restore(file, pass)
        assertEquals("новый текст", a.notes.get(note.id)?.content)
        assertEquals(1, report.skippedNewerLocal)
        assertEquals(0, report.restored)
    }

    @Test fun backupHoldsNoSecretsOfTheApp() = runTest {
        val a = device(); fill(a)
        val json = BackupCodec.decode(BackupService(a, Extras()).export(pass, includeChat = true), pass)
        for (word in listOf("api_key", "access_token", "refresh_token", "passphrase", "Bearer")) assertFalse(json.contains(word, ignoreCase = true), word)
    }

    @Test fun garbageInsideEncryptedFileIsNotABackup() = runTest {
        val file = encode("это не json {{{")
        assertFailsWith<BackupCodec.NotABackup> { BackupService(device()).restore(file, pass) }
    }
}
