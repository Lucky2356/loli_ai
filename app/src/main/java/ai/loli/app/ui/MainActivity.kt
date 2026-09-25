package ai.loli.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.loli.app.AppContainer
import ai.loli.app.LoliApp
import ai.loli.app.ui.screens.AuthScreen
import ai.loli.app.ui.screens.ExpensesScreen
import ai.loli.app.ui.screens.HistoryScreen
import ai.loli.app.ui.screens.HomeScreen
import ai.loli.app.ui.screens.HomeViewModel
import ai.loli.app.ui.screens.MemoriesScreen
import ai.loli.app.ui.screens.MemoryHubScreen
import ai.loli.app.ui.screens.NotesScreen
import ai.loli.app.ui.screens.RemindersScreen
import ai.loli.app.ui.screens.SettingsScreen
import ai.loli.app.ui.screens.TasksScreen
import ai.loli.app.ui.theme.LoliTheme
import ai.loli.app.voice.WakeWordService
import ai.loli.core.auth.AuthState
import ai.loli.core.model.NoteKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as LoliApp).container

    /** Запрос «начать слушать» от жеста ассистента, плитки или ярлыка. */
    private val listenRequest = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            LoliTheme {
                Surface(Modifier.fillMaxSize()) { LoliRoot(container, listenRequest) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Фоновое прослушивание можно (пере)запустить только когда приложение на экране (Android 14+).
        val s = container.settings.settings.value
        if (s.wakeWordEnabled && container.voskModels.isReady() && !WakeWordService.running) WakeWordService.start(this)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, ACTION_LISTEN, "android.intent.action.SEARCH_LONG_PRESS" ->
                listenRequest.value = listenRequest.value + 1
        }
    }

    companion object {
        const val ACTION_LISTEN = "ai.loli.action.LISTEN"
    }
}

private data class Tab(val route: String, val title: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Главная", Icons.Outlined.Home),
    Tab("memory", "Память", Icons.Outlined.Psychology),
    Tab("tasks", "Задачи", Icons.Outlined.TaskAlt),
    Tab("expenses", "Расходы", Icons.Outlined.Payments),
    Tab("settings", "Настройки", Icons.Outlined.Settings),
)

@Composable
private fun LoliRoot(c: AppContainer, listenRequest: MutableStateFlow<Int>) {
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var showAuth by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    var pendingListen by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingListen) c.voice.startListening()
        pendingListen = false
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun listen() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            c.voice.startListening()
        } else {
            pendingListen = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val needsAuth = showAuth || (auth is AuthState.SignedOut && !settings.localOnly && c.supabaseConfig().isConfigured && !settings.onboardingDone)
    if (needsAuth) {
        AuthScreen(c) {
            showAuth = false
            c.appScope.launch { c.settings.setOnboardingDone(true) }
        }
        return
    }

    val nav = rememberNavController()
    val request by listenRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        if (request > 0) {
            nav.navigate("home") { launchSingleTop = true }
            listen()
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = { navigateTab(nav, tab.route) },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                val vm: HomeViewModel = viewModel { HomeViewModel(c) }
                HomeScreen(vm, onOpen = { route -> nav.navigate(route) }, onMicClick = { listen() })
            }
            composable("memory") { MemoryHubScreen(c) { route -> nav.navigate(route) } }
            composable("tasks") { TasksScreen(c, onBack = null) }
            composable("expenses") { ExpensesScreen(c, onBack = null) }
            composable("settings") { SettingsScreen(c, onOpenAuth = { showAuth = true }) }
            composable("notes/{kind}") { entry ->
                val kind = if (entry.arguments?.getString("kind") == "idea") NoteKind.IDEA else NoteKind.NOTE
                NotesScreen(c, kind) { nav.popBackStack() }
            }
            composable("reminders") { RemindersScreen(c) { nav.popBackStack() } }
            composable("memories") { MemoriesScreen(c) { nav.popBackStack() } }
            composable("history") { HistoryScreen(c) { nav.popBackStack() } }
        }
    }
}

private fun navigateTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo("home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
