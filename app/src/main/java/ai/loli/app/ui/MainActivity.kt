package ai.loli.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.loli.app.AppContainer
import ai.loli.app.LoliApp
import ai.loli.app.settings.ThemeMode
import ai.loli.app.ui.nav.Navigator
import ai.loli.app.ui.nav.Tab
import ai.loli.app.ui.nav.rememberNavigator
import ai.loli.app.ui.screens.AuthScreen
import ai.loli.app.ui.screens.ExpensesScreen
import ai.loli.app.ui.screens.HistoryScreen
import ai.loli.app.ui.screens.HomeScreen
import ai.loli.app.ui.screens.HomeViewModel
import ai.loli.app.ui.screens.PlansScreen
import ai.loli.app.ui.screens.RecordsScreen
import ai.loli.app.ui.screens.SettingsPage
import ai.loli.app.ui.screens.SettingsScreen
import ai.loli.app.ui.theme.LoliTheme
import ai.loli.app.voice.WakeWordService
import ai.loli.core.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as LoliApp).container

    /** Запрос «начать слушать» от ярлыка или жеста ассистента. */
    private val listenRequest = MutableStateFlow(0)
    /** Открыть настройки (из системных настроек телефона — «Настройки в приложении»). */
    private val openSettingsRequest = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // После пересоздания (поворот экрана) исходный intent не должен снова включать микрофон.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Цвет значков строки состояния — под выбранную в приложении тему, а не только системную.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            LoliTheme(settings.themeMode, settings.dynamicColor) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { LoliRoot(container, listenRequest, openSettingsRequest) }
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
        lifecycleScope.launch {
            val s = container.settings.current() // не значение по умолчанию до загрузки DataStore
            if (s.wakeWordEnabled && container.voskModels.isObtainable() && !WakeWordService.running) WakeWordService.start(this@MainActivity)
            // Проверка новой версии не чаще раза в 6 часов; установка — по кнопке на главной или автоматически.
            if (s.autoUpdate && System.currentTimeMillis() - container.updates.lastCheckedAt > 6 * 3600_000L) container.updates.check()
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, ACTION_LISTEN, "android.intent.action.SEARCH_LONG_PRESS" ->
                listenRequest.value = listenRequest.value + 1
            // Уведомление «Доступна новая версия»: сразу скачиваем и ставим.
            ACTION_UPDATE -> lifecycleScope.launch {
                val info = container.updates.check() ?: return@launch
                if (!container.updates.canInstall()) startActivity(container.updates.installPermissionIntent())
                else container.updates.downloadAndInstall(info)
            }
            // «Настройки приложения» из системных настроек телефона.
            Intent.ACTION_APPLICATION_PREFERENCES -> openSettingsRequest.value = openSettingsRequest.value + 1
        }
    }

    companion object {
        const val ACTION_LISTEN = "ai.loli.action.LISTEN"
        const val ACTION_UPDATE = "ai.loli.action.UPDATE"
    }
}

/** Системное окно распознавания Google — запасной вариант, если встроенное распознавание не работает. */
@Composable
fun rememberSystemSpeechDialog(c: AppContainer): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else null
        c.voice.onSystemDialogResult(text)
    }
    val name = c.settings.settings.collectAsStateWithLifecycle().value.assistantName
    val show = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите, $name слушает")
        }
        runCatching { launcher.launch(intent) }.onFailure { c.voice.onSystemDialogResult(null) }
        Unit
    }
    // Окно показывает только экран, который сейчас виден (главный экран или окно ассистента).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { c.voice.systemDialogRequests.collect { show() } }
    }
    return show
}

