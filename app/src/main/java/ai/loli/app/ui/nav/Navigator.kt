package ai.loli.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Лоли", Icons.Rounded.GraphicEq),
    RECORDS("Записи", Icons.Rounded.StickyNote2),
    PLANS("Планы", Icons.Rounded.Event),
    EXPENSES("Расходы", Icons.Rounded.Payments),
    SETTINGS("Настройки", Icons.Rounded.Settings),
}

/**
 * Простая и предсказуемая навигация: у каждой вкладки свой стек вложенных экранов.
 *  - нажатие на другую вкладку всегда переключает на неё (стек вкладки сохраняется);
 *  - повторное нажатие на текущую вкладку возвращает к её началу;
 *  - «Назад» сначала закрывает вложенный экран, затем возвращает на главную.
 * Так переход «Главная → Настройки → Главная» по нижнему меню работает всегда.
 */
@Stable
class Navigator(initialTab: Tab, initialStacks: Map<Tab, List<String>>) {
    var tab by mutableStateOf(initialTab)
        private set
    private val stacks: Map<Tab, SnapshotStateList<String>> =
        Tab.entries.associateWith { t -> mutableStateListOf<String>().apply { addAll(initialStacks[t].orEmpty()) } }

    /** Текущий вложенный экран вкладки или null, если открыт её корень. */
    val route: String? get() = stacks.getValue(tab).lastOrNull()

    val canGoBack: Boolean get() = stacks.getValue(tab).isNotEmpty() || tab != Tab.HOME

    fun select(target: Tab) {
        if (target == tab) stacks.getValue(target).clear() else tab = target
    }

    /** Открыть вложенный экран; с [inTab] — переключившись на нужную вкладку. */
    fun open(route: String, inTab: Tab? = null) {
        if (inTab != null) tab = inTab
        val stack = stacks.getValue(tab)
        if (stack.lastOrNull() != route) stack.add(route)
    }

    /** Показать корень вкладки (без вложенных экранов). */
    fun showRoot(target: Tab) {
        stacks.getValue(target).clear()
        tab = target
    }

    fun back(): Boolean {
        val stack = stacks.getValue(tab)
        return when {
            stack.isNotEmpty() -> { stack.removeAt(stack.lastIndex); true }
            tab != Tab.HOME -> { tab = Tab.HOME; true }
            else -> false
        }
    }

    companion object {
        val saver: Saver<Navigator, List<String>> = Saver(
            save = { nav -> listOf(nav.tab.name) + Tab.entries.map { t -> t.name + "=" + nav.stacks.getValue(t).joinToString("|") } },
            restore = { saved ->
                val tab = Tab.entries.firstOrNull { it.name == saved.firstOrNull() } ?: Tab.HOME
                val stacks = saved.drop(1).associate { line ->
                    val name = line.substringBefore('=')
                    val routes = line.substringAfter('=', "").split('|').filter { it.isNotBlank() }
                    (Tab.entries.firstOrNull { it.name == name } ?: Tab.HOME) to routes
                }
                Navigator(tab, stacks)
            },
        )
    }
}

@Composable
fun rememberNavigator(): Navigator = rememberSaveable(saver = Navigator.saver) { Navigator(Tab.HOME, emptyMap()) }
