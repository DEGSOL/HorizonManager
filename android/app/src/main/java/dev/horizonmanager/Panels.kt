package dev.horizonmanager

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Sources(panel: Panel, model: ManagerModel, dirtyChange: (Boolean) -> Unit) {
    val config = panel.config.optJSONObject("config")
    val sourceRoot = panel.schema.optJSONObject("config")
    if (config == null || sourceRoot == null) {
        Tile { Text(ui(R.string.ui_connect_to_the_agent_to_manage)); TextButton(onClick = { model.refresh() }) { Text(ui(R.string.ui_connect)) } }
        return
    }
    val profiles = panel.config.getJSONArray("profiles")
    val ids = profileIds(profiles)
    val original = config.getJSONObject("sources")
    var working by remember(panel.server?.id, panel.config.optInt("revision")) { mutableStateOf(original.copyObject()) }
    var type by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("rss") }
    val dirty = working.toString() != original.toString()
    SideEffect { dirtyChange(dirty) }
    val types = panel.schema.optJSONArray("sources")?.strings().orEmpty()
    val sourceSchema = definition(sourceRoot, sourceRoot.getJSONObject("properties").getJSONObject("sources")).getJSONObject("properties")
    Tile {
        Choice(ui(R.string.ui_integration), types, type, { type = it }, row = true, help = R.string.setting_help_sources)
        when (type) {
            "rss" -> Text(ui(R.string.ui_add_an_rss_or_atom_url))
            "twitter" -> Text(ui(R.string.ui_apify_requires_apify_token_and_access))
            "openbb" -> if (!panel.status.optBoolean("openbb")) Text(ui(R.string.ui_openbb_requires_an_additional_package_on))
            "github" -> Text(ui(R.string.ui_use_user_events_for_user_activity))
            "google_news", "gdelt" -> Text(ui(R.string.ui_enter_a_search_query_and_optionally))
        }
        key(type) {
            if (type == "telegram") {
                val schema = definition(sourceRoot, sourceSchema.getJSONObject(type))
                val item = schema.getJSONObject("properties").getJSONObject("channels").getJSONObject("items")
                TelegramSources(working.optJSONObject(type) ?: JSONObject(), original.optJSONObject(type) ?: JSONObject(),
                    sourceRoot, item, ids, panel.busy || panel.jobs.any {
                        it.optString("kind") != "article_context" && it.optString("state") in setOf("queued", "running")
                    },
                    { working = working.copyObject().put(type, it) },
                    { index -> model.submit("source", "telegram", if (index == null) null else "channels", index) })
            } else EditValue(sourceRoot, sourceSchema.getJSONObject(type), type, working.opt(type), {
                working = working.copyObject().put(type, it)
            }, ids, expandedByDefault = true)
        }
        SettingsSaveAction(dirty, R.string.ui_apply_source_settings, !panel.busy) {
            model.saveConfig(config.copyObject().put("sources", working), profiles)
        }
        Reveal(dirty) {
            SettingNote(ui(R.string.editorial_unsaved))
            TextButton(onClick = { working = original.copyObject() }, enabled = !panel.busy) { Text(ui(R.string.ui_discard_changes)) }
        }
    }
    if (type != "telegram") Tile {
        Text(ui(R.string.ui_test_saved_sources), style = MaterialTheme.typography.titleMedium)
        when (val value = original.opt(type)) {
            is JSONArray -> for (index in 0 until value.length()) {
                val entry = value.optJSONObject(index)
                val name = entry?.optString("name")?.ifBlank { null } ?: entry?.optString("username")?.ifBlank { null }
                    ?: entry?.optString("repo")?.ifBlank { null } ?: "#${index + 1}"
                TextButton(onClick = { model.submit("source", type, index = index) }, enabled = !panel.busy) { Text(ui(R.string.ui_test, name)) }
            }
            is JSONObject -> {
                TextButton(onClick = { model.submit("source", type) }, enabled = !panel.busy) { Text(ui(R.string.ui_test, label(type))) }
                val buckets = when (type) {
                    "telegram" -> listOf("channels")
                    "reddit" -> listOf("subreddits", "users")
                    "openbb" -> listOf("watchlists")
                    "twitter" -> listOf("users", "keywords")
                    else -> emptyList()
                }
                for (bucket in buckets) value.optJSONArray(bucket)?.let { items ->
                    for (index in 0 until items.length()) {
                        val item = items.get(index)
                        val name = (item as? JSONObject)?.let {
                            listOf("name", "channel", "subreddit", "username").firstNotNullOfOrNull { key -> it.optString(key).ifBlank { null } }
                        } ?: if (item is String) item else "#${index + 1}"
                        TextButton(onClick = { model.submit("source", type, bucket, index) }, enabled = !panel.busy) { Text(ui(R.string.ui_test, name)) }
                    }
                }
            }
            else -> Text(ui(R.string.ui_this_integration_has_not_been_configured))
        }
    }
    if (panel.sourceHistory.isNotEmpty()) Tile {
        Text(ui(R.string.ui_recent_checks), style = MaterialTheme.typography.titleMedium)
        panel.sourceHistory.take(30).forEach { history ->
            Text("${history.optString("source")} · ${stateName(history.optString("status"))}")
            Text(ui(R.string.ui_checked, localTime(history.optString("checked"))) +
                history.optString("last_success").takeIf { it.isNotBlank() && it != "null" }?.let { ui(R.string.ui_last_success, localTime(it)) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun stateName(value: String) = mapOf("queued" to ui(R.string.ui_waiting), "running" to ui(R.string.ui_running_eff79c),
    "success" to ui(R.string.ui_succeeded), "empty" to ui(R.string.ui_no_posts), "partial_failure" to ui(R.string.ui_partial_failure),
    "failure" to ui(R.string.ui_error_72aecd), "failed" to ui(R.string.ui_error_72aecd), "cancelled" to ui(R.string.ui_stopped), "timeout" to ui(R.string.ui_timed_out), "interrupted" to ui(R.string.ui_interrupted))[value] ?: value

internal fun phaseName(value: String) = mapOf("fetch" to ui(R.string.ui_collecting_news), "score" to ui(R.string.ui_ai_scoring),
    "filter" to ui(R.string.ui_filtering_and_deduplication), "enrich" to ui(R.string.ui_analysis_and_context),
    "generate" to ui(R.string.ui_generating_digest), "save" to ui(R.string.ui_saving), "delivery" to ui(R.string.ui_delivery),
    "media" to ui(R.string.journal_digest_media))[value] ?: stateName(value)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HorizonOnVps(panel: Panel, model: ManagerModel, confirm: (String, () -> Unit) -> Unit) {
    val status = panel.status
    val active = panel.jobs.firstOrNull {
        it.optString("kind") != "article_context" && it.optString("state") in setOf("queued", "running")
    }
    Tile {
        Text(ui(R.string.ui_horizon_on_the_vps), style = MaterialTheme.typography.titleLarge)
        if (status.length() == 0) Text(ui(R.string.ui_monitoring_data_has_not_loaded_yet))
        else {
            if (status.has("agent") && status.has("horizon")) Text(ui(R.string.ui_agent_horizon, status.optString("agent"), status.optString("horizon").take(12)))
            val mem = status.optJSONObject("memory")
            val total = mem?.optLong("MemTotal") ?: 0
            val available = mem?.optLong("MemAvailable") ?: 0
            if (mem?.has("MemAvailable") == true && total > 0 && status.has("disk_free")) Text(ui(R.string.ui_ram_mib_free_disk_mib, (total - available) / 1_048_576, total / 1_048_576, status.optLong("disk_free") / 1_048_576))
            if (status.has("cpu") && status.optDouble("load").isFinite()) Text(ui(R.string.ui_cpu_1_minute_load_average, status.optInt("cpu"), status.optDouble("load")))
        }
        SettingsActionPair(R.string.ui_start_collection, R.string.ui_check_status, active == null && !panel.busy,
            !panel.busy, { model.submit() }, { model.refresh() })
    }
    active?.let { job -> Tile {
        Text(phaseName(job.optString("phase")), style = MaterialTheme.typography.titleMedium)
        Text(stateName(job.optString("state")))
        DottedProgress(Modifier.fillMaxWidth())
        Text(job.getString("id"), style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { confirm(ui(R.string.ui_stop_this_job_the_provider_may)) { model.stop(job.getString("id")) } }) { Text(ui(R.string.ui_stop)) }
    } }

}

private fun profileIds(profiles: JSONArray) = profiles.objects().mapNotNull { it.optJSONObject("definition")?.optString("id") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsForm(panel: Panel, model: ManagerModel, page: String, dirtyChange: (Boolean) -> Unit) {
    if (page == "schedule") {
        ScheduleForm(panel, model, dirtyChange)
        return
    }
    val stored = panel.config.optJSONObject("config") ?: return
    val serverId = panel.server?.id ?: return
    val schema = panel.schema.optJSONObject("config") ?: return
    val storedProfiles = panel.config.getJSONArray("profiles")
    when (page) {
        "digest" -> {
            key(panel.config.optInt("revision")) {
                DigestRulesForm(panel, model, stored, storedProfiles, dirtyChange)
            }
            return
        }
        "secrets" -> {
            SecretsSettings(panel, model, stored, storedProfiles, dirtyChange)
            return
        }
        "profiles" -> {
            ProfileManager(panel, model, dirtyChange)
            return
        }
    }
    if (page == "ai") {
        AiModelForm(panel, model, stored, storedProfiles, schema, profileIds(storedProfiles), dirtyChange)
        return
    }
    var working by remember(serverId, page, panel.config.optInt("revision")) { mutableStateOf(stored.copyObject()) }
    val ids = profileIds(storedProfiles)
    val fields = schema.getJSONObject("properties")
    val dirty = working.toString() != stored.toString()
    SideEffect { dirtyChange(dirty) }
    SettingsSaveAction(dirty, R.string.ui_save_settings, !panel.busy) { model.saveConfig(working, storedProfiles) }
    if (page == "advanced") {
        AdvancedSettings(working, schema, ids) { name, value -> working = working.copyObject().put(name, value) }
        return
    }
    Tile {
        val names = if (page == "delivery") listOf("email", "webhook") else listOf(page)
        for (name in names) {
            Text(label(name), style = MaterialTheme.typography.titleLarge)
            key(name) {
                EditValue(schema, fields.getJSONObject(name), name, working.opt(name),
                    { working = working.copyObject().put(name, it) }, ids, showHeader = false)
            }
        }
    }
}
