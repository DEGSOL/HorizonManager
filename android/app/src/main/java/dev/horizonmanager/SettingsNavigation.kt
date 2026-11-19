package dev.horizonmanager

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.unifiedpush.android.connector.UnifiedPush

@Composable
private fun SettingsSection(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ui(title), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
internal fun SettingsHome(panel: Panel, preset: ThemePreset, open: (String) -> Unit, servers: () -> Unit) {
    val hasConfig = panel.config.optJSONObject("config") != null && panel.schema.length() > 0
    if (panel.ready) {
        GlassSettingRow(ui(R.string.sources), ui(R.string.sources_description)) { open("sources") }
        if (hasConfig) {
            GlassSettingRow(ui(R.string.ui_digest), ui(R.string.editorial_digest_settings_detail)) { open("digest_settings") }
            GlassSettingRow(ui(R.string.editorial_ai_processing), ui(R.string.editorial_ai_settings_detail)) { open("ai_settings") }
        }
    }
    SettingsSection(R.string.settings_app_group) {
        GlassSettingRow(ui(R.string.theme), ui(preset.title)) { open("theme") }
        GlassSettingRow(ui(R.string.editorial_feed_behavior), ui(R.string.editorial_feed_behavior_detail)) { open("reading") }
        if (panel.ready) GlassSettingRow(ui(R.string.editorial_local_data), ui(R.string.editorial_local_data_detail)) { open("local") }
        if (panel.ready) {
            GlassSettingRow(ui(R.string.settings_notifications), ui(R.string.settings_notifications_detail)) { open("alerts") }
        }
    }
    if (panel.ready || panel.servers.isNotEmpty()) SettingsSection(R.string.settings_server_group) {
        GlassSettingRow(ui(R.string.ui_servers), ui(R.string.servers_description), open = servers)
        if (panel.ready) {
            GlassSettingRow(ui(R.string.ui_connection), ui(R.string.settings_connection_detail)) { open("connection") }
            GlassSettingRow(ui(R.string.control), ui(R.string.control_description)) { open("control") }
            GlassSettingRow(ui(R.string.ui_maintenance), ui(R.string.settings_maintenance_detail)) { open("maintenance") }
        }
    }
}

@Composable
internal fun DigestSettings(open: (String) -> Unit) {
    GlassSettingRow(ui(R.string.settings_digest_rules), ui(R.string.settings_digest_rules_detail)) { open("digest") }
    GlassSettingRow(ui(R.string.ui_schedule), ui(R.string.ui_days_intervals_and_time_zone)) { open("schedule") }
}

@Composable
internal fun AiSettings(open: (String) -> Unit) {
    GlassSettingRow(ui(R.string.settings_ai_model), ui(R.string.ui_model_api_address_and_request_limits)) { open("ai") }
    SettingsSection(R.string.ui_advanced_settings) {
        GlassSettingRow(ui(R.string.ui_processing_profiles), ui(R.string.ui_prompts_scoring_criteria_and_output_blocks)) { open("profiles") }
        GlassSettingRow(ui(R.string.ui_secrets), ui(R.string.ui_ai_keys_and_integration_tokens)) { open("secrets") }
    }
}

internal fun isConnectionFailure(problem: String): Boolean =
    problem.startsWith("SSH_") || problem.startsWith("HTTPS_") || problem.startsWith("AGENT_") ||
        problem.substringBefore(" · ") in setOf("AUTH_REQUIRED", "API_401", "API_403")

@Composable
internal fun ServerConnectionStatus(panel: Panel, model: ManagerModel) {
    val theme = LocalHorizonPalette.current
    val problem = panel.connectionProblem
    val (title, color) = when {
        panel.server == null -> R.string.editorial_not_configured to theme.muted
        panel.syncing || panel.busy && panel.lastContact.isBlank() -> R.string.editorial_connecting to theme.muted
        problem.substringBefore(" · ") in setOf("SSH_AUTH", "AUTH_REQUIRED", "API_401", "API_403") -> R.string.editorial_auth_error to theme.danger
        problem.isNotBlank() -> R.string.editorial_unavailable to theme.danger
        panel.lastContact.isNotBlank() -> R.string.editorial_connected to theme.success
        else -> R.string.editorial_not_checked to theme.muted
    }
    Tile {
        Text(panel.server?.name ?: ui(R.string.ui_servers), style = MaterialTheme.typography.titleMedium)
        Text(ui(title), style = MaterialTheme.typography.labelLarge, color = color)
        if (panel.lastContact.isNotBlank()) Text(ui(R.string.editorial_last_contact, localTime(panel.lastContact)),
            style = MaterialTheme.typography.bodySmall, color = theme.muted)
        if (problem.isNotBlank() && !panel.syncing) {
            Text(errorText(problem), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { model.refresh() }, enabled = !panel.busy) { Text(ui(R.string.ui_retry)) }
        }
    }
}

@Composable
internal fun ConnectionSettings(panel: Panel, model: ManagerModel, dirtyChange: (Boolean) -> Unit, editSsh: () -> Unit) {
    val server = panel.server ?: return
    var url by remember(server.id, server.https) { mutableStateOf(server.https) }
    val changed = url.trim() != server.https
    val valid = AgentApi.validHttps(url.trim())
    SideEffect { dirtyChange(changed) }
    ServerConnectionStatus(panel, model)
    Tile {
        OutlinedTextField(url, { url = it }, label = { Text(ui(R.string.ui_https_domain_optional)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, isError = changed && !valid,
            supportingText = { Text(if (changed && !valid) errorText("HTTPS_URL") else ui(R.string.ui_leave_blank_to_use_ssh_https)) },
            colors = horizonFieldColors())
        Reveal(changed) {
            Text(ui(R.string.editorial_unsaved), color = LocalHorizonPalette.current.warning)
            Button(onClick = { model.settings(url.trim(), server.background) },
                enabled = !panel.busy && valid) { Text(ui(R.string.ui_save_connection)) }
        }
    }
    GlassSettingRow(ui(R.string.ui_ssh_settings), "${server.user} @ ${server.host} · ${server.port}", enabled = !panel.busy && !changed, open = editSsh)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotificationSettings(panel: Panel, model: ManagerModel, permission: () -> Unit, delivery: () -> Unit) {
    val server = panel.server ?: return
    val context = LocalContext.current
    val registration by remember(context, server.id) { DigestNotifications.observeRegistration(context, server.id) }
        .collectAsState(initial = DigestNotifications.registration(context, server.id))
    var chooseDistributor by remember { mutableStateOf(false) }
    if (chooseDistributor) {
        val distributors = UnifiedPush.getDistributors(context)
        HorizonAlertDialog(onDismissRequest = { chooseDistributor = false },
            title = { Text(ui(R.string.ui_push_notification_service)) },
            text = {
                Column {
                    if (distributors.isEmpty()) Text(ui(R.string.ui_install_ntfy_and_enable_unifiedpush_in))
                    distributors.forEach { distributor ->
                        val name = runCatching {
                            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(distributor, 0)).toString()
                        }.getOrDefault(distributor)
                        TextButton(onClick = {
                            chooseDistributor = false
                            permission()
                            DigestNotifications.register(context, distributor, server.id)
                        }) { Text(name) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { chooseDistributor = false }) { Text(ui(R.string.ui_close)) } })
    }
    Tile {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ui(R.string.ui_background_news_checks), Modifier.weight(1f))
            Switch(server.background, enabled = !panel.busy, onCheckedChange = { checked ->
                if (checked) permission()
                model.settings(server.https, checked)
            })
        }
        Text(ui(R.string.ui_for_faster_alerts_connect_ntfy_through), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Reveal(server.background) {
            if (registration.isNotEmpty()) Text(ui(R.string.ui_push_notifications_connected))
            FlowRow {
                TextButton(onClick = { chooseDistributor = true }) { Text(ui(R.string.ui_connect_push_notifications)) }
                if (registration.isNotEmpty()) {
                    TextButton(onClick = {
                        DigestNotifications.unregister(context, server.id)
                        DigestWorker.sync(context, server.id)
                    }) { Text(ui(R.string.ui_disconnect_push_notifications)) }
                }
            }
        }
    }
    if (panel.config.length() > 0 && panel.schema.length() > 0) {
        GlassSettingRow(ui(R.string.settings_server_delivery), ui(R.string.ui_email_and_webhook), open = delivery)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServerMaintenance(panel: Panel, model: ManagerModel, open: (String) -> Unit) {
    var restart by remember { mutableStateOf(false) }
    var upgrade by remember { mutableStateOf(false) }
    if (restart) PasswordDialog(ui(R.string.ui_restart_agent), ui(R.string.ui_the_current_job_may_be_interrupted),
        close = { restart = false }) { password -> restart = false; model.restart(password) }
    if (upgrade) PasswordDialog(ui(R.string.ui_update_vps_agent), ui(R.string.ui_settings_and_the_archive_will_stay),
        close = { upgrade = false }) { password -> upgrade = false; model.updateAgent(password) }
    GlassSettingRow(ui(R.string.journal), ui(R.string.settings_journal_detail)) { open("journal") }
    if (panel.config.length() > 0 && panel.schema.length() > 0) {
        GlassSettingRow(ui(R.string.settings_transfer), ui(R.string.settings_transfer_detail)) { open("transfer") }
        GlassSettingRow(ui(R.string.ui_secrets), ui(R.string.ui_ai_keys_and_integration_tokens)) { open("secrets") }
        GlassSettingRow(ui(R.string.ui_advanced_settings), ui(R.string.ui_extractors_and_display)) { open("advanced") }
    }
    Tile {
        Text(ui(R.string.settings_agent_tools), style = MaterialTheme.typography.titleMedium)
        FlowRow {
            TextButton(onClick = model::check, enabled = !panel.busy) { Text(ui(R.string.ui_check_configuration)) }
            TextButton(onClick = model::checkVersions, enabled = !panel.busy) { Text(ui(R.string.ui_check_horizon_version)) }
            TextButton(onClick = { restart = true }, enabled = !panel.busy) { Text(ui(R.string.ui_restart_agent)) }
            if (agentUpdateAvailable(panel.status.optString("agent")) || panel.updatingAgent) {
                TextButton(onClick = { upgrade = true }, enabled = !panel.busy && !panel.updatingAgent &&
                    panel.jobs.none { it.optString("state") in setOf("queued", "running") }) { Text(ui(R.string.ui_update_vps_agent)) }
            }
        }
        Reveal(panel.updatingAgent) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        val result = panel.maintenanceResult
        Reveal(result.isNotEmpty()) { Text(result, style = MaterialTheme.typography.bodyMedium) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TransferSettings(panel: Panel, model: ManagerModel, backup: () -> Unit, restore: () -> Unit,
                              importFile: (String) -> Unit, export: (String, String) -> Unit,
                              confirm: (String, () -> Unit) -> Unit) {
    val config = panel.config.optJSONObject("config") ?: return
    Tile {
        Text(ui(R.string.ui_backup), style = MaterialTheme.typography.titleMedium)
        FlowRow {
            TextButton(onClick = backup, enabled = !panel.busy) { Text(ui(R.string.ui_export_backup)) }
            TextButton(onClick = restore, enabled = !panel.busy) { Text(ui(R.string.ui_restore_backup_4afed2)) }
        }
    }
    Tile {
        Text(ui(R.string.ui_transfer_settings), style = MaterialTheme.typography.titleMedium)
        FlowRow {
            TextButton(onClick = { importFile("config") }) { Text(ui(R.string.ui_import_horizon_config)) }
            TextButton(onClick = { export("horizon-settings.json", config.toString(2)) }) { Text(ui(R.string.ui_export_settings)) }
            TextButton(onClick = { importFile("profiles") }) { Text(ui(R.string.ui_import_manager_profiles)) }
            TextButton(onClick = { export("horizon-profiles.json", panel.config.getJSONArray("profiles").toString(2)) }) { Text(ui(R.string.ui_export_profiles)) }
            TextButton(onClick = { confirm(ui(R.string.ui_restore_the_previous_configuration_and_its)) { model.revert() } }) { Text(ui(R.string.ui_roll_back_settings)) }
        }
        var path by remember { mutableStateOf(panel.probe["existing"].orEmpty()) }
        ValueField(ui(R.string.ui_existing_horizon_folder_on_the_vps), path, { path = it })
        TextButton(enabled = !panel.busy && path.startsWith("/"), onClick = {
            confirm(ui(R.string.ui_copy_settings_from_this_folder_into)) { model.copyExisting(path) }
        }) { Text(ui(R.string.ui_copy_configuration_from_vps)) }
    }
}

@Composable
internal fun ReadingPreferences(order: NewsOrder, change: (NewsOrder) -> Unit) {
    Tile {
        Text(ui(R.string.editorial_feed_behavior_detail), color = LocalHorizonPalette.current.muted)
        Choice(ui(R.string.ui_sort), NewsOrder.entries.map { it.name }, order.name,
            { change(NewsOrder.valueOf(it)) }, { ui(NewsOrder.valueOf(it).label) })
    }
}

@Composable
internal fun LocalReadingData(panel: Panel) {
    val server = panel.server ?: return
    val context = LocalContext.current
    val count by remember(context, server.id) { ManagerDb.get(context).cache().cachedIssues(server.id) }
        .collectAsState(initial = null)
    Tile {
        Text(ui(R.string.editorial_local_data_detail))
        count?.let { Text(ui(R.string.editorial_cached_issues, it)) }
        Text(ui(R.string.editorial_saved_count, panel.flags.count(ArticleFlag::saved)))
        Text(ui(R.string.editorial_read_count, panel.flags.count(ArticleFlag::read)))
    }
}
