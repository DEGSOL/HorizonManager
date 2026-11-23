package dev.horizonmanager

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CancellationException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) =
        super.attachBaseContext(appLanguageContext(newBase))

    private var notificationServer by mutableStateOf("")

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        notificationServer = notificationTarget(intent)
    }

    private fun notificationTarget(intent: Intent): String =
        intent.data?.takeIf { it.scheme == "horizon-manager" && it.host == "server" }
            ?.lastPathSegment.orEmpty()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationServer = notificationTarget(intent)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        setContent {
            var preset by remember { mutableStateOf(ThemePreset.fromId(preferences.getString("preset", null))) }
            DisposableEffect(preferences) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "preset") preset = ThemePreset.fromId(preferences.getString("preset", null))
                }
                preferences.registerOnSharedPreferenceChangeListener(listener)
                onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            HorizonTheme(preset) {
                val notices = remember { NoticeQueue() }
                CompositionLocalProvider(LocalNoticeQueue provides notices) {
                    HovqLinks {
                        Manager(viewModel(), notificationServer, { notificationServer = "" }, preset) {
                            preset = it
                            preferences.edit().putString("preset", it.id).apply()
                        }
                    }
                }
            }
        }
    }
}

private const val FEED = 0
private const val SETTINGS = 1
private const val FAVORITES = 2

internal fun pushSettingsRoute(current: String, next: String): String = when {
    next.isEmpty() -> ""
    current.substringAfterLast('/') == next -> current
    current.isEmpty() -> next
    else -> "$current/$next"
}

private data class Screen(val page: String, val issueId: String = "") {
    val reading get() = page == "feed" || page == "archived"
    val tabIndex get() = when (page) {
        "feed" -> FEED
        "settings" -> SETTINGS
        "favorites" -> FAVORITES
        else -> null
    }
}

@Composable
private fun mainTabs() = listOf(
    HorizonTab(R.string.feed, ImageVector.vectorResource(R.drawable.ic_nav_feed)),
    HorizonTab(R.string.settings, ImageVector.vectorResource(R.drawable.ic_nav_sliders)),
    HorizonTab(R.string.favorites_title, ImageVector.vectorResource(R.drawable.ic_star_outline)),
)

