package dev.horizonmanager

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val graphite = Color(0xFF101417)
private val colors = darkColorScheme(
    primary = Color(0xFF9CCAFF), onPrimary = Color(0xFF0C2D4C),
    primaryContainer = Color(0xFF223A50), onPrimaryContainer = Color(0xFFC6E1FF),
    background = graphite, surface = graphite, surfaceContainer = Color(0xFF1A2026),
    surfaceContainerHigh = Color(0xFF212932), surfaceVariant = Color(0xFF262F38),
    onSurface = Color(0xFFE4E9F0), onSurfaceVariant = Color(0xFFAFBBC8),
    outlineVariant = Color(0xFF34414C), error = Color(0xFFFFB4A9),
)

class MainActivity : ComponentActivity() {
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
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = colors) { Manager(viewModel(), notificationServer) { notificationServer = "" } }
        }
    }
}

private val tabs = listOf(
    R.string.news to Icons.Default.Home, R.string.sources to Icons.AutoMirrored.Filled.List,
    R.string.control to Icons.Default.Menu, R.string.settings to Icons.Default.Settings,
)

@Composable
private fun Manager(model: ManagerModel, notificationServer: String, consumed: () -> Unit) {
    val panel by model.state.collectAsStateWithLifecycle()
    val currentServer = panel.server
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember(currentServer?.id) { mutableIntStateOf(0) }
    LaunchedEffect(notificationServer) {
        if (notificationServer.isNotEmpty()) {
            tab = 0
            model.openNotification(notificationServer)
            consumed()
        }
    }
    var manageServers by remember { mutableStateOf(false) }
    var editServer by remember { mutableStateOf(false) }
    var configPage by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var exportDoc by remember { mutableStateOf("") }
    var fileAction by remember { mutableStateOf("") }
    var restoreFile by remember { mutableStateOf("") }
    var fileMessage by remember { mutableStateOf("") }
    var backupDialog by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val output = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val content = exportDoc
        exportDoc = ""
        if (uri != null) scope.launch {
            fileMessage = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: error("output")
                    ui(R.string.ui_file_saved)
                }.getOrDefault(ui(R.string.ui_could_not_write_the_file))
            }
        }
    }
    val input = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val action = fileAction
            try {
                val content = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBounded(9_000_000).decodeToString() }
                        ?: error("input")
                }
                when (action) {
                    "backup" -> { restoreFile = content.trim(); backupDialog = "restore" }
                    "profiles" -> model.saveConfig(panel.config.getJSONObject("config"), JSONArray(content))
                    "config" -> model.importConfig(JSONObject(content))
                }
            } catch (_: Exception) {
                fileMessage = ui(R.string.ui_the_file_is_damaged_too_large)
            }
        }
    }
    LaunchedEffect(panel.file) {
        panel.file?.let {
            exportDoc = it
            output.launch("horizon-backup.hmb")
            model.clearFile()
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val running = panel.jobs.firstOrNull()?.optString("state") in setOf("running", "queued")
    val checkingInstall = panel.probe["install"] == "running" || panel.updatingAgent
    LaunchedEffect(currentServer?.id, panel.ready, running, checkingInstall, lifecycle, locale) {
        androidx.lifecycle.Lifecycle.State.RESUMED.let { minimum ->
            lifecycle.repeatAt(minimum) {
                while (true) {
                    delay(when {
                        checkingInstall -> 5_000
                        running -> 12_000
                        else -> 60_000
                    })
                    if (panel.ready) model.refresh(quiet = true)
                    else if (currentServer != null) model.refreshInstallation()
                }
            }
        }
    }
    BackHandler(manageServers || editServer || configPage.isNotEmpty()) {
        when { configPage.isNotEmpty() -> configPage = ""; editServer -> editServer = false; else -> manageServers = false }
    }

    confirm?.let { (description, action) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(ui(R.string.ui_confirm_action)) },
            text = { Text(description) }, confirmButton = {
                Button(onClick = { confirm = null; action() }) { Text(ui(R.string.ui_confirm)) }
            }, dismissButton = { TextButton(onClick = { confirm = null }) { Text(ui(R.string.ui_cancel)) } })
    }
    if (backupDialog.isNotEmpty()) {
        val action = backupDialog
        PasswordDialog(if (action == "restore") ui(R.string.ui_restore_backup) else ui(R.string.ui_export_backup),
            if (action == "restore") ui(R.string.ui_this_will_replace_settings_secrets_and)
            else ui(R.string.ui_enter_a_password_of_at_least),
            minimum = if (action == "restore") 1 else 12, close = { backupDialog = ""; restoreFile = "" }) { password ->
            backupDialog = ""
            if (action == "restore") { model.restore(restoreFile, password); restoreFile = "" }
            else model.export(password)
        }
    }

    Scaffold(
        containerColor = graphite,
        bottomBar = {
            if (panel.ready && !manageServers && !editServer && configPage.isEmpty()) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEachIndexed { index, (res, icon) ->
                        val title = stringResource(res)
                        NavigationBarItem(selected = index == tab, onClick = { tab = index },
                            icon = { Icon(icon, title) },
                            label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val section = if (panel.ready) tab else -1
                key(section, configPage, manageServers, editServer, currentServer?.id, locale) {
                    Column(Modifier.widthIn(max = 780.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Header(if (configPage == "journal") stringResource(R.string.journal) else if (manageServers) ui(R.string.ui_servers) else if (panel.ready && !editServer) stringResource(tabs[tab].first)
                            else "Horizon Manager", currentServer?.name.orEmpty(), {
                            if (configPage.isNotEmpty()) configPage = "" else manageServers = !manageServers
                        }, if (configPage.isNotEmpty() || manageServers) ui(R.string.ui_back) else ui(R.string.ui_servers))
                        if (panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (panel.problem.isNotEmpty()) Message(errorText(panel.problem), panel.problem, true) { model.dismissMessage() }
                        if (panel.notice.isNotEmpty()) Message(panel.notice, "") { model.dismissMessage() }
                        if (fileMessage.isNotEmpty()) Message(fileMessage, "") { fileMessage = "" }
                        when {
                            manageServers -> {
                                panel.servers.forEach { server ->
                                    Tile(Modifier.clickable { model.select(server); manageServers = false; editServer = false }) {
                                        Text(server.name, style = MaterialTheme.typography.titleMedium)
                                        Text("${server.user} @ ${server.host} · ${server.port}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Button(onClick = { model.add(); manageServers = false; editServer = true }) { Text(ui(R.string.ui_add_server)) }
                            }
                            !panel.ready || editServer -> Setup(panel, model, editServer) { editServer = false }
                            configPage == "journal" -> Journal(panel, { description, action -> confirm = description to action }, model) {
                                exportDoc = it; output.launch("horizon-journal.json")
                            }
                            configPage.isNotEmpty() -> SettingsForm(panel, model, configPage, { configPage = "" })
                            tab == 0 -> News(panel, model, { configPage = "ai" }, { tab = 1 })
                            tab == 1 -> Sources(panel, model)
                            tab == 2 -> Control(panel, model, { description, action -> confirm = description to action },
                                { backupDialog = "export" },
                                { fileAction = "backup"; input.launch(arrayOf("*/*")) }, { configPage = it })
                            else -> Settings(panel, model, { configPage = it }, { editServer = true },
                                { toImport -> fileAction = toImport; input.launch(arrayOf("*/*")) },
                                { filename, content -> exportDoc = content; output.launch(filename) },
                                { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                { description, action -> confirm = description to action })
                        }
                        Spacer(Modifier.height(16.dp))
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
private fun Header(heading: String, server: String, onClick: () -> Unit, title: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (server.isNotEmpty()) Text(server, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(heading, style = MaterialTheme.typography.headlineMedium)
        }
        TextButton(onClick = onClick) { Text(title) }
    }
}

@Composable
internal fun Tile(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().then(modifier),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun Message(text: String, code: String, error: Boolean = false, dismiss: () -> Unit) {
    Tile {
        Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (code.isNotEmpty()) Text(code, style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = dismiss) { Text(ui(R.string.ui_close)) }
    }
}

@Composable
internal fun PasswordDialog(title: String, detail: String, minimum: Int = 0, close: () -> Unit, proceed: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(detail)
            PasswordField(ui(R.string.ui_password), password) { password = it }
        }
    }, confirmButton = { Button(enabled = password.length >= minimum, onClick = { proceed(password) }) { Text(ui(R.string.ui_continue)) } },
        dismissButton = { TextButton(onClick = close) { Text(ui(R.string.ui_cancel)) } })
}

@Composable
internal fun PasswordField(title: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(title) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, visualTransformation = PasswordVisualTransformation())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(panel: Panel, model: ManagerModel, forceEdit: Boolean, done: () -> Unit) {
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
    LaunchedEffect(panel.ready, panel.notice) {
        if (panel.ready && (!forceEdit || panel.notice == ui(R.string.ui_ssh_settings_verified))) done()
    }
    panel.hostConfirmation?.let { key ->
        AlertDialog(onDismissRequest = { model.confirmHost(false) },
            title = { Text(if (key.previous.isBlank()) ui(R.string.ui_verify_the_vps_key) else ui(R.string.ui_the_vps_key_has_changed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui(R.string.ui_compare_the_fingerprint_with_the_one))
                    if (key.previous.isNotBlank()) Text(ui(R.string.ui_previous, key.previous))
                    Text(ui(R.string.ui_received, key.fingerprint), color = MaterialTheme.colorScheme.primary)
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
            AlertDialog(onDismissRequest = { approveInstall = false }, title = { Text(ui(R.string.ui_install_horizon)) },
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
            if (keyMode) {
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) {
                    Text(if (credential.isNotEmpty()) ui(R.string.ui_replace_selected_key) else ui(R.string.ui_select_private_key))
                }
                if (keyError) Text(ui(R.string.ui_could_not_read_the_key_file), color = MaterialTheme.colorScheme.error)
                PasswordField(ui(R.string.ui_key_passphrase_if_set), passphrase) { passphrase = it }
            } else PasswordField(ui(R.string.ui_ssh_password), credential) { credential = it }
            Button(onClick = { model.connect(name, host, port, user, keyMode, credential, passphrase, server) },
                enabled = !panel.busy && credential.isNotBlank()) { Text(ui(R.string.ui_connect)) }
        }
    } else {
        Tile {
            Text(ui(R.string.ui_vps_checks), style = MaterialTheme.typography.titleLarge)
            Text("${probe["os"].orEmpty()} ${probe["version"].orEmpty()} · ${probe["arch"].orEmpty()}")
            Text(ui(R.string.ui_cpu_ram_free_disk, probe["cpu"].orEmpty(), kb(probe["memory_kb"]), kb(probe["disk_kb"])))
            Text(ui(R.string.ui_privileges_docker_compose, probe["privileges"].orEmpty(), if (probe["docker"] == "yes") ui(R.string.ui_available) else ui(R.string.ui_missing), if (probe["compose"] == "yes") ui(R.string.ui_available) else ui(R.string.ui_missing)))
            Text("DNS GitHub ${if (probe["dns"] == "yes") ui(R.string.ui_available_990c71) else ui(R.string.ui_no_response)} · systemd ${if (probe["systemd"] == "yes") ui(R.string.ui_running) else ui(R.string.ui_not_found)}")
            probe["existing"]?.let { Text(ui(R.string.ui_horizon_found_at_you_can_copy, it)) }
            if (probe["docker_horizon"] == "yes") Text(ui(R.string.ui_a_container_with_horizon_in_its))
            probe["agent_problem"]?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
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
                if (id == step && install == "running") LinearProgressIndicator(Modifier.fillMaxWidth())
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
    "SSH_HOST_CHANGED" -> ui(R.string.ui_the_ssh_fingerprint_changed_open_ssh)
    "SSH_KEY" -> ui(R.string.ui_could_not_read_the_private_key)
    "SSH_AUTH" -> ui(R.string.ui_the_vps_rejected_the_password_or)
    "SSH_DNS" -> ui(R.string.ui_could_not_resolve_the_vps_host)
    "SSH_TIMEOUT" -> ui(R.string.ui_the_vps_did_not_respond_to)
    "SSH_REFUSED" -> ui(R.string.ui_the_vps_refused_the_connection_on)
    "AGENT_UPDATE_FAILED" -> ui(R.string.ui_could_not_update_the_agent_see)
    "LOCALIZATION_INVALID" -> ui(R.string.ui_the_ai_returned_a_translation_in)
    "CLASSIFIER_FALLBACK" -> ui(R.string.ui_the_classifier_used_the_fallback_profile)
    "SSH_CONNECT" -> ui(R.string.ui_could_not_connect_over_ssh_check)
    "SSH_COMMAND", "SUDO_REQUIRED" -> ui(R.string.ui_the_vps_could_not_run_the)
    "AGENT_UNREACHABLE" -> ui(R.string.ui_the_agent_did_not_respond_over)
    "AUTH_REQUIRED" -> ui(R.string.ui_the_agent_rejected_the_access_key)
    "UPSTREAM_CHECK_UNAVAILABLE" -> ui(R.string.ui_could_not_verify_the_version_on)
    "TLS_INVALID" -> ui(R.string.ui_the_https_certificate_could_not_be)
    "HTTPS_URL" -> ui(R.string.ui_enter_an_address_like_https_example)
    "ACTIVE_JOB" -> ui(R.string.ui_wait_for_the_active_job_to)
    "CONFIG_CHANGED" -> ui(R.string.ui_the_configuration_changed_on_the_vps)
    "CONFIG_FIELDS" -> ui(R.string.ui_horizon_rejected_the_values_in_the)
    "NO_PREVIOUS_CONFIG" -> ui(R.string.ui_there_is_no_previous_configuration_on)
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
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Instant.parse(time).atZone(ZoneId.systemDefault()))
}.getOrDefault(time)

internal fun localEpoch(seconds: Double): String = if (seconds.isFinite())
    localTime(Instant.ofEpochSecond(seconds.toLong()).toString()) else "—"
