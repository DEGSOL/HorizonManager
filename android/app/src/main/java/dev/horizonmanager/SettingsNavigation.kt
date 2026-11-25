package dev.horizonmanager

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
private fun SettingsSection(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Tile {
        Text(ui(title), Modifier.padding(horizontal = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = ReadingGlass.text)
        content()
    }
}

@Composable
internal fun SettingsHome(panel: Panel, model: ManagerModel, open: (String) -> Unit, page: String) {
    val configured = panel.config.has("config") && panel.schema.length() > 0
    fun rowPage(id: String): () -> Unit = { open(id) }
    when (page) {
        "settings" -> {
            Column(Modifier.fillMaxWidth().connectionStatusGlass(panel)
                .clickable(role = Role.Button, onClick = rowPage("server_settings"))
                .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(ui(R.string.mr_server), style = MaterialTheme.typography.labelLarge, color = Color.White)
                Text(panel.server?.name ?: ui(R.string.editorial_not_configured),
                    style = MaterialTheme.typography.headlineSmall, color = Color.White)
                panel.server?.let {
                    Text(it.host, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .87f))
                }
            }
            if (panel.ready) SettingsSection(R.string.hovq_settings_feed_digest) {
                GlassSettingRow(ui(R.string.archive_digests), ui(R.string.archive_digests_detail), open = rowPage("archive"))
                GlassSettingRow(ui(R.string.mr_news), ui(R.string.mr_news_detail), open = rowPage("news_settings"))
                GlassSettingRow(ui(R.string.editorial_ai_processing), ui(R.string.editorial_ai_settings_detail),
                    enabled = configured, open = rowPage("ai_settings"))
            }
            SettingsSection(R.string.setting_group_application) {
                GlassSettingRow(ui(R.string.mr_interface), ui(R.string.hovq_settings_interface_detail), open = rowPage("interface"))
                GlassSettingRow(ui(R.string.ui_maintenance), ui(R.string.settings_maintenance_detail), open = rowPage("maintenance"))
            }
        }
        "news_settings" -> {
            SettingsSection(R.string.setting_group_content) {
                GlassSettingRow(ui(R.string.sources), ui(R.string.sources_description), open = rowPage("sources"))
                GlassSettingRow(ui(R.string.mr_images), ui(R.string.mr_images_detail), open = rowPage("images"))
            }
            if (configured) SettingsSection(R.string.ui_digest) {
                GlassSettingRow(ui(R.string.settings_digest_rules), ui(R.string.settings_digest_rules_detail), open = rowPage("digest"))
                GlassSettingRow(ui(R.string.ui_schedule), ui(R.string.ui_days_intervals_and_time_zone),
                    help = R.string.schedule_vps_hint, open = rowPage("schedule"))
                val supportsLanguage = panel.status.has("digest_language")
                val availableLanguages = panel.status.optJSONArray("digest_languages")?.strings() ?: legacyDigestLanguages
                Choice(ui(R.string.digest_output_language), digestLanguages, panel.preferredDigestLanguage(), model::setDigestLanguage,
                    ::digestLanguageLabel, enabled = supportsLanguage && !panel.busy, row = true,
                    help = R.string.digest_language_detail, entryEnabled = { it in availableLanguages })
                if (supportsLanguage && !availableLanguages.containsAll(digestLanguages)) {
                    SettingNote(ui(R.string.hovq_language_upgrade))
                }
                GlassSettingRow(ui(R.string.settings_server_delivery), ui(R.string.hovq_settings_delivery_detail), open = rowPage("delivery"))
            }
        }
        "interface" -> {
            SettingsSection(R.string.setting_group_reading) {
                GlassSettingRow(ui(R.string.editorial_feed_behavior), ui(R.string.editorial_feed_behavior_detail), open = rowPage("reading"))
                GlassSettingRow(ui(R.string.hovq_icon), ui(R.string.hovq_icon_detail), open = rowPage("icon"))
            }
            if (panel.ready) SettingsSection(R.string.setting_group_device) {
                GlassSettingRow(ui(R.string.settings_notifications), ui(R.string.hovq_settings_device_alerts_detail), open = rowPage("alerts"))
                GlassSettingRow(ui(R.string.editorial_local_data), ui(R.string.editorial_local_data_detail), open = rowPage("local"))
            }
        }
    }
}