@Composable
private fun Manager(model: ManagerModel, notificationServer: String, consumed: () -> Unit,
                    preset: ThemePreset, selectTheme: (ThemePreset) -> Unit) {
    val panel by model.state.collectAsStateWithLifecycle()
    val currentServer = panel.server
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(currentServer?.id) { mutableIntStateOf(FEED) }
    var manageServers by remember { mutableStateOf(false) }
    var editServer by remember { mutableStateOf(false) }
    var editVerification by remember { mutableStateOf("") }
    var route by rememberSaveable(currentServer?.id) { mutableStateOf("") }
    val configPage = route.substringAfterLast('/')
    var previousTab by rememberSaveable { mutableIntStateOf(FEED) }
    var navVisible by remember(tab, currentServer?.id) { mutableStateOf(true) }
    var overlay by remember(tab, currentServer?.id) { mutableStateOf(ReadingOverlay.None) }
    val feedFilters = rememberSaveable(currentServer?.id, saver = NewsFilters.Saver) { NewsFilters() }
    val screenState = rememberSaveableStateHolder()
    val readingPrefs = remember(ctx) { ctx.getSharedPreferences("reading", android.content.Context.MODE_PRIVATE) }
    var defaultOrder by remember { mutableStateOf(DeviceBackup.readingOrder(ctx)) }
    var launcherIcon by remember { mutableStateOf(LauncherAppearance.current(ctx)) }
    DisposableEffect(readingPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "default_order") defaultOrder = DeviceBackup.readingOrder(ctx)
        }
        readingPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { readingPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val notices = requireNotNull(LocalNoticeQueue.current).messages
    var localSave by remember { mutableIntStateOf(0) }
    val remoteSave by model.settingsSaved.collectAsStateWithLifecycle()
    var lastSave by remember { mutableIntStateOf(remoteSave) }
    var lastLocalSave by remember { mutableIntStateOf(localSave) }
    LaunchedEffect(remoteSave, localSave) {
        if (remoteSave == lastSave && localSave == lastLocalSave) return@LaunchedEffect
        lastSave = remoteSave
        lastLocalSave = localSave
        launcherIcon = LauncherAppearance.current(ctx)
        notices.showSnackbar(SavedNotice(ui(R.string.hovq_settings_saved)))
    }
    var archiveIssue by rememberSaveable(currentServer?.id) { mutableStateOf("") }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val now by produceState(Instant.now(), lifecycle) {
        lifecycle.repeatAt(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                value = Instant.now()
                delay(30_000)
            }
        }
    }
    val tabs = mainTabs()
    val issues = remember(panel.archive, panel.collectionHours, panel.manuallyArchived, panel.hiddenDigests, now) {
        splitDigests(panel.archive, panel.collectionHours, now, panel.manuallyArchived, panel.hiddenDigests)
    }
    val openedIssue = issues.archive.find { it.optString("id") == archiveIssue }
    val feed = issues.feed
    val feedRun = feed?.optString("id")
    val feedLanguage = feed?.optJSONArray("languages")?.strings()?.let { digestLanguage(it, panel.preferredDigestLanguage()) }
    val unread = if (currentServer == null || feedRun.isNullOrBlank() || feedLanguage == null) 0
        else key(currentServer.id, feedRun, feedLanguage) {
            val updates = remember { model.unreadFeed(currentServer.id, feedRun, feedLanguage) }
            updates.collectAsStateWithLifecycle(initialValue = 0).value
        }
    val archiveFilters = rememberSaveable(currentServer?.id, archiveIssue, saver = NewsFilters.Saver) { NewsFilters() }
    LaunchedEffect(feedFilters, archiveFilters, defaultOrder) {
        for (filters in listOf(feedFilters, archiveFilters)) {
            if (!filters.explicitOrder) filters.order = defaultOrder
        }
    }
    LaunchedEffect(editServer, panel.sshVerification) {
        if (editServer && panel.sshVerification.isNotEmpty() && panel.sshVerification != editVerification) {
            editServer = false
            if (!panel.ready) tab = FEED
        }
    }
    LaunchedEffect(configPage, openedIssue?.optString("id"), currentServer?.id) {
        if (configPage == "archive" && openedIssue != null) {
            snapshotFlow { panel.busy }.first { !it }
            val issue = openedIssue
            model.openArchive(issue)
        }
    }
    fun openSettings(page: String) {
        if (tab != SETTINGS) previousTab = tab
        tab = SETTINGS
        route = pushSettingsRoute(route, page)
    }
    LaunchedEffect(notificationServer) {
        if (notificationServer.isNotEmpty()) {
            tab = FEED
            archiveIssue = ""
            route = ""
            manageServers = false
            editServer = false
            model.openNotification(notificationServer)
            consumed()
        }
    }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var fileAction by rememberSaveable { mutableStateOf("") }
    var restoreFile by remember { mutableStateOf("") }
    LaunchedEffect(panel.userProblem) {
        val problem = panel.userProblem.takeIf(String::isNotEmpty) ?: return@LaunchedEffect
        notices.showSnackbar(errorText(problem), duration = SnackbarDuration.Long)
        model.consumeProblem(problem)
    }
    var backupDialog by remember { mutableStateOf("") }
    var notificationOffer by rememberSaveable { mutableStateOf(true) }
    var notificationAllowed by remember { mutableStateOf(DigestNotifications.deliveryAllowed(ctx)) }
    var requestingAlerts by rememberSaveable { mutableStateOf(false) }
    var enableDigestAfterPermission by rememberSaveable { mutableStateOf(false) }
    val notificationPrefs = remember(ctx) { ctx.getSharedPreferences("notification_permission", android.content.Context.MODE_PRIVATE) }
    val activity = androidx.activity.compose.LocalActivity.current
    fun permissionReturned() {
        notificationAllowed = DigestNotifications.deliveryAllowed(ctx)
        if (!notificationAllowed) enableDigestAfterPermission = false
        requestingAlerts = false
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionReturned() }
    val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionReturned()
    }
    fun requestNotifications() {
        if (DigestNotifications.deliveryAllowed(ctx)) return
        requestingAlerts = true
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            (!notificationPrefs.getBoolean("requested", false) ||
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true)) {
            notificationPrefs.edit().putBoolean("requested", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationSettings.launch(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
    }
    DisposableEffect(lifecycle, ctx) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) notificationAllowed = DigestNotifications.deliveryAllowed(ctx)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enableDigestAfterPermission, notificationAllowed, currentServer, panel.ready, panel.busy) {
        if (enableDigestAfterPermission && notificationAllowed && panel.ready && !panel.busy && currentServer != null) {
            enableDigestAfterPermission = false
            if (!currentServer.background) model.settings(currentServer.https, true)
            else DigestWorker.sync(ctx, currentServer.id)
        }
    }
    var dismissedAgentUpdates by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val output = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val content = model.takeExportDocument()
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    require(!content.isNullOrBlank())
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) } ?: error("output")
                }.isSuccess
            }
            if (!saved) notices.showSnackbar(ui(R.string.ui_could_not_write_the_file))
        }
    }
    val input = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val action = fileAction
            fileAction = ""
            try {
                val content = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBounded(9_000_000).decodeToString() }
                        ?: error("input")
                }
                if (action.isNotEmpty()) {
                    restoreFile = content.trim()
                    backupDialog = "${action}_import"
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                notices.showSnackbar(ui(R.string.ui_the_file_is_damaged_too_large))
            }
        }
    }
    LaunchedEffect(panel.file) {
        panel.file?.let {
            model.prepareExportDocument(it)
            output.launch(if (panel.deviceFile) "hovq-settings.hqb" else "horizon-settings.hmb")
            model.clearFile()
        }
    }
    val running = panel.jobs.firstOrNull()?.optString("state") in setOf("running", "queued")
    val checkingInstall = panel.probe["install"] == "running" || panel.updatingAgent
    val watchingFeed = panel.ready && tab == FEED && !manageServers && !editServer
    val preparing = digestIsPreparing(panel.jobs)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatAt(androidx.lifecycle.Lifecycle.State.RESUMED) {
            model.networkAvailable.collect(model::networkChanged)
        }
    }
    LaunchedEffect(currentServer?.id, panel.ready, running, checkingInstall, watchingFeed, preparing, lifecycle, locale) {
        androidx.lifecycle.Lifecycle.State.RESUMED.let { minimum ->
            lifecycle.repeatAt(minimum) {
                while (true) {
                    delay(when {
                        checkingInstall -> 5_000
                        watchingFeed && preparing -> 5_000
                        running -> 12_000
                        watchingFeed -> 15_000
                        else -> 60_000
                    })
                    if (panel.ready) model.refresh(quiet = true)
                    else if (currentServer != null) model.refreshInstallation()
                }
            }
        }
    }
    var dirtyPage by remember(configPage, currentServer?.id) { mutableStateOf(false) }
    val showingSetupSettings = !panel.ready && tab == SETTINGS && !editServer
    fun navigateBack() {
        when {
            manageServers -> manageServers = false
            editServer -> editServer = false
            tab == SETTINGS && configPage == "archive" && archiveIssue.isNotEmpty() -> {
                archiveIssue = ""
                model.leaveArchive()
            }
            tab == SETTINGS && route.isNotEmpty() -> route = route.substringBeforeLast('/', "")
            showingSetupSettings -> tab = FEED
            tab != FEED -> { tab = if (previousTab != tab) previousTab else FEED; previousTab = FEED; if (tab == FEED) model.showFeed() }
        }
    }

    val editorBack = remember(configPage) { mutableStateOf<(() -> Unit)?>(null) }
    fun goBack() {
        editorBack.value?.let { it(); return }
        if (dirtyPage) confirm = ui(R.string.editorial_discard_prompt) to ::navigateBack else navigateBack()
    }
    val canGoBack = manageServers || editServer || configPage.isNotEmpty() || showingSetupSettings
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = canGoBack || tab != FEED) { events ->
        try {
            events.collect { backProgress = it.progress }
            goBack()
        } finally { backProgress = 0f }
    }

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    confirm?.let { (description, action) ->
        HorizonAlertDialog(onDismissRequest = { confirm = null }, title = { Text(ui(R.string.ui_confirm_action)) },
            text = { Text(description) }, confirmButton = {
                Button(onClick = {
                    confirm = null
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    action()
                }) { Text(ui(R.string.ui_confirm)) }
            }, dismissButton = { TextButton(onClick = { confirm = null }) { Text(ui(R.string.ui_cancel)) } })
    }
    if (backupDialog.isNotEmpty()) {
        val device = backupDialog.startsWith("device")
        val restoring = backupDialog.endsWith("import")
        PasswordDialog(
            ui(if (restoring) R.string.hovq_import else R.string.hovq_export),
            ui(when {
                restoring && device -> R.string.hovq_device_restore_password
                restoring -> R.string.hovq_vps_restore_password
                else -> R.string.ui_enter_a_password_of_at_least
            }),
            minimum = if (restoring) 1 else 12,
            maximum = 256,
            close = { backupDialog = ""; restoreFile = "" },
        ) { password ->
            backupDialog = ""
            val file = restoreFile
            restoreFile = ""
            when {
                restoring && device -> model.restoreDevice(file, password)
                restoring -> model.restore(file, password)
                device -> model.exportDevice(password)
                else -> model.export(password)
            }
        }
    }

    val showTabs = panel.ready && !canGoBack && (tab != FEED || navVisible)
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    fun selectTab(chosen: Int) {
        if (chosen != tab || configPage.isNotEmpty() || openedIssue != null) {
            previousTab = tab
            tab = chosen
            route = ""
            archiveIssue = ""
            if (chosen == FEED) model.showFeed()
        }
    }
    val promptsAllowed = confirm == null && backupDialog.isEmpty() && panel.hostConfirmation == null &&
        overlay == ReadingOverlay.None && !requestingAlerts
    val installedAgent = panel.status.optString("agent")
    val updateKey = "${currentServer?.id}/$installedAgent"
    var requestUpdatePassword by remember(updateKey) { mutableStateOf(false) }
    val showPermissionOffer = promptsAllowed && notificationOffer && !notificationAllowed
    val showUpdateOffer = promptsAllowed && !showPermissionOffer && !canGoBack && panel.ready &&
        !panel.busy && !panel.updatingAgent && !enableDigestAfterPermission && currentServer != null &&
        panel.jobs.none { it.optString("state") in setOf("running", "queued") } &&
        agentUpdateAvailable(installedAgent) && updateKey !in dismissedAgentUpdates
    val promptOpen = showPermissionOffer || showUpdateOffer || requestUpdatePassword
    val swipeTabs = panel.ready && !canGoBack && overlay == ReadingOverlay.None && !promptOpen &&
        (tab != FEED || !feedFilters.searchOpen && !feedFilters.optionsOpen)
    val releaseDialogs: @Composable () -> Unit = {
        if (showPermissionOffer) {
            HorizonAlertDialog(onDismissRequest = { notificationOffer = false },
                title = { Text(ui(R.string.hovq_digest_notifications)) },
                text = { Text(ui(R.string.hovq_notification_offer)) },
                confirmButton = {
                    Button(onClick = {
                        notificationOffer = false
                        enableDigestAfterPermission = true
                        requestNotifications()
                    }) { Text(ui(R.string.hovq_allow_notifications)) }
                }, dismissButton = {
                    TextButton(onClick = { notificationOffer = false }) { Text(ui(R.string.hovq_later)) }
                })
        } else if (showUpdateOffer) {
            fun dismissAgent() { dismissedAgentUpdates = dismissedAgentUpdates + updateKey }
            HorizonAlertDialog(onDismissRequest = ::dismissAgent,
                title = { Text(ui(R.string.ui_update_vps_agent)) },
                text = {
                    Text(ui(R.string.hovq_agent_update_offer, installedAgent, BuildConfig.AGENT_VERSION))
                }, confirmButton = {
                    Button(onClick = {
                        dismissAgent()
                        val privileges = panel.probe["privileges"]
                        if (currentServer.user == "root" || privileges in listOf("root", "sudo"))
                            model.updateAgent("")
                        else requestUpdatePassword = true
                    }) {
                        Text(ui(R.string.hovq_update_now))
                    }
                }, dismissButton = {
                    TextButton(onClick = ::dismissAgent) { Text(ui(R.string.hovq_later)) }
                })
        } else if (requestUpdatePassword) {
            PasswordDialog(ui(R.string.ui_update_vps_agent), ui(R.string.hovq_update_sudo),
                close = { requestUpdatePassword = false }) { password ->
                requestUpdatePassword = false
                model.updateAgent(password)
            }
        }
    }
    val destination = when {
        manageServers -> Screen("servers")
        editServer -> Screen("ssh")
        configPage == "archive" && openedIssue != null -> Screen("archived", archiveIssue)
        configPage.isNotEmpty() -> Screen(configPage, route)
        showingSetupSettings -> Screen("settings")
        !panel.ready -> Screen("setup")
        tab == FAVORITES -> Screen("favorites")
        tab == SETTINGS -> Screen("settings")
        else -> Screen("feed")
    }
    GlassLayout(tabs, tab, showTabs, unread = unread, overlay = overlay, statusGlassInContent = destination.reading,
        select = ::selectTab, modal = releaseDialogs) {
        val currentDestination by rememberUpdatedState(destination)
        AnimatedContent(destination,
            modifier = Modifier.align(Alignment.TopCenter)
                .graphicsLayer { scaleX = 1f - backProgress * .035f; scaleY = scaleX }
                .widthIn(max = 780.dp).fillMaxSize().swipeBetweenTabs(swipeTabs, tab, tabs.size, ::selectTab),
            transitionSpec = {
                val from = initialState.tabIndex
                val to = targetState.tabIndex
                if (from != null && to != null && from != to) {
                    val side = if (to > from) direction else -direction
                    (fadeIn(tween(200)) + slideInHorizontally(tween(230)) { side * it / 4 }) togetherWith
                        (fadeOut(tween(160)) + slideOutHorizontally(tween(210)) { -side * it / 4 })
                } else {
                    (fadeIn(tween(220, delayMillis = 40)) + slideInVertically(tween(260)) { it / 45 }) togetherWith
                        (fadeOut(tween(130)) + slideOutVertically(tween(160)) { -it / 65 })
                }
            },
            label = "screen",
        ) { screen ->
            val archived = screen.page == "archived"
            val displayedIssue = if (archived) issues.archive.find { it.optString("id") == screen.issueId } else issues.feed
            val filters = if (archived) archiveFilters else feedFilters
            val screenBack = screen.page !in setOf("feed", "favorites", "setup", "settings") ||
                (screen.page == "settings" && showingSetupSettings)
            val heading = when (screen.page) {
                "servers" -> ui(R.string.ui_servers)
                "theme" -> ui(R.string.theme)
                "setup", "ssh" -> ui(R.string.app_name)
                "settings" -> ui(R.string.settings)
                "journal" -> ui(R.string.journal)
                "news_settings" -> ui(R.string.mr_news)
                "server_settings" -> ui(R.string.mr_server)
                "interface" -> ui(R.string.mr_interface)
                "images" -> ui(R.string.mr_images)
                "sources" -> ui(R.string.sources)
                "control" -> ui(R.string.control)
                "schedule" -> ui(R.string.ui_schedule)
                "delivery" -> ui(R.string.settings_server_delivery)
                "alerts" -> ui(R.string.settings_notifications)
                "connection" -> ui(R.string.ui_connection)
                "maintenance" -> ui(R.string.ui_maintenance)
                "transfer" -> ui(R.string.settings_transfer)
                "icon" -> ui(R.string.hovq_icon)
                "secrets" -> ui(R.string.ui_secrets)
                "advanced" -> ui(R.string.ui_advanced_settings)
                "profiles" -> ui(R.string.ui_processing_profiles)
                "digest" -> ui(R.string.settings_digest_rules)
                "digest_settings" -> ui(R.string.ui_digest)
                "reading" -> ui(R.string.editorial_feed_behavior)
                "local" -> ui(R.string.editorial_local_data)
                "ai" -> ui(R.string.settings_ai_model)
                "ai_settings" -> ui(R.string.editorial_ai_processing)
                "archived" -> ui(R.string.ui_digest)
                "archive" -> ui(R.string.archive_digests)
                "favorites" -> ui(R.string.favorites_title)
                "feed" -> ui(R.string.feed)
                else -> label(screen.page)
            }
            val action: (@Composable () -> Unit)? = if (screen.page == "setup") ({
                GlassIconAction(Icons.Default.Settings, ui(R.string.settings)) { openSettings("") }
            }) else null
            screenState.SaveableStateProvider("${currentServer?.id.orEmpty()}/${screen.page}/${screen.issueId}") {
                when {
                    screen.reading -> NewsScreen(panel, model, displayedIssue, filters, archived, promptOpen,
                        { openSettings("ai") }, { openSettings("sources") }, ::goBack,
                        { if (screen == currentDestination) navVisible = it },
                        { if (screen == currentDestination) overlay = it })
                    screen.page == "favorites" -> FavoritesScreen(model, panel.servers, {
                        Text(heading, Modifier.padding(top = 6.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.headlineLarge)
                    }) {
                        if (screen == currentDestination) overlay = it
                    }
                    else -> SettingsPage(heading, if (screenBack) ::goBack else null, action) { top ->
                        when (screen.page) {
                            "archive" -> ArchiveScreen(issues.archive, panel, model, top) { archiveIssue = it.getString("id") }
                            "journal" -> Journal(panel, { description, run -> confirm = description to run }, model,
                                { model.prepareExportDocument(it); output.launch("horizon-journal.json") }, top)
                            else -> Column(Modifier.fillMaxSize().editorialSideBottomInsets().verticalScroll(rememberScrollState())
                                .padding(start = 16.dp, end = 16.dp, top = top, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Reveal(panel.busy) { DottedProgress(Modifier.fillMaxWidth()) }
                                when (screen.page) {
                                    "servers" -> {
                                        panel.servers.forEach { server -> key(server.id) {
                                            val endpoint = "${server.user} @ ${server.host} · ${server.port}"
                                            val detail = if (server.id == currentServer?.id) "${ui(R.string.ui_selected)} · $endpoint" else endpoint
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.weight(1f)) {
                                                    GlassSettingRow(server.name, detail, enabled = !panel.busy) {
                                                        model.select(server)
                                                        route = ""
                                                        archiveIssue = ""
                                                        tab = FEED
                                                        manageServers = false
                                                    }
                                                }
                                                GlassIconAction(Icons.Default.Delete, ui(R.string.server_remove_named, server.name),
                                                    enabled = !panel.busy) {
                                                    confirm = ui(R.string.server_remove_message, server.name) to { model.removeServer(server.id) }
                                                }
                                            }
                                        } }
                                        Button(onClick = { model.add(); manageServers = false; tab = FEED }, enabled = !panel.busy) {
                                            Text(ui(R.string.ui_add_server))
                                        }
                                    }
                                    "theme" -> ThemeSettings(preset) { choice ->
                                        if (preset != choice) { selectTheme(choice); localSave++ }
                                    }
                                    "icon" -> IconSettings(launcherIcon) { choice ->
                                        if (launcherIcon != choice) {
                                            runCatching { choice.apply(ctx) }.onSuccess {
                                                launcherIcon = choice
                                                localSave++
                                            }.onFailure { scope.launch { notices.showSnackbar(ui(R.string.hovq_icon_error)) } }
                                        }
                                    }
                                    "setup", "ssh" -> Setup(panel, model, forceEdit = screen.page == "ssh")
                                    "sources" -> Sources(panel, model) { if (configPage == screen.page) dirtyPage = it }
                                    "control" -> Control(panel, model, { description, action -> confirm = description to action }, ::openSettings)
                                    "connection" -> ConnectionSettings(panel, model, { if (configPage == screen.page) dirtyPage = it }) {
                                        editVerification = panel.sshVerification
                                        editServer = true
                                    }
                                    "alerts" -> NotificationSettings(panel, model, ::requestNotifications,
                                        { openSettings("delivery") })
                                    "maintenance" -> ServerMaintenance(panel, model, ::openSettings)
                                    "transfer" -> TransferSettings(panel,
                                        { device -> backupDialog = if (device) "device_export" else "vps_export" },
                                        { device ->
                                            fileAction = if (device) "device" else "vps"
                                            input.launch(arrayOf("*/*"))
                                        })
                                    "reading" -> ReadingPreferences(defaultOrder) {
                                        defaultOrder = it
                                        readingPrefs.edit().putString("default_order", it.name).apply()
                                        localSave++
                                    }
                                    "local" -> LocalReadingData(panel)
                                    "digest_settings" -> DigestSettings(::openSettings)
                                    "ai_settings" -> AiSettings(panel, model, ::openSettings)
                                    "settings", "news_settings", "server_settings", "interface" ->
                                        SettingsHome(panel, preset, ::openSettings, screen.page) { manageServers = true }
                                    "images" -> ImageSettings(panel, model)
                                    else -> CompositionLocalProvider(LocalEditorBack provides editorBack) {
                                        SettingsForm(panel, model, screen.page) { if (configPage == screen.page) dirtyPage = it }
                                    }
                                }
                                Spacer(Modifier.height(if (showTabs) LocalNavigationClearance.current else 10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun androidx.lifecycle.LifecycleOwner.repeatAt(
    minimum: androidx.lifecycle.Lifecycle.State, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
) = this.lifecycle.repeatOnLifecycle(minimum, block)


@Composable
internal fun PasswordDialog(title: String, detail: String, minimum: Int = 0, maximum: Int = Int.MAX_VALUE, close: () -> Unit, proceed: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    HorizonAlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(detail)
            PasswordField(ui(R.string.ui_password), password) { password = it }
        }
    }, confirmButton = { Button(enabled = password.length in minimum..maximum, onClick = { proceed(password) }) { Text(ui(R.string.ui_continue)) } },
        dismissButton = { TextButton(onClick = close) { Text(ui(R.string.ui_cancel)) } })
}

@Composable
internal fun PasswordField(title: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(title) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, colors = horizonFieldColors(), visualTransformation = PasswordVisualTransformation())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(panel: Panel, model: ManagerModel, forceEdit: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val server = panel.server
    val probe = panel.probe
    var name by remember { mutableStateOf(server?.name.orEmpty()) }
    var host by remember { mutableStateOf(server?.host.orEmpty()) }
    var port by remember { mutableStateOf(server?.port?.toString() ?: "22") }
    var user by remember { mutableStateOf(server?.user ?: "root") }
    var keyMode by remember { mutableStateOf(server?.keyMode ?: false) }
    var credential by remember { mutableStateOf(server?.credential.orEmpty()) }
    var passphrase by remember { mutableStateOf(server?.passphrase.orEmpty()) }
    var approveInstall by remember { mutableStateOf(false) }
    var approveAttach by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            credential = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBounded(65536).decodeToString() }.orEmpty() }
                    .getOrDefault("")
            }
            keyError = credential.isEmpty()
        }
    }
    panel.hostConfirmation?.let { key ->
        HorizonAlertDialog(onDismissRequest = { model.confirmHost(false) },
            title = { Text(if (key.previous.isBlank()) ui(R.string.ui_verify_the_vps_key) else ui(R.string.ui_the_vps_key_has_changed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui(R.string.ui_compare_the_fingerprint_with_the_one))
                    if (key.previous.isNotBlank()) Text(ui(R.string.ui_previous, key.previous))
                    Text(ui(R.string.ui_received, key.fingerprint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { model.confirmHost(true) }) { Text(ui(R.string.ui_fingerprint_matches)) } },
            dismissButton = { TextButton(onClick = { model.confirmHost(false) }) { Text(ui(R.string.ui_cancel)) } })
    }
    if (approveAttach) {
        PasswordDialog(ui(R.string.ui_connect_an_existing_agent),
            ui(R.string.ui_manager_will_retrieve_the_agent_key),
            close = { approveAttach = false }) { password -> approveAttach = false; model.attachExisting(password) }
    }
    if (approveInstall) {
        val needsPassword = probe["privileges"] !in listOf("root", "sudo")
        val text = ui(R.string.ui_manager_will_install_missing_system_packages)
        if (needsPassword) {
            PasswordDialog(ui(R.string.ui_install_horizon), ui(R.string.ui_enter_the_sudo_password_leave_the, text),
                close = { approveInstall = false }) { password -> approveInstall = false; model.install(password) }
        } else {
            HorizonAlertDialog(onDismissRequest = { approveInstall = false }, title = { Text(ui(R.string.ui_install_horizon)) },
                text = { Text(text) }, confirmButton = {
                    Button(onClick = { approveInstall = false; model.install("") }) { Text(ui(R.string.ui_install)) }
                }, dismissButton = { TextButton(onClick = { approveInstall = false }) { Text(ui(R.string.ui_cancel)) } })
        }
    }
    if (server == null || forceEdit || probe.isEmpty()) {
        Tile {
            Text(ui(R.string.ui_connect_to_your_vps), style = MaterialTheme.typography.titleLarge)
            ValueField(ui(R.string.ui_server_name), name, { name = it })
            ValueField(ui(R.string.ui_ip_address_or_domain), host, { host = it })
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.weight(1f)) { ValueField(ui(R.string.ui_ssh_port), port, { port = it }, numeric = true) }
                Box(Modifier.weight(2f)) { ValueField(ui(R.string.ui_ssh_user), user, { user = it }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!keyMode, { keyMode = false; credential = ""; keyError = false }, { Text(ui(R.string.ui_password)) })
                FilterChip(keyMode, { keyMode = true; credential = ""; keyError = false }, { Text(ui(R.string.ui_ssh_key)) })
            }
            Reveal(keyMode) {
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) {
                    Text(if (credential.isNotEmpty()) ui(R.string.ui_replace_selected_key) else ui(R.string.ui_select_private_key))
                }
                if (keyError) Text(ui(R.string.ui_could_not_read_the_key_file), color = MaterialTheme.colorScheme.error)
                PasswordField(ui(R.string.ui_key_passphrase_if_set), passphrase) { passphrase = it }
            }
            Reveal(!keyMode) { PasswordField(ui(R.string.ui_ssh_password), credential) { credential = it } }
            val setupProblem = panel.problem.takeIf { it == "SERVER_FIELDS" || isConnectionFailure(it) }
            val phase = panel.connectionPhase
            if (phase != ConnectionPhase.Idle) Text(ui(phase.label()))
            if (setupProblem != null) Text(errorText(setupProblem), color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.connect(name, host, port, user, keyMode, credential, passphrase, server) },
                    enabled = !panel.busy && credential.isNotBlank()) {
                    Text(ui(if (setupProblem != null) R.string.ui_retry else R.string.ui_connect))
                }
                if (panel.connectionCancellable) TextButton(onClick = model::cancelConnection) {
                    Text(ui(R.string.ui_cancel))
                }
            }
        }
    } else {
        Tile {
            Text(ui(R.string.ui_vps_checks), style = MaterialTheme.typography.titleLarge)
            Text("${probe["os"].orEmpty()} ${probe["version"].orEmpty()} · ${probe["arch"].orEmpty()}")
            Text(ui(R.string.ui_cpu_ram_free_disk, probe["cpu"].orEmpty(), kb(probe["memory_kb"]), kb(probe["disk_kb"])))
            Text(ui(R.string.ui_privileges_docker_compose, probe["privileges"].orEmpty(), if (probe["docker"] == "yes") ui(R.string.ui_available) else ui(R.string.ui_missing), if (probe["compose"] == "yes") ui(R.string.ui_available) else ui(R.string.ui_missing)))
            Text("DNS GitHub ${if (probe["dns"] == "yes") ui(R.string.ui_available_990c71) else ui(R.string.ui_no_response)} · systemd ${if (probe["systemd"] == "yes") ui(R.string.ui_running) else ui(R.string.ui_not_found)}")
            probe["existing"]?.let { Text(ui(R.string.hovq_existing_horizon, it)) }
            if (probe["docker_horizon"] == "yes") Text(ui(R.string.ui_a_container_with_horizon_in_its))
            if (panel.connectionPhase != ConnectionPhase.Idle) Text(ui(panel.connectionPhase.label()))
            val failure = panel.connectionProblem.ifBlank { probe["agent_problem"].orEmpty() }
            if (failure.isNotBlank()) Text(errorText(failure), color = MaterialTheme.colorScheme.error)
        }
        val stages = linkedMapOf("preflight" to ui(R.string.ui_check_server), "packages" to ui(R.string.ui_prepare_packages),
            "files" to ui(R.string.ui_persistent_storage), "runtime" to ui(R.string.ui_download_horizon_and_dependencies),
            "service" to ui(R.string.ui_install_agent_service), "health" to ui(R.string.ui_check_api))
        val install = probe["install"].orEmpty()
        val step = probe["step"].orEmpty()
        if (install.isNotEmpty()) Tile {
            Text(ui(R.string.ui_vps_installation), style = MaterialTheme.typography.titleLarge)
            stages.entries.forEachIndexed { index, (id, title) ->
                val current = stages.keys.indexOf(step)
                val event = when {
                    index < current || install == "success" -> ui(R.string.ui_done)
                    index == current && install == "failed" -> ui(R.string.ui_error, probe["code"])
                    index == current -> ui(R.string.ui_running_eff79c)
                    else -> ui(R.string.ui_waiting)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, Modifier.weight(1f)); Text(event) }
                if (id == step && install == "running") DottedProgress(Modifier.fillMaxWidth())
            }
            probe["operation"]?.let { Text(ui(R.string.ui_operation, it), style = MaterialTheme.typography.labelSmall) }
        }
        val supported = (probe["os"] == "ubuntu" && probe["version"] == "24.04" || probe["os"] == "debian" && probe["version"] == "12") &&
            probe["arch"] in listOf("x86_64", "aarch64") && probe["systemd"] == "yes"
        val resources = (probe["memory_kb"]?.toLongOrNull() ?: 0) >= 442368 && (probe["disk_kb"]?.toLongOrNull() ?: 0) >= 1572864
        if (!supported) Text(ui(R.string.ui_installation_requires_ubuntu_24_04_or))
        if (!resources) Text(ui(R.string.ui_at_least_432_mib_of_total))
        if (install == "failed") Text(installError(probe["code"], step), color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (probe["agent_problem"] == "AUTH_REQUIRED") {
                OutlinedButton(onClick = { approveAttach = true }, enabled = !panel.busy) { Text(ui(R.string.ui_connect_an_existing_agent)) }
            }
            Button(onClick = { approveInstall = true }, enabled = !panel.busy && supported && resources &&
                install != "running" && probe["agent"] != "active") {
                Text(if (install == "failed") ui(R.string.ui_retry_installation) else ui(R.string.ui_install_horizon))
            }
            OutlinedButton(onClick = { model.refreshInstallation(false) }, enabled = !panel.busy) { Text(ui(R.string.ui_check_again)) }
            if (panel.connectionCancellable) TextButton(onClick = model::cancelConnection) { Text(ui(R.string.ui_cancel)) }
        }
    }
}

