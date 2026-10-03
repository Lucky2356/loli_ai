package ai.loli.app

import ai.loli.app.ui.MainActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Приложение запускается и держится на экране без падения; копия открывается по своему действию. */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @Test
    fun launchesAndStaysResumed() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            Thread.sleep(3000)
            assertTrue(s.state.isAtLeast(Lifecycle.State.STARTED))
        }
    }

    @Test
    fun backupAndQuickActionsOpenWithoutCrash() {
        for (action in listOf(MainActivity.ACTION_BACKUP, MainActivity.ACTION_QUICK)) {
            val intent = android.content.Intent(androidx.test.core.app.ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .setAction(action).putExtra("kind", "task")
            ActivityScenario.launch<MainActivity>(intent).use { s ->
                Thread.sleep(2000)
                assertTrue("$action", s.state.isAtLeast(Lifecycle.State.STARTED))
            }
        }
    }
}