@Composable
internal fun ImageSettings(panel: Panel, model: ManagerModel) {
    val supported = panel.status.has("media_search")
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui(R.string.setting_find_missing_covers), Modifier.weight(1f))
            SettingHelp(ui(R.string.setting_find_missing_covers), R.string.mr_search_detail)
            Switch(panel.status.optBoolean("media_search"), { model.mediaSearch(it) }, enabled = supported && !panel.busy)
        }
        if (!supported) Text(ui(R.string.mr_agent_images))
    }
}

@Composable
internal fun AiSettings(panel: Panel, model: ManagerModel, open: (String) -> Unit) {
    val mode = panel.status.optString("enrichment_mode")
    Tile {
        GlassSettingRow(ui(R.string.settings_ai_model), ui(R.string.ui_model_api_address_and_request_limits)) { open("ai") }
        GlassSettingRow(ui(R.string.ui_processing_profiles), ui(R.string.ui_prompts_scoring_criteria_and_output_blocks),
            help = R.string.setting_help_profiles) { open("profiles") }
        Choice(ui(R.string.enrichment_mode), listOf("on_demand", "automatic"),
            mode.ifBlank { "on_demand" }, model::enrichmentMode, {
                ui(if (it == "automatic") R.string.enrichment_automatic else R.string.enrichment_on_demand)
            }, enabled = mode.isNotBlank() && !panel.busy, row = true, help = R.string.setting_help_enrichment,
            menuDetail = R.string.hovq_enrichment_token_cost)
        if (mode.isBlank()) SettingNote(ui(R.string.article_context_update))
    }
}

internal fun ConnectionPhase.label(): Int = when (this) {
    ConnectionPhase.Idle -> R.string.editorial_not_checked
    ConnectionPhase.Connecting -> R.string.editorial_connecting
    ConnectionPhase.SshAvailable -> R.string.connection_ssh_available
    ConnectionPhase.CheckingAgent -> R.string.connection_checking_agent
    ConnectionPhase.Connected -> R.string.editorial_connected
}

@Composable
private fun Modifier.connectionStatusGlass(panel: Panel): Modifier {
    val connected = panel.server != null && panel.ready &&
        panel.connectionPhase == ConnectionPhase.Connected && panel.connectionProblem.isBlank()
    return glassSurface(feedGlassShape).savedNoticeWash(if (connected) NoticeWash.Connected else NoticeWash.Disconnected)
}

@Composable
internal fun ServerConnectionStatus(panel: Panel, model: ManagerModel?) {
    val problem = panel.connectionProblem
    val title = when {
        panel.server == null -> R.string.editorial_not_configured
        problem in setOf("SSH_AUTH", "AUTH_REQUIRED", "API_401", "API_403") ->
            R.string.editorial_auth_error
        problem.isNotBlank() -> R.string.editorial_unavailable
        panel.connectionPhase == ConnectionPhase.Connected -> R.string.editorial_connected
        else -> panel.connectionPhase.label()
    }
    CompositionLocalProvider(LocalLightGlass provides false, LocalContentColor provides HorizonPalette.text) {
        Column(Modifier.fillMaxWidth().connectionStatusGlass(panel).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(panel.server?.name ?: ui(R.string.ui_servers), style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(ui(title), style = MaterialTheme.typography.labelLarge, color = Color.White)
            if (panel.lastContact.isNotBlank()) Text(ui(R.string.editorial_last_contact, localTime(panel.lastContact)),
                style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .87f))
            if (model != null && problem.isNotBlank()) {
                Text(errorText(problem), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    if (panel.ready) model.refresh() else model.refreshInstallation(false)
                }, enabled = !panel.busy) { Text(ui(R.string.ui_retry)) }
            }
            if (panel.connectionCancellable && model != null) {
                TextButton(onClick = model::cancelConnection) { Text(ui(R.string.ui_cancel)) }
            }
        }
    }
}