private fun kb(value: String?) = value?.toLongOrNull()?.let { displayBytes(it * 1024) } ?: "—"
private fun displayBytes(value: Long) = if (value < 1024L * 1024 * 1024) ui(R.string.ui_mib, value / (1024 * 1024))
    else ui(R.string.ui_1f_gib).format(Locale.ROOT, value.toDouble() / (1024 * 1024 * 1024))

private fun installError(code: String?, step: String) = when (code) {
    "21", "22" -> ui(R.string.ui_check_the_operating_system_and_systemd)
    "23", "24" -> ui(R.string.ui_free_disk_space_or_allocate_more)
    "26" -> ui(R.string.ui_this_agent_is_already_linked_to)
    "27" -> ui(R.string.ui_local_port_18777_is_in_use)
    "28" -> ui(R.string.ui_the_service_started_but_the_api)
    "29" -> ui(R.string.ui_installation_stopped_retry_to_resume_preparation)
    "124" -> ui(R.string.ui_the_installation_step_timed_out)
    else -> when (step) {
        "packages" -> ui(R.string.ui_package_installation_failed_check_vps_access)
        "runtime" -> ui(R.string.ui_horizon_setup_failed_check_vps_access)
        "service" -> ui(R.string.ui_systemd_could_not_start_the_manager)
        else -> ui(R.string.ui_installation_stopped_check_the_report_and)
    }
}

