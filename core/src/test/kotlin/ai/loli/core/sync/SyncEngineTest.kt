package ai.loli.core.sync

import ai.loli.core.data.LocalStore
import ai.loli.core.db.LoliDatabase
import ai.loli.core.model.NoteKind
import ai.loli.core.remote.RemoteDataSource
import ai.loli.core.remote.RemoteException
import ai.loli.core.util.FixedTimeSource
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Эмулятор Supabase: хранит строки по владельцу (как RLS), проставляет server_updated_at
 * и применяет last-write-wins как серверный триггер из миграции.
 */
class FakeSupabase {
    var currentUser = "user-a"
    var offline = false
    /** Как в Postgres: все строки одного запроса получают одну и ту же метку server_updated_at. */
    var sharedStamp = false
    private var serverClock = Instant.parse("2026-09-25T00:00:00Z")
    val tables = HashMap<String, LinkedHashMap<String, JsonObject>>()

    fun remoteFor(user: String): RemoteDataSource = object : RemoteDataSource {
        override suspend fun upsert(table: String, rows: List<JsonObject>) {
            currentUser = user
            if (offline) throw RemoteException.Offline()
            val t = tables.getOrPut(table) { LinkedHashMap() }
            if (sharedStamp) serverClock = serverClock.plusSeconds(1)
            for (row in rows) {
                val id = (row["id"] as JsonPrimitive).content
                val existing = t[id]
                if (existing != null && existing.s("user_id") != user) throw RemoteException.Http(403, "row-level security")
                if (existing != null && Instant.parse(row.s("updated_at")!!).isBefore(Instant.parse(existing.s("updated_at")!!))) continue
                if (!sharedStamp) serverClock = serverClock.plusSeconds(1)
                t[id] = JsonObject(row + mapOf("user_id" to JsonPrimitive(user), "server_updated_at" to JsonPrimitive(serverClock.toString())))
            }
        }

        override suspend fun fetchChanges(table: String, since: Instant?, afterId: String?, limit: Int): List<JsonObject> {
            if (offline) throw RemoteException.Offline()
            fun stamp(r: JsonObject) = Instant.parse(r.s("server_updated_at")!!)
            return tables[table].orEmpty().values
                .filter { it.s("user_id") == user }
                .filter { r ->
                    since == null || stamp(r).isAfter(since) ||
                        (afterId != null && stamp(r) == since && r.s("id")!! > afterId)
                }
                .sortedWith(compareBy({ stamp(it) }, { it.s("id") }))
                .take(limit)
        }

        override suspend fun fetchSingle(table: String): JsonObject? = null
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}

class SyncEngineTest {
    private val time = FixedTimeSource(Instant.parse("2026-09-25T09:00:00Z"), ZoneId.of("Europe/Moscow"))
    private fun device(): LocalStore = LocalStore(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LoliDatabase.Schema.create(it) }, time, Dispatchers.Unconfined)
    private fun engine(store: LocalStore, remote: RemoteDataSource) = SyncEngine(store, remote, userId = { "user-a" })

    @Test fun localDataUploadedAndDownloadedOnSecondDevice() = runTest {
        val server = FakeSupabase()
        val phone = device(); val pc = device()
        phone.notes.create(NoteKind.IDEA, "Приложение для холодильника", "учёт продуктов")
        phone.expenses.create(85_000, "RUB", "Продукты", "", LocalDate.of(2026, 9, 25))
        phone.tasks.create("Купить молоко")
        val r1 = engine(phone, server.remoteFor("user-a")).sync()
        assertTrue(r1.ok)
        assertEquals(3, r1.pushed)
        assertEquals(0, phone.pendingChanges())

        val r2 = engine(pc, server.remoteFor("user-a")).sync()
        assertEquals(3, r2.pulled)
        assertEquals("Приложение для холодильника", pc.notes.all().single().title)
        assertEquals(85_000, pc.expenses.all().single().amountMinor)
        assertEquals(0, pc.pendingChanges(), "скачанные записи не должны помечаться к отправке")
    }

    @Test fun offlineChangesAreKeptAndSyncedLater() = runTest {
        val server = FakeSupabase()
        val phone = device()
        server.offline = true
        phone.tasks.create("Задача без интернета")
        val report = engine(phone, server.remoteFor("user-a")).sync()
        assertTrue(report.offline)
        assertEquals(1, phone.pendingChanges(), "данные не теряются без сети")
        assertEquals("Задача без интернета", phone.tasks.all().single().title)

        server.offline = false
        val retry = engine(phone, server.remoteFor("user-a")).sync()
        assertTrue(retry.ok)
        assertEquals(0, phone.pendingChanges())
        assertEquals(1, server.tables["tasks"]!!.size)
    }

