package ai.loli.app

import ai.loli.app.reminders.ReminderReceiver
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** Настоящее срабатывание напоминания на эмуляторе: уведомление появляется один раз, разовое гаснет. */
@RunWith(AndroidJUnit4::class)
class RemindersInstrumentedTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context.applicationContext as LoliApp).container

    private fun shown(id: String) =
        context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == ReminderReceiver.notificationId(id) }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue("не дождались за $timeoutMs мс", condition())
    }

    @Test
    fun overdueReminderFiresOnceAndDeactivates() = runBlocking {
        val c = container
        c.awaitReady()
        val r = c.store.reminders.create("проверка эмулятора", Instant.now().minusSeconds(30), null, ZoneId.systemDefault().id)
        ReminderReceiver.fire(context, c, r.id)
        waitUntil { shown(r.id) } // показ тоже асинхронный
        assertFalse("разовое напоминание больше не активно", c.store.reminders.get(r.id)!!.active)
        context.getSystemService(NotificationManager::class.java).cancel(ReminderReceiver.notificationId(r.id))
        // Система убирает уведомление не мгновенно: ждём, пока оно исчезнет, иначе проверка ниже увидит старое.
        waitUntil { !shown(r.id) }
        // Повторный вызов (страховка рядом с будильником) не должен показать его снова.
        ReminderReceiver.fire(context, c, r.id)
        Thread.sleep(500)
        assertFalse("второй раз уведомления быть не должно", shown(r.id))
        c.store.reminders.delete(r.id)
        assertEquals(null, c.store.reminders.get(r.id)?.takeIf { it.active })
    }
}
