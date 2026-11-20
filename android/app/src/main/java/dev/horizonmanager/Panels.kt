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
    var working by remember(panel.config.optInt("revision")) { mutableStateOf(original.copyObject()) }
    var type by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("rss") }
    val dirty = working.toString() != original.toString()
    SideEffect { dirtyChange(dirty) }
    val types = panel.schema.optJSONArray("sources")?.strings().orEmpty()
    val sourceSchema = definition(sourceRoot, sourceRoot.getJSONObject("properties").getJSONObject("sources")).getJSONObject("properties")
    Tile {
        Text(ui(R.string.ui_horizon_sources), style = MaterialTheme.typography.titleLarge)
        Choice(ui(R.string.ui_integration), types, type, { type = it })
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
        Reveal(dirty) {
            Text(ui(R.string.editorial_unsaved), color = LocalHorizonPalette.current.warning)
            FlowRow {
                Button(onClick = { model.saveConfig(config.copyObject().put("sources", working), profiles) },
                    enabled = !panel.busy) { Text(ui(R.string.ui_apply_source_settings)) }
                TextButton(onClick = { working = original.copyObject() }, enabled = !panel.busy) { Text(ui(R.string.ui_discard_changes)) }
            }
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
    "generate" to ui(R.string.ui_generating_digest), "save" to ui(R.string.ui_saving), "delivery" to ui(R.string.ui_delivery))[value] ?: stateName(value)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Control(panel: Panel, model: ManagerModel, confirm: (String, () -> Unit) -> Unit,
                     configure: (String) -> Unit) {
    val status = panel.status
    ServerConnectionStatus(panel, model)
    val active = panel.jobs.firstOrNull {
        it.optString("kind") != "article_context" && it.optString("state") in setOf("queued", "running")
    }
    val latest = panel.jobs.firstOrNull { it.optString("kind") == "run" && it.optString("state") == "success" }
    val issue = panel.events.firstOrNull { it.optString("level") in listOf("ERROR", "CRITICAL") }
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
            Text(ui(R.string.ui_next_run, localEpoch(status.optDouble("next"))))
        }
        latest?.let { Text(ui(R.string.ui_last_success_517d5d, localTime(it.optString("updated")))) }
        issue?.let { Text(ui(R.string.ui_last_error, errorText(it.optString("code"))), color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.submit() }, enabled = active == null && !panel.busy) { Text(ui(R.string.ui_start_collection)) }
            OutlinedButton(onClick = { model.refresh() }) { Text(ui(R.string.ui_check_status)) }
        }
    }
    active?.let { job -> Tile {
        Text(phaseName(job.optString("phase")), style = MaterialTheme.typography.titleMedium)
        Text(stateName(job.optString("state")))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(job.getString("id"), style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { confirm(ui(R.string.ui_stop_this_job_the_provider_may)) { model.stop(job.getString("id")) } }) { Text(ui(R.string.ui_stop)) }
    } }
    GlassSettingRow(ui(R.string.ui_schedule), ui(R.string.ui_days_intervals_and_time_zone)) { configure("schedule") }
    if (panel.jobs.isNotEmpty()) Tile {
        Text(ui(R.string.ui_job_history), style = MaterialTheme.typography.titleMedium)
        panel.jobs.take(25).forEach { job ->
            val kind = when (job.optString("kind")) { "ai" -> ui(R.string.ui_ai_test); "source" -> ui(R.string.ui_source_test); "localize" -> ui(R.string.ui_translation); "article_context" -> ui(R.string.article_context_heading); else -> ui(R.string.ui_digest) }
            Text("$kind · ${stateName(job.optString("state"))} · ${localTime(job.optString("created"))}")
            Text(job.optString("id"), style = MaterialTheme.typography.labelSmall)
            job.optJSONObject("result")?.let { result ->
                val counts = listOf("raw" to ui(R.string.ui_collected), "scored" to ui(R.string.ui_scored), "kept" to ui(R.string.ui_in_issue), "enriched" to ui(R.string.ui_enriched))
                    .mapNotNull { (key, title) -> if (result.has(key)) "$title ${result.getInt(key)}" else null }
                if (counts.isNotEmpty()) Text(counts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            if (active == null && job.optString("kind") in setOf("ai", "run") && job.optString("state") in setOf("failed", "timeout", "interrupted")) {
                TextButton(onClick = { model.submit(job.optString("kind")) }) { Text(ui(R.string.ui_retry)) }
            }
            HorizontalDivider()
        }
    }
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
    var working by remember(page, panel.config.optInt("revision")) { mutableStateOf(stored.copyObject()) }
    var profiles by remember(page, panel.config.optInt("revision")) { mutableStateOf(JSONArray(storedProfiles.toString())) }
    val ids = profileIds(profiles)
    val fields = schema.getJSONObject("properties")
    val aiDraft = working.optJSONObject("ai")
    val secretAlias = aiDraft?.optString("api_key_env").orEmpty()
    val secrets = panel.config.optJSONArray("secrets")?.strings().orEmpty()
    val configuredSecret = secretAlias in secrets
    var genericKey by remember(secretAlias, aiDraft?.optString("provider"), panel.config.optInt("revision")) { mutableStateOf("") }
    var showGenericKey by remember(secretAlias, panel.config.optInt("revision")) { mutableStateOf(!configuredSecret) }
    val dirty = working.toString() != stored.toString() || profiles.toString() != storedProfiles.toString() || genericKey.isNotBlank()
    if (page != "profiles") SideEffect { dirtyChange(dirty) }
    if (page == "profiles") {
        ProfileManager(panel, model, dirtyChange)
        return
    }
    Tile {
        when (page) {
            "secrets" -> {
                val secretNames = panel.schema.optJSONArray("secrets")?.strings().orEmpty()
                val configured = panel.config.optJSONArray("secrets")?.strings().orEmpty()
                val providerName = stored.getJSONObject("ai").optString("api_key_env")
                var name by remember { mutableStateOf(providerName.ifBlank { "OPENAI_API_KEY" }) }
                var value by remember { mutableStateOf("") }
                var remove by remember { mutableStateOf(false) }
                Text(ui(R.string.ui_secrets_on_the_vps), style = MaterialTheme.typography.titleLarge)
                Choice(ui(R.string.ui_known_variables), (secretNames + configured).distinct(), name, { name = it }, { it })
                ValueField(ui(R.string.ui_variable_name), name, { name = it })
                if (name in configured) Text(ui(R.string.ui_value_saved))
                PasswordField(ui(R.string.ui_new_value), value) { value = it }
                Button(enabled = value.isNotBlank() && name.isNotBlank() && !panel.busy, onClick = {
                    model.saveConfig(stored, storedProfiles, JSONObject().put(name, value))
                    value = ""
                }) { Text(ui(R.string.ui_save_secret)) }
                if (name in configured) TextButton(onClick = { remove = true }) { Text(ui(R.string.ui_delete_secret)) }
                if (remove) HorizonAlertDialog(onDismissRequest = { remove = false }, title = { Text(ui(R.string.ui_delete, name)) },
                    text = { Text(ui(R.string.ui_sources_apis_or_delivery_that_rely)) },
                    confirmButton = { Button(onClick = { remove = false; model.saveConfig(stored, storedProfiles, JSONObject().put(name, JSONObject.NULL)) }) { Text(ui(R.string.ui_delete_86ea33)) } },
                    dismissButton = { TextButton(onClick = { remove = false }) { Text(ui(R.string.ui_cancel)) } })
            }
            "ai" -> {
                val ai = working.getJSONObject("ai")
                val router = AiRouter.configured(ai)
                val provider = router?.id ?: ai.optString("provider")
                val providers = panel.schema.getJSONObject("providers")
                val supported = AiRouter.entries.map(AiRouter::id) +
                    providers.keys().asSequence().filter { it != "openai-chatgpt" }.toList()
                fun replace(value: JSONObject) { working = working.copyObject().put("ai", value) }
                Text(ui(R.string.ui_ai_provider), style = MaterialTheme.typography.titleLarge)
                Choice(ui(R.string.ui_provider), supported, provider, { change ->
                    if (change != provider) {
                        val preset = AiRouter.entries.find { it.id == change }
                        if (preset != null) replace(preset.defaults(ai))
                        else {
                            val defaults = providers.getJSONObject(change)
                            replace(ai.copyObject().put("provider", change).put("model", "")
                                .put("api_key_env", defaults.optString("api_key_env")).put("base_url", defaults.opt("base_url") ?: JSONObject.NULL)
                                .put("azure_endpoint_env", defaults.opt("azure_endpoint_env") ?: JSONObject.NULL)
                                .put("api_version", defaults.opt("api_version") ?: JSONObject.NULL))
                        }
                    }
                }, { value ->
                    AiRouter.entries.find { it.id == value }?.title
                        ?: if (value == "openai") ui(R.string.ui_openai_compatible_api) else value
                })
                if (router == null) {
                    ValueField(ui(R.string.ui_model_id), ai.optString("model"), { replace(ai.copyObject().put("model", it)) })
                    ValueField(ui(R.string.ui_compatible_api_address_optional), ai.optString("base_url").takeUnless { it == "null" }.orEmpty(), {
                        replace(ai.copyObject().put("base_url", it.ifBlank { null } ?: JSONObject.NULL))
                    })
                    if (provider != "ollama") {
                        if (configuredSecret) {
                            Text(ui(R.string.ui_key_saved_on_the_vps), style = MaterialTheme.typography.bodyMedium)
                            if (!showGenericKey) TextButton(onClick = { showGenericKey = true }) {
                                Text(ui(R.string.ui_replace_api_key))
                            }
                        }
                        Reveal(showGenericKey) {
                            PasswordField(ui(R.string.ui_api_key, provider), genericKey) { genericKey = it }
                        }
                    }
                    Text(ui(R.string.ui_enter_the_model_id_from_your))
                    val advanced = definition(schema, fields.getJSONObject("ai")).copyObject()
                    val properties = advanced.getJSONObject("properties")
                    listOf("provider", "model", "base_url", "languages").forEach { properties.remove(it) }
                    EditValue(schema, advanced, "ai", ai,
                        { working = working.copyObject().put("ai", it) }, ids, ui(R.string.ui_advanced_ai_options))
                } else {
                    val savedAlias = router.secretAlias(stored.getJSONObject("ai"))
                    val knownSecrets = panel.config.optJSONArray("secrets")?.strings().orEmpty()
                    val alias = savedAlias.takeIf { it in knownSecrets } ?: router.secretName
                    val hasKey = alias in knownSecrets
                    key(serverId, router, panel.config.optInt("revision")) {
                        RouterSettings(
                            router, ai.optString("model"), hasKey, panel.busy,
                            { key -> model.loadRouterCatalog(serverId, router, key) },
                            { id -> replace(ai.copyObject().put("model", id)) },
                        ) { newKey ->
                            val edited = ai.copyObject().put("api_key_env", alias)
                            val secret = JSONObject()
                            if (newKey != null) secret.put(alias, newKey)
                            model.saveConfig(working.copyObject().put("ai", edited), profiles, secret)
                        }
                    }
                    val advanced = definition(schema, fields.getJSONObject("ai")).copyObject()
                    val properties = advanced.getJSONObject("properties")
                    listOf("provider", "model", "api_key_env", "base_url", "azure_endpoint_env", "api_version", "provider_chain", "languages")
                        .forEach { properties.remove(it) }
                    EditValue(schema, advanced, "ai", ai,
                        { working = working.copyObject().put("ai", it) }, ids, ui(R.string.ui_advanced_ai_options))
                }
                val savedAi = stored.getJSONObject("ai")
                val canTest = savedAi.optString("model").isNotBlank() && (savedAi.optString("provider") == "ollama" ||
                    savedAi.optString("api_key_env") in panel.config.optJSONArray("secrets")?.strings().orEmpty())
                Text(ui(R.string.ui_the_reading_language_follows_android_new))
                TextButton(onClick = { model.submit("ai") }, enabled = !panel.busy && canTest) { Text(ui(R.string.ui_test_saved_model)) }
            }
            else -> {
                val names = when (page) {
                    "delivery" -> listOf("email", "webhook")
                    "advanced" -> listOf("extractors", "display")
                    "digest" -> listOf("collection", "digest")
                    else -> listOf(page)
                }
                for (name in names) {
                    Text(label(name), style = MaterialTheme.typography.titleLarge)
                    key(name) {
                        EditValue(schema, fields.getJSONObject(name), name, working.opt(name),
                            { working = working.copyObject().put(name, it) }, ids, showHeader = false)
                    }
                }
            }
        }
        Reveal(dirty && page != "secrets" &&
            (page != "ai" || AiRouter.configured(working.getJSONObject("ai")) == null)) {
            Text(ui(R.string.editorial_unsaved), color = LocalHorizonPalette.current.warning)
            Button(onClick = {
                val keys = JSONObject()
                if (page == "ai" && genericKey.isNotBlank() && aiDraft?.optString("provider") != "ollama")
                    keys.put(secretAlias, genericKey.trim())
                model.saveConfig(working, profiles, keys)
            }, enabled = !panel.busy) { Text(ui(R.string.ui_save_settings)) }
        }
    }
}
