package ai.loli.app

import ai.loli.app.update.UpdateManager
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    private fun cmp(a: String, b: String) = UpdateManager.compareVersions(a, b)

    @Test fun ordersStableAndBeta() {
        assertTrue(cmp("2.0.0", "1.9.0") > 0)
        assertTrue(cmp("2.0.0-beta.1", "1.9.0") > 0)
        assertTrue(cmp("2.0.0-beta.1", "2.0.0") < 0)
        assertTrue(cmp("2.0.0-beta.10", "2.0.0-beta.2") > 0)
        assertTrue(cmp("2.0.1", "2.0.0-beta.3") > 0)
        assertTrue(cmp("1.9.0-debug", "1.9.0") == 0)
    }
}
