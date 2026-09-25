package ai.loli.app

import ai.loli.app.ui.nav.Navigator
import ai.loli.app.ui.nav.Tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigatorTest {
    @Test fun homeTabAlwaysReturnsHomeAfterOpeningSettingsFromHome() {
        val nav = Navigator(Tab.HOME, emptyMap())
        // «Только на устройстве» на главной открывает страницу настроек…
        nav.showRoot(Tab.SETTINGS)
        nav.open("settings/account", Tab.SETTINGS)
        assertEquals(Tab.SETTINGS, nav.tab)
        assertEquals("settings/account", nav.route)
        // …и нижнее меню «Главная» обязано вернуть на главную.
        nav.select(Tab.HOME)
        assertEquals(Tab.HOME, nav.tab)
        assertNull(nav.route)
        // Стек вкладки настроек сохраняется, повторное нажатие возвращает к её началу.
        nav.select(Tab.SETTINGS)
        assertEquals("settings/account", nav.route)
        nav.select(Tab.SETTINGS)
        assertNull(nav.route)
    }

    @Test fun backClosesNestedScreenThenGoesHome() {
        val nav = Navigator(Tab.RECORDS, emptyMap())
        nav.open("history")
        assertTrue(nav.back())
        assertNull(nav.route)
        assertTrue(nav.back())
        assertEquals(Tab.HOME, nav.tab)
        assertFalse(nav.canGoBack)
        assertFalse(nav.back())
    }
}