internal fun errorText(code: String) = when (code.substringBefore(" · ")) {
    "MEDIA_READY" -> ui(R.string.mr_media_ready)
    "MEDIA_UNAVAILABLE" -> ui(R.string.mr_media_failed)
    "MEDIA_SEARCH_CHANGED" -> ui(R.string.mr_media_changed)
    "SSH_HOST_CHANGED" -> ui(R.string.ui_the_ssh_fingerprint_changed_open_ssh)
    "SSH_KEY" -> ui(R.string.ui_could_not_read_the_private_key)
    "SSH_AUTH" -> ui(R.string.ui_the_vps_rejected_the_password_or)
    "SSH_DNS" -> ui(R.string.ui_could_not_resolve_the_vps_host)
    "SSH_TIMEOUT" -> ui(R.string.ui_the_vps_did_not_respond_to)
    "SSH_REFUSED" -> ui(R.string.ui_the_vps_refused_the_connection_on)
    "AGENT_UPDATE_FAILED" -> ui(R.string.ui_could_not_update_the_agent_see)
    "LOCALIZATION_INVALID" -> ui(R.string.ui_the_ai_returned_a_translation_in)
    "LOCALIZATION_RECOVERED" -> ui(R.string.journal_translation_recovered)
    "LOCALIZATION_RESPONSE_INVALID" -> ui(R.string.journal_translation_invalid)
    "LOCALIZATION_PROVIDER_TIMEOUT" -> ui(R.string.journal_translation_timeout)
    "LOCALIZATION_PROVIDER_ERROR" -> ui(R.string.journal_translation_error)
    "LOCALIZATION_PARTIAL" -> ui(R.string.journal_translation_partial)
    "CLASSIFIER_FALLBACK" -> ui(R.string.ui_the_classifier_used_the_fallback_profile)
    "SSH_CONNECT" -> ui(R.string.ui_could_not_connect_over_ssh_check)
    "SSH_HANDSHAKE" -> ui(R.string.connection_ssh_handshake)
    "SSH_NEGOTIATION" -> ui(R.string.connection_ssh_negotiation)
    "SSH_SESSION_LOST" -> ui(R.string.connection_ssh_lost)
    "SSH_COMMAND_TIMEOUT" -> ui(R.string.connection_ssh_command_timeout)
    "NETWORK_UNAVAILABLE" -> ui(R.string.connection_network_unavailable)
    "AGENT_TIMEOUT", "HTTPS_TIMEOUT" -> ui(R.string.connection_agent_timeout)
    "AGENT_API_ERROR" -> ui(R.string.connection_agent_api_error)
    "AGENT_INACTIVE" -> ui(R.string.connection_agent_inactive)
    "AGENT_TUNNEL" -> ui(R.string.connection_agent_tunnel)
    "AGENT_PROTOCOL" -> ui(R.string.connection_agent_protocol)
    "HTTPS_DNS" -> ui(R.string.connection_https_dns)
    "SSH_COMMAND", "SUDO_REQUIRED" -> ui(R.string.ui_the_vps_could_not_run_the)
    "AGENT_UNREACHABLE" -> ui(R.string.ui_the_agent_did_not_respond_over)
    "AUTH_REQUIRED", "API_401", "API_403" -> ui(R.string.ui_the_agent_rejected_the_access_key)
    "UPSTREAM_CHECK_UNAVAILABLE" -> ui(R.string.ui_could_not_verify_the_version_on)
    "HTTPS_UNREACHABLE" -> ui(R.string.agent_https_unreachable)
    "TLS_INVALID" -> ui(R.string.ui_the_https_certificate_could_not_be)
    "HTTPS_URL" -> ui(R.string.ui_enter_an_address_like_https_example)
    "ACTIVE_JOB" -> ui(R.string.ui_wait_for_the_active_job_to)
    "CONFIG_CHANGED" -> ui(R.string.ui_the_configuration_changed_on_the_vps)
    "CONFIG_FIELDS" -> ui(R.string.ui_horizon_rejected_the_values_in_the)
    "CONFIG_OR_PROFILE_INVALID" -> ui(R.string.journal_config_invalid)
    "PROFILE_DUPLICATE_ID" -> ui(R.string.journal_profile_duplicate)
    "PROFILE_DEFAULT_UNKNOWN" -> ui(R.string.journal_profile_default)
    "PROFILE_SOURCE_INVALID" -> ui(R.string.journal_profile_source)
    "PROFILE_DIGEST_INVALID" -> ui(R.string.journal_profile_digest)
    "PROFILE_ORDER_INVALID" -> ui(R.string.journal_profile_order)
    "SCHEDULE_SAVED" -> ui(R.string.journal_schedule_saved)
    "NO_PREVIOUS_CONFIG" -> ui(R.string.ui_there_is_no_previous_configuration_on)
    "BACKUP_VERSION" -> ui(R.string.journal_backup_version)
    "AGENT_UPDATE_REQUIRED" -> ui(R.string.article_context_update)
    "BACKUP_INVALID" -> ui(R.string.ui_check_the_password_and_backup_file)
    "SERVER_FIELDS" -> ui(R.string.ui_check_the_address_ssh_port_username)
    "OPENBB_DEPENDENCY" -> ui(R.string.ui_openbb_requires_an_additional_set_of)
    "CHATGPT_AUTH_UNAVAILABLE" -> ui(R.string.ui_in_app_chatgpt_subscription_sign_in)
    "TWITTER_MODE_UNAVAILABLE" -> ui(R.string.ui_the_apify_mode_is_available_playwright)
    "WECHAT_AUTH_UNAVAILABLE" -> ui(R.string.ui_signing_in_to_wechat_from_the)
    "SECRET_REFERENCE" -> ui(R.string.ui_add_the_secrets_referenced_by_the)
    "LANGUAGE_UNVERIFIED" -> ui(R.string.ui_english_simplified_chinese_and_russian_are)
    "NO_ENABLED_SOURCES", "SOURCE_ALL_FAILED" -> ui(R.string.ui_sources_are_unavailable_or_disabled_check)
    "AI_SETUP_REQUIRED", "AI_OR_DELIVERY_SETUP_REQUIRED", "RUN_SETUP_REQUIRED" -> ui(R.string.ui_check_the_ai_model_and_missing)
    "AUTH", "HTTP_401", "HTTP_403" -> ui(R.string.ui_a_source_or_ai_provider_rejected)
    "RATE_LIMIT", "HTTP_429" -> ui(R.string.ui_the_provider_rate_limit_or_quota)
    "TIMEOUT", "JOB_TIMEOUT" -> ui(R.string.ui_the_server_request_or_job_timed)
    "NETWORK" -> ui(R.string.ui_the_source_did_not_respond_over)
    "HTTP_NOT_FOUND", "HTTP_404" -> ui(R.string.ui_the_source_api_address_or_model)
    "INVALID_FORMAT" -> ui(R.string.ui_the_response_was_in_an_unexpected)
    else -> ui(R.string.ui_operation_details)
}

internal fun localTime(time: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", appLocale()).format(Instant.parse(time).atZone(ZoneId.systemDefault()))
}.getOrDefault(time)

internal fun localEpoch(seconds: Double): String = if (seconds.isFinite())
    localTime(Instant.ofEpochSecond(seconds.toLong()).toString()) else "—"