@Composable
internal fun ServerSettings(panel: Panel, model: ManagerModel, servers: () -> Unit, secrets: () -> Unit,
                            confirm: (String, () -> Unit) -> Unit) {
    ServerConnectionStatus(panel, model)
    SettingsSection(R.string.hovq_settings_server_access) {
        GlassSettingRow(ui(R.string.ui_servers), ui(R.string.servers_description), open = servers)
        if (panel.ready && panel.config.has("config") && panel.schema.length() > 0) {
            GlassSettingRow(ui(R.string.ui_secrets), ui(R.string.ui_ai_keys_and_integration_tokens),
                help = R.string.setting_help_secrets, open = secrets)
        }
    }
    val server = panel.server ?: return
    var attaching by remember(server.id) { mutableStateOf(false) }
    if (panel.connectionProblem in setOf("AUTH_REQUIRED", "API_401", "API_403")) {
        OutlinedButton(onClick = { attaching = true }, enabled = !panel.busy) {
            Text(ui(R.string.ui_connect_an_existing_agent))
        }
    }
    if (attaching) PasswordDialog(ui(R.string.ui_connect_an_existing_agent),
        ui(R.string.ui_manager_will_retrieve_the_agent_key), close = { attaching = false }) { password ->
        attaching = false
        model.attachExisting(password)
    }
    if (panel.ready) HorizonOnVps(panel, model, confirm)
}

