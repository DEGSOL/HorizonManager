package dev.horizonmanager

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ServerDraft(
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val user: String = "root",
    val keyMode: Boolean = false,
    val credential: String = "",
    val passphrase: String = "",
    val https: String = "",
) {
    constructor(server: Server) : this(server.name, server.host, server.port.toString(), server.user,
        server.keyMode, server.credential, server.passphrase, server.https)

    fun update(previous: Server): Server? {
        val address = host.trim().removeSurrounding("[", "]")
        val parsedPort = port.trim().toIntOrNull()
        if (!address.matches(Regex("[A-Za-z0-9:._%\\-]+")) || parsedPort == null || parsedPort !in 1..65535 ||
            !user.matches(Regex("[A-Za-z0-9._-]{1,64}")) || credential.isBlank() || credential.length > 65536) return null
        return previous.copy(name = name.trim().ifBlank { address }, host = address, port = parsedPort,
            user = user, keyMode = keyMode, credential = credential, passphrase = if (keyMode) passphrase else "",
            https = https.trim())
    }
}

internal fun Server.sameSshAddress(other: Server): Boolean =
    host.equals(other.host, ignoreCase = true) && port == other.port

internal fun Server.sameSshCredentials(other: Server): Boolean =
    user == other.user && keyMode == other.keyMode && credential == other.credential &&
        (!keyMode || passphrase == other.passphrase)

@Composable
internal fun ServerCredentialFields(draft: ServerDraft, enabled: Boolean, sectioned: Boolean = false,
                                    change: (ServerDraft) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyError by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(draft)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val key = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBounded(65536).decodeToString() } }
                    .getOrNull()
            }
            keyError = key.isNullOrEmpty()
            if (!keyError && current.keyMode) change(current.copy(credential = key.orEmpty()))
        }
    }
    @Composable
    fun Address() {
        ValueField(ui(R.string.ui_server_name), draft.name, { change(draft.copy(name = it)) }, enabled = enabled)
        ValueField(ui(R.string.ui_ip_address_or_domain), draft.host, { change(draft.copy(host = it)) }, enabled = enabled,
            help = R.string.vps_form_address_help)
        BoxWithConstraints {
            val port: @Composable () -> Unit = {
                ValueField(ui(R.string.ui_ssh_port), draft.port, { change(draft.copy(port = it)) }, numeric = true, enabled = enabled,
                    help = R.string.vps_form_port_help)
            }
            val user: @Composable () -> Unit = {
                ValueField(ui(R.string.ui_ssh_user), draft.user, { change(draft.copy(user = it)) }, enabled = enabled,
                    help = R.string.vps_form_user_help)
            }
            if (maxWidth < 280.dp || LocalConfiguration.current.fontScale > 1.3f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    port()
                    user()
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.weight(1f)) { port() }
                    Box(Modifier.weight(2f)) { user() }
                }
            }
        }
    }

    @Composable
    fun Credentials() {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, label) in listOf(false to R.string.ui_password, true to R.string.ui_ssh_key)) {
                FilterChip(draft.keyMode == key, {
                    if (draft.keyMode != key) {
                        keyError = false
                        change(draft.copy(keyMode = key, credential = "", passphrase = ""))
                    }
                }, { Text(ui(label), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    enabled = enabled, modifier = Modifier.weight(1f))
            }
        }
        if (draft.keyMode) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = enabled) {
                Text(ui(if (draft.credential.isEmpty()) R.string.ui_select_private_key else R.string.ui_replace_selected_key))
            }
            if (keyError) Text(ui(R.string.ui_could_not_read_the_key_file),
                color = if (sectioned) MaterialTheme.colorScheme.error else HorizonPalette.danger)
            PasswordField(ui(R.string.ui_key_passphrase_if_set), draft.passphrase, enabled) { change(draft.copy(passphrase = it)) }
        } else {
            PasswordField(ui(R.string.ui_ssh_password), draft.credential, enabled) { change(draft.copy(credential = it)) }
        }
    }
    if (sectioned) {
        Text(ui(R.string.vps_form_address), style = MaterialTheme.typography.titleMedium)
        Address()
        HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
        SettingHeading(ui(R.string.vps_form_credentials), R.string.vps_form_auth_help)
        Credentials()
    } else {
        Address()
        Credentials()
    }
}

@Composable
internal fun VpsFormSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().readingNavigationGlass(feedGlassShape)
        .padding(horizontal = 17.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val column = this
        CompositionLocalProvider(LocalContentColor provides ReadingGlass.text) { column.content() }
    }
}

@Composable
internal fun SavedServerEditor(server: Server, panel: Panel, model: ManagerModel, close: () -> Unit) {
    var draft by remember(server.id) { mutableStateOf(ServerDraft(server)) }
    val enabled = !panel.busy && panel.hostConfirmation == null
    val canSave = draft.update(server)?.let { it != server && AgentApi.validHttps(it.https) } == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ServerCredentialFields(draft, enabled) { draft = it }
        ValueField(ui(R.string.ui_https_domain_optional), draft.https,
            { draft = draft.copy(https = it) }, enabled = enabled)
        if (!AgentApi.validHttps(draft.https.trim())) {
            Text(errorText("HTTPS_URL"), color = HorizonPalette.danger)
        }
        panel.problem.takeIf(String::isNotBlank)?.let {
            Text(errorText(it), color = HorizonPalette.danger)
        }
        SettingsSaveAction(draft != ServerDraft(server), R.string.ui_save_connection, enabled && canSave) {
            model.editServer(server.id, draft)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = close, enabled = enabled || panel.connectionCancellable) { Text(ui(R.string.ui_cancel)) }
        }
    }
    ServerHostPrompt(panel.hostConfirmation, model::confirmHost)
}

@Composable
internal fun ServerHostPrompt(key: HostConfirmation?, respond: (Boolean) -> Unit) {
    if (key == null) return
    HorizonAlertDialog(
        onDismissRequest = { respond(false) },
        title = { Text(ui(if (key.previous.isBlank()) R.string.ui_verify_the_vps_key else R.string.ui_the_vps_key_has_changed)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui(R.string.ui_compare_the_fingerprint_with_the_one))
                if (key.previous.isNotBlank()) Text(ui(R.string.ui_previous, key.previous))
                Text(ui(R.string.ui_received, key.fingerprint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { respond(true) }) { Text(ui(R.string.ui_fingerprint_matches)) } },
        dismissButton = { TextButton(onClick = { respond(false) }) { Text(ui(R.string.ui_cancel)) } },
    )
}
