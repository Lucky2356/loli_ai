package ai.loli.desktop

import ai.loli.core.assistant.ActionExecutor
import ai.loli.core.assistant.AssistantEngine
import ai.loli.core.assistant.AssistantSettings
import ai.loli.core.assistant.Persona
import ai.loli.core.assistant.ReminderScheduler
import ai.loli.core.assistant.TargetResolver
import ai.loli.core.data.LocalStore
import ai.loli.core.db.LoliDatabase
import ai.loli.core.health.Habits
import ai.loli.core.model.Reminder
import ai.loli.core.search.SearchService
import ai.loli.core.skills.SkillHost
import ai.loli.core.skills.Skills
import ai.loli.core.util.SystemTimeSource
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.Properties

/**
 * Всё, что нужно клиенту для Windows: база на компьютере, тот же движок Лоли, что и на телефоне,
 * команды компьютеру и напоминания в трее. Вход в аккаунт и синхронизация с телефоном — следующий шаг.
 */
class DesktopContainer(val dataDir: File = defaultDataDir()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val time = SystemTimeSource()
    val settings = DesktopSettings(File(dataDir, "settings.properties"))

    private val driver = JdbcSqliteDriver("jdbc:sqlite:" + File(dataDir, "loli.db").absolutePath, Properties(), LoliDatabase.Schema)
    val store = LocalStore(driver, time, Dispatchers.IO)

    val http = HttpClient(Java) {
        install(HttpTimeout) { requestTimeoutMillis = 20_000; connectTimeoutMillis = 10_000 }
        expectSuccess = false
    }

    val search = SearchService(store.notes, store.tasks, store.reminders, store.memories, store.embeddings) { null }
    private val resolver = TargetResolver(search, store.notes, store.tasks, store.reminders, store.memories)

    /** Будильников в Windows нет: напоминания проверяет [ReminderTicker], пока Лоли запущена (в том числе в трее). */
    private val scheduler = object : ReminderScheduler {
        override fun schedule(reminder: Reminder) = Unit
        override fun cancel(reminderId: String) = Unit
    }

    val device = DesktopDevice(store, time)

    val executor = ActionExecutor(
        store.notes, store.expenses, store.tasks, store.reminders, store.memories, search, resolver, scheduler, time, device,
        shopping = store.shopping, routines = store.routines, secrets = store.secrets,
    )

    private val skillHost = object : SkillHost {
        override fun setUserName(name: String?) = settings.update { it.copy(userName = name.orEmpty()) }
        override fun setCity(city: String?) = settings.update { it.copy(city = city.orEmpty()) }
    }

    val engine = AssistantEngine(
        notes = store.notes, tasks = store.tasks, reminders = store.reminders, memories = store.memories,
        conversations = store.conversations, search = search, executor = executor, time = time,
        settings = { assistantSettings() },
        aiProvider = { null },
        routines = { store.routines.all() }, shoppingItems = { store.shopping.all() },
        skills = Skills(skillHost, http, time),
        habits = Habits(store.habits, time),
        onPersona = { p -> settings.update { it.copy(persona = p.wire) } },
        expensesBetween = { a, b -> store.expenses.between(a, b) },
        daySpend = { d ->
            store.expenses.between(d, d).filter { it.category != ai.loli.core.nlp.ExpenseCategories.INCOME }.takeIf { it.isNotEmpty() }
                ?.let { l -> "Потрачено: " + l.groupBy { it.currency }.entries.joinToString(", ") { (c, x) -> ai.loli.core.nlp.Money.format(x.sumOf { it.amountMinor }, c) } + "." }
        },
    )

    fun assistantSettings(): AssistantSettings = settings.value.let {
        AssistantSettings(
            assistantName = it.assistantName, useAI = false, dialogMode = false,
            userName = it.userName.ifBlank { null }, city = it.city.ifBlank { null }, persona = Persona.of(it.persona),
        )
    }

    companion object {
        /** %APPDATA%\Loli на Windows, ~/.loli на остальных системах. */
        fun defaultDataDir(): File {
            val appData = System.getenv("APPDATA")
            val dir = if (!appData.isNullOrBlank()) File(appData, "Loli") else File(System.getProperty("user.home"), ".loli")
            return dir.apply { mkdirs() }
        }
    }
}