@Composable
internal fun NotificationSettings(panel: Panel, model: ManagerModel, allowed: Boolean,
                                  permission: () -> Unit, soundSettings: () -> Unit) {
    val server = panel.server ?: return
    val live by DigestDeliveryService.connected.collectAsStateWithLifecycle()
    Tile {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ui(R.string.hovq_digest_notifications), Modifier.weight(1f))
            SettingHelp(ui(R.string.hovq_digest_notifications), R.string.hovq_push_delivery_help)
            Switch(server.background, enabled = !panel.busy, inverted = true, onCheckedChange = { checked ->
                model.settings(server.https, checked)
                if (checked && !allowed) permission()
            })
        }
        Reveal(server.background) {
            if (allowed) {
                Text(ui(if (server.id in live) R.string.hovq_listener_connected else R.string.hovq_polling_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SettingNote(ui(R.string.hovq_alerts_blocked), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = permission) { Text(ui(R.string.hovq_allow_notifications)) }
            }
        }
    }
    GlassSettingRow(ui(R.string.hovq_digest_sound_settings), ui(R.string.hovq_digest_sound_settings_detail), open = soundSettings)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServerMaintenance(panel: Panel, model: ManagerModel, open: (String) -> Unit) {
    val serverId = panel.server?.id
    var restart by remember(serverId) { mutableStateOf(false) }
    var upgrade by remember(serverId) { mutableStateOf(false) }
    var runningTool by remember(serverId) { mutableIntStateOf(0) }
    LaunchedEffect(runningTool, panel.busy, panel.updatingAgent) {
        if (!panel.busy && !panel.updatingAgent) runningTool = 0
    }
    fun runTool(title: Int, action: () -> Unit) {
        runningTool = title
        action()
    }
    if (restart) PasswordDialog(ui(R.string.ui_restart_agent), ui(R.string.ui_the_current_job_may_be_interrupted),
        close = { restart = false }) { password ->
        restart = false
        runTool(R.string.ui_restart_agent) { model.restart(password) }
    }
    if (upgrade) PasswordDialog(ui(R.string.ui_update_vps_agent), ui(R.string.ui_settings_and_the_archive_will_stay),
        close = { upgrade = false }) { password ->
        upgrade = false
        runTool(R.string.ui_update_vps_agent) { model.updateAgent(password) }
    }
    SettingsSection(R.string.hovq_settings_diagnostics_backup) {
        GlassSettingRow(ui(R.string.journal), ui(R.string.settings_journal_detail)) { open("journal") }
        GlassSettingRow(ui(R.string.settings_transfer), ui(R.string.hovq_transfer_detail)) { open("transfer") }
        if (panel.ready && panel.config.length() > 0 && panel.schema.length() > 0) {
            GlassSettingRow(ui(R.string.ui_advanced_settings), ui(R.string.ui_extractors_and_display)) { open("advanced") }
        }
    }
    if (!panel.ready) return
    Tile {
        Text(ui(R.string.settings_agent_tools), style = MaterialTheme.typography.titleMedium)
        val wash = rememberNoticeWashPhase()
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            CompactSettingsButton(R.string.ui_check_configuration, !panel.busy,
                runningTool == R.string.ui_check_configuration, wash) {
                runTool(R.string.ui_check_configuration, model::check)
            }
            CompactSettingsButton(R.string.ui_check_horizon_version, !panel.busy,
                runningTool == R.string.ui_check_horizon_version, wash) {
                runTool(R.string.ui_check_horizon_version, model::checkVersions)
            }
            CompactSettingsButton(R.string.ui_restart_agent, !panel.busy,
                restart || runningTool == R.string.ui_restart_agent, wash) { restart = true }
            if (agentUpdateAvailable(panel.status.optString("agent")) || panel.updatingAgent) {
                val canUpdate = !panel.busy && !panel.updatingAgent &&
                    panel.jobs.none { it.optString("state") in setOf("queued", "running") }
                CompactSettingsButton(R.string.ui_update_vps_agent, canUpdate,
                    upgrade || panel.updatingAgent || runningTool == R.string.ui_update_vps_agent, wash) { upgrade = true }
            }
        }
        Reveal(panel.updatingAgent) { DottedProgress(Modifier.fillMaxWidth()) }
        val result = panel.maintenanceResult
        Reveal(result.isNotEmpty()) { Text(result, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun TransferSettings(panel: Panel, backup: (Boolean) -> Unit, restore: (Boolean) -> Unit) {
    val connected = panel.ready && panel.config.has("revision")
    Tile {
        Text(ui(R.string.hovq_vps_backup), style = MaterialTheme.typography.titleMedium)
        SettingNote(ui(R.string.hovq_vps_backup_detail))
        if (!connected) Text(ui(R.string.hovq_backup_connect_first), style = MaterialTheme.typography.bodySmall)
        val enabled = connected && !panel.busy
        SettingsActionPair(R.string.hovq_export, R.string.hovq_import, enabled, enabled,
            { backup(false) }, { restore(false) })
    }
    Tile {
        Text(ui(R.string.hovq_device_backup), style = MaterialTheme.typography.titleMedium)
        SettingNote(ui(R.string.hovq_device_backup_detail))
        SettingsActionPair(R.string.hovq_export, R.string.hovq_import, !panel.busy, !panel.busy,
            { backup(true) }, { restore(true) })
    }
}

@Composable
internal fun ReadingPreferences(order: NewsOrder, change: (NewsOrder) -> Unit) {
    Tile {
        Choice(ui(R.string.ui_sort), NewsOrder.entries.map { it.name }, order.name,
            { change(NewsOrder.valueOf(it)) }, { ui(NewsOrder.valueOf(it).label) },
            row = true, help = R.string.setting_help_sort)
    }
}

@Composable
internal fun LocalReadingData(panel: Panel) {
    val server = panel.server ?: return
    val context = LocalContext.current
    val count by remember(context, server.id) { ManagerDb.get(context).cache().cachedIssues(server.id) }
        .collectAsState(initial = null)
    Tile {
        SettingHeading(ui(R.string.editorial_local_data), R.string.setting_help_offline)
        count?.let { Text(ui(R.string.editorial_cached_issues, it)) }
        Text(ui(R.string.editorial_saved_count, panel.flags.count(ArticleFlag::saved)))
        Text(ui(R.string.editorial_read_count, panel.flags.count(ArticleFlag::read)))
    }
}