    @Test fun concurrentNoteEditsAreMergedWithoutLoss() = runTest {
        val server = FakeSupabase()
        val phone = device(); val pc = device()
        val note = phone.notes.create(NoteKind.NOTE, "Идеи для дня рождения", "")
        engine(phone, server.remoteFor("user-a")).sync()
        engine(pc, server.remoteFor("user-a")).sync()

        // Оба устройства офлайн дописывают в одну заметку.
        phone.notes.append(note.id, "Игра Secret Identity")
        time.advanceMillis(1000)
        pc.notes.append(note.id, "Парфюмерный адвент")

        engine(phone, server.remoteFor("user-a")).sync()
        val pcReport = engine(pc, server.remoteFor("user-a")).sync()
        assertEquals(1, pcReport.conflicts)
        engine(phone, server.remoteFor("user-a")).sync()

        for (d in listOf(phone, pc)) {
            val content = d.notes.get(note.id)!!.content
            assertTrue(content.contains("Secret Identity"), content)
            assertTrue(content.contains("Парфюмерный адвент"), content)
        }
        assertEquals(phone.notes.get(note.id)!!.content, pc.notes.get(note.id)!!.content)
    }

    @Test fun lastWriteWinsForSimpleRecordsAndDeletesPropagate() = runTest {
        val server = FakeSupabase()
        val phone = device(); val pc = device()
        val task = phone.tasks.create("Позвонить клиенту")
        engine(phone, server.remoteFor("user-a")).sync()
        engine(pc, server.remoteFor("user-a")).sync()

        phone.tasks.setDone(task.id, true)
        time.advanceMillis(5000)
        pc.tasks.update(pc.tasks.get(task.id)!!.copy(title = "Позвонить клиенту до обеда"))
        engine(phone, server.remoteFor("user-a")).sync()
        engine(pc, server.remoteFor("user-a")).sync()
        engine(phone, server.remoteFor("user-a")).sync()
        assertEquals("Позвонить клиенту до обеда", phone.tasks.get(task.id)!!.title)

        pc.tasks.delete(task.id)
        engine(pc, server.remoteFor("user-a")).sync()
        engine(phone, server.remoteFor("user-a")).sync()
        assertTrue(phone.tasks.all().isEmpty(), "удаление (tombstone) должно синхронизироваться")
    }

    @Test fun otherUsersDataIsNeverVisible() = runTest {
        val server = FakeSupabase()
        val alice = device(); val bob = device()
        alice.memories.create("Секрет Алисы", "fact")
        engine(alice, server.remoteFor("user-a")).sync()
        SyncEngine(bob, server.remoteFor("user-b"), userId = { "user-b" }).sync()
        assertTrue(bob.memories.all().isEmpty())
    }

    @Test fun cannotOverwriteForeignRowById() = runTest {
        val server = FakeSupabase()
        val alice = device()
        val m = alice.memories.create("Секрет", "fact")
        engine(alice, server.remoteFor("user-a")).sync()
        val row = server.tables["memories"]!![m.id]!!
        assertFailsWith<RemoteException.Http> {
            server.remoteFor("user-b").upsert("memories", listOf(JsonObject(row + ("content" to JsonPrimitive("взлом")))))
        }
    }

    @Test fun pagesWithSharedTimestampLoseNothing() = runTest {
        val server = FakeSupabase().apply { sharedStamp = true }
        val laptop = device(); val phone = device()
        // 7 записей одним запросом: у всех одна метка сервера; страница — 3 строки, граница режет группу.
        repeat(7) { laptop.memories.create("факт $it", "fact") }
        engine(laptop, server.remoteFor("user-a")).sync()
        SyncEngine(phone, server.remoteFor("user-a"), userId = { "user-a" }, pageSize = 3).sync()
        assertEquals(7, phone.memories.all().size, "ни одна строка с общей меткой не должна потеряться на границе страницы")
    }

    @Test fun chatTableSkippedUntilEnabled() = runTest {
        val server = FakeSupabase()
        val a = device(); val b = device()
        a.conversations.add("conv-1", ai.loli.core.model.MessageRole.USER, "личная переписка")
        a.notes.create(NoteKind.NOTE, "заметка", "текст")
        SyncEngine(a, server.remoteFor("user-a"), userId = { "user-a" }, tableEnabled = { it != "conversation_messages" }).sync()
        assertTrue(server.tables["conversation_messages"].isNullOrEmpty(), "переписка не должна уходить на сервер без согласия")
        assertEquals(1, server.tables["notes"]?.size)
        SyncEngine(a, server.remoteFor("user-a"), userId = { "user-a" }).sync()
        assertEquals(1, server.tables["conversation_messages"]?.size, "после включения переписка синхронизируется")
    }
}