/** Запрос микрофона и запуск прослушивания. */
@Composable
fun rememberListenAction(c: AppContainer): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pending) c.voice.startListening()
        pending = false
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            c.voice.startListening()
        } else {
            pending = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoliRoot(c: AppContainer, listenRequest: MutableStateFlow<Int>, openSettingsRequest: MutableStateFlow<Int>) {
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var showAuth by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val listen = rememberListenAction(c)
    rememberSystemSpeechDialog(c)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val started by c.started.collectAsStateWithLifecycle()
    val loaded by c.settings.loaded.collectAsStateWithLifecycle()
    if (!started || !loaded) {
        Box(Modifier.fillMaxSize()) // доли секунды при запуске: сессия и настройки ещё читаются
        return
    }

    val needsAuth = showAuth || (auth is AuthState.SignedOut && !settings.localOnly && c.supabaseConfig().isConfigured && !settings.onboardingDone)
    if (needsAuth) {
        BackHandler(enabled = showAuth) { showAuth = false }
        AuthScreen(c, onClose = if (showAuth) ({ showAuth = false }) else null) {
            showAuth = false
            c.appScope.launch { c.settings.setOnboardingDone(true) }
        }
        return
    }

    val nav = rememberNavigator()
    // Выбранные разделы внутри вкладок — общие, чтобы главная могла открыть, например, «Напоминания».
    val recordsSegment = rememberSaveable { mutableIntStateOf(0) }
    val plansSegment = rememberSaveable { mutableIntStateOf(0) }

    val request by listenRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        if (request > 0) {
            listenRequest.value = 0 // запрос обработан — не повторять при возврате в композицию
            nav.showRoot(Tab.HOME)
            listen()
        }
    }
    val settingsRequest by openSettingsRequest.collectAsStateWithLifecycle()
    LaunchedEffect(settingsRequest) {
        if (settingsRequest > 0) {
            openSettingsRequest.value = 0
            nav.showRoot(Tab.SETTINGS)
        }
    }
    BackHandler(enabled = nav.canGoBack) { nav.back() }

    // Пока открыта клавиатура, нижнее меню прячется, а поле ввода встаёт прямо над клавиатурой.
    val imeVisible = WindowInsets.isImeVisible
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (!imeVisible) BottomBar(nav) },
    ) { padding ->
        val holder = rememberSaveableStateHolder()
        AnimatedContent(
            targetState = nav.tab to nav.route,
            transitionSpec = {
                val (fromTab, fromRoute) = initialState
                val (toTab, toRoute) = targetState
                when {
                    // Вложенный экран открывается справа, «Назад» — уезжает вправо.
                    fromTab == toTab && fromRoute == null && toRoute != null ->
                        (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(tween(280)) { -it / 8 } + fadeOut(tween(180)))
                    fromTab == toTab && fromRoute != null && toRoute == null ->
                        (slideInHorizontally(tween(280)) { -it / 8 } + fadeIn(tween(220))) togetherWith (slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(180)))
                    // Смена вкладки — мягкое проявление с лёгким масштабом.
                    else -> (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.97f)) togetherWith fadeOut(tween(140))
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            label = "screens",
        ) { (tab, route) ->
            holder.SaveableStateProvider("${tab.name}/${route.orEmpty()}") {
                Box(Modifier.fillMaxSize()) {
                    Screen(c, nav, tab, route, listen, recordsSegment.intValue, { recordsSegment.intValue = it },
                        plansSegment.intValue, { plansSegment.intValue = it }, onOpenAuth = { showAuth = true })
                }
            }
        }
    }
}

@Composable
private fun Screen(
    c: AppContainer,
    nav: Navigator,
    tab: Tab,
    route: String?,
    listen: () -> Unit,
    recordsSegment: Int,
    setRecordsSegment: (Int) -> Unit,
    plansSegment: Int,
    setPlansSegment: (Int) -> Unit,
    onOpenAuth: () -> Unit,
) {
    val back: () -> Unit = { nav.back() }
    when (tab) {
        Tab.HOME -> {
            val vm: HomeViewModel = viewModel { HomeViewModel(c) }
            HomeScreen(
                vm,
                onMic = listen,
                openRecords = { segment -> setRecordsSegment(segment); nav.showRoot(Tab.RECORDS) },
                openPlans = { segment -> setPlansSegment(segment); nav.showRoot(Tab.PLANS) },
                openExpenses = { nav.showRoot(Tab.EXPENSES) },
                openSettings = { page -> if (page == null) nav.showRoot(Tab.SETTINGS) else { nav.showRoot(Tab.SETTINGS); nav.open(page, Tab.SETTINGS) } },
                openHistory = { nav.open("history", Tab.RECORDS) },
            )
        }
        Tab.RECORDS -> when (route) {
            "history" -> HistoryScreen(c, back)
            else -> RecordsScreen(c, recordsSegment, setRecordsSegment, openHistory = { nav.open("history") })
        }
        Tab.PLANS -> PlansScreen(c, plansSegment, setPlansSegment)
        Tab.EXPENSES -> ExpensesScreen(c)
        Tab.SETTINGS -> {
            val providerId = route?.takeIf { it.startsWith("settings/ai/") }?.removePrefix("settings/ai/")
            val provider = providerId?.let { ai.loli.core.ai.AIProviderType.fromIdOrNull(it) }
            if (provider != null) {
                ai.loli.app.ui.screens.ProviderScreen(c, provider, back)
            } else {
                val page = route?.let { r -> SettingsPage.entries.firstOrNull { it.route == r } }
                SettingsScreen(c, page, open = { p -> nav.open(p.route) }, openRoute = { nav.open(it) }, onBack = back, onOpenAuth = onOpenAuth)
            }
        }
    }
}

@Composable
private fun BottomBar(nav: Navigator) {
    Column {
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            Tab.entries.forEach { tab ->
                val selected = nav.tab == tab
                // Выбранная вкладка слегка «подпрыгивает».
                val scale by animateFloatAsState(if (selected) 1.12f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "tab")
                NavigationBarItem(
                    selected = selected,
                    onClick = { nav.select(tab) },
                    icon = { Icon(tab.icon, contentDescription = tab.title, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) },
                    label = { Text(tab.title, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
