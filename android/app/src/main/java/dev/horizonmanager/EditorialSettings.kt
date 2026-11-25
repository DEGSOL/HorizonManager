package dev.horizonmanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun SettingsExpandable(title: Int, description: Int, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Tile {
        GlassSettingRow(ui(title), ui(description)) { expanded = !expanded }
        Reveal(expanded, content)
    }
}

@Composable
private fun CountField(title: Int, value: String, update: (String) -> Unit, valid: Boolean,
                       hint: Int? = null, help: Int? = null) {
    OutlinedTextField(value, { update(it.filter(Char::isDigit).take(9)) }, modifier = Modifier.fillMaxWidth(),
        label = { Text(ui(title)) }, singleLine = true, isError = !valid,
        supportingText = if (hint == null) null else ({ Text(ui(hint)) }),
        trailingIcon = if (help == null) null else ({ SettingHelp(ui(title), help) }),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = horizonFieldColors(), shape = horizonFieldShape())
}

@Composable
internal fun DigestRulesForm(panel: Panel, model: ManagerModel, base: JSONObject,
                             profiles: JSONArray, dirtyChange: (Boolean) -> Unit) {
    val original = base.optJSONObject("digest") ?: JSONObject()
    val collection = base.optJSONObject("collection") ?: JSONObject()
    val revision = panel.config.optInt("revision")
    val savedHeadline = panel.status.optJSONObject("headline_range")
    val savedMinimum = savedHeadline?.optString("minimum").orEmpty()
    val savedMaximum = savedHeadline?.optString("maximum").orEmpty()
    val serverId = panel.server?.id
    var headlineMinimum by rememberSaveable(serverId, savedMinimum, savedMaximum) { mutableStateOf(savedMinimum) }
    var headlineMaximum by rememberSaveable(serverId, savedMinimum, savedMaximum) { mutableStateOf(savedMaximum) }
    val headlineDirty = savedHeadline != null && (headlineMinimum != savedMinimum || headlineMaximum != savedMaximum)
    val minimumCharacters = headlineMinimum.toIntOrNull()
    val maximumCharacters = headlineMaximum.toIntOrNull()
    val validMinimum = minimumCharacters != null && minimumCharacters in 1..240
    val validMaximum = maximumCharacters != null && minimumCharacters != null && maximumCharacters in minimumCharacters..240
    var hours by rememberSaveable(revision) { mutableStateOf(collection.optInt("time_window_hours", 24).toString()) }
    var maximum by rememberSaveable(revision) { mutableStateOf(articleField(original, "max_items")) }
    var draft by rememberSaveable(revision) { mutableStateOf(original.toString()) }
    val digest = remember(draft) { JSONObject(draft) }
    val groupBy = digest.optString("group_by", "category")
    val groups = digest.optJSONObject("category_groups") ?: JSONObject()
    val ids = profiles.objects().map { it.getJSONObject("definition").getString("id") }
    val names = profiles.objects().associate { it.getJSONObject("definition").let { d -> d.getString("id") to d.getString("name") } }
    val correctHours = (hours.toIntOrNull() ?: 0) in 1..8760
    val correctMaximum = maximum.isBlank() || (maximum.toIntOrNull() ?: 0) > 0
    val defaultLimit = digest.opt("default_group_limit")
    val validDefaultLimit = defaultLimit == null || defaultLimit == JSONObject.NULL || digest.optInt("default_group_limit") > 0
    val validRoutes = groupBy != "profile" || groups.keys().asSequence().all {
        groups.getJSONObject(it).optJSONArray("categories")?.strings()?.all(ids::contains) == true
    }
    val order = digest.optJSONArray("profile_order")?.strings().orEmpty()
    val validOrder = order.distinct().size == order.size && order.all(ids::contains)
    val dirty = hours != collection.optInt("time_window_hours", 24).toString() ||
        maximum != articleField(original, "max_items") || draft != original.toString()
    SideEffect { dirtyChange(dirty || headlineDirty) }
    fun change(name: String, value: Any) { draft = digest.copyObject().put(name, value).toString() }

    Tile {
        Text(ui(R.string.digest_basic_title), style = MaterialTheme.typography.titleMedium)
        CountField(R.string.ui_collection_window_hours, hours, { hours = it }, correctHours,
            R.string.digest_window_hint, R.string.setting_help_collection)
        CountField(R.string.ui_maximum_items, maximum, { maximum = it }, correctMaximum,
            R.string.digest_max_hint, R.string.setting_help_issue_limit)
    }
    Tile {
        Text(ui(R.string.hovq_headline_range), style = MaterialTheme.typography.titleMedium)
        if (savedHeadline == null) {
            Text(ui(R.string.hovq_headline_upgrade), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(ui(R.string.hovq_headline_help), style = MaterialTheme.typography.bodySmall)
            CountField(R.string.hovq_headline_min, headlineMinimum, { headlineMinimum = it }, validMinimum)
            CountField(R.string.hovq_headline_max, headlineMaximum, { headlineMaximum = it }, validMaximum)
            if (headlineDirty) {
                Button(enabled = !panel.busy && validMinimum && validMaximum, onClick = {
                    if (minimumCharacters != null && maximumCharacters != null)
                        model.setHeadlineRange(minimumCharacters, maximumCharacters)
                }) {
                    Text(ui(R.string.hovq_headline_save))
                }
            }
        }
    }
    SettingsExpandable(R.string.digest_groups_title, R.string.digest_groups_summary) {
        Choice(ui(R.string.ui_grouping), listOf("category", "profile"), groupBy, { change("group_by", it) }, {
            ui(if (it == "profile") R.string.digest_group_profile else R.string.digest_group_category)
        }, row = true, help = R.string.digest_groups_help)
        QuotaGroups(groups, groupBy, names) { change("category_groups", it) }
        if (!validRoutes) Text(ui(R.string.digest_group_invalid), color = MaterialTheme.colorScheme.error)
        val currentDefault = digest.optString("default_group", "other")
        Choice(ui(R.string.ui_default_group_name), (listOf(currentDefault, "other") + groups.keys().asSequence().toList()).distinct(),
            currentDefault, { change("default_group", it) }, { id ->
                groups.optJSONObject(id)?.let { articleField(it, "name") }.orEmpty().ifBlank {
                    if (id == "other") ui(R.string.digest_other_group) else id
                }
            }, row = true, help = R.string.digest_default_group_detail)
        CountField(R.string.ui_default_group_limit, articleField(digest, "default_group_limit"),
            { change("default_group_limit", it.toIntOrNull() ?: JSONObject.NULL) }, validDefaultLimit,
            R.string.digest_default_group_detail)
        if (!validDefaultLimit) Text(ui(R.string.digest_positive_limit), color = MaterialTheme.colorScheme.error)
    }
    SettingsExpandable(R.string.ui_profile_order, R.string.digest_order_detail) {
        order.forEachIndexed { index, id ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(names[id] ?: id, modifier = Modifier.weight(1f).padding(top = 12.dp))
                TextButton(enabled = index > 0, onClick = { change("profile_order", JSONArray(moveSource(order, index, index - 1))) }) {
                    Text(ui(R.string.ui_move_up))
                }
                TextButton(onClick = { change("profile_order", JSONArray(order.filterIndexed { i, _ -> i != index })) }) {
                    Text(ui(R.string.ui_remove))
                }
            }
        }
        val available = ids.filterNot(order::contains)
        if (available.isNotEmpty()) Choice(ui(R.string.digest_order_add), available, "",
            { id -> change("profile_order", JSONArray(order + id)) }, { names[it] ?: it }, row = true)
    }
    if (dirty) Tile {
        SettingNote(ui(R.string.editorial_unsaved))
        Button(enabled = !panel.busy && correctHours && correctMaximum && validDefaultLimit && validRoutes && validOrder, onClick = {
            val updated = base.copyObject()
                .put("collection", collection.copyObject().put("time_window_hours", hours.toInt()))
                .put("digest", digest.copyObject().put("max_items", maximum.toIntOrNull() ?: JSONObject.NULL))
            model.saveConfig(updated, profiles)
        }) { Text(ui(R.string.ui_save_settings)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuotaGroups(groups: JSONObject, groupBy: String, profiles: Map<String, String>,
                        change: (JSONObject) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    for (id in groups.keys()) {
        val group = groups.getJSONObject(id)
        GlassSettingRow(articleField(group, "name").ifBlank { id }, ui(R.string.digest_group_count, group.optInt("limit"))) {
            editing = id
        }
    }
    TextButton(onClick = { editing = "" }) { Text(ui(R.string.digest_group_add)) }
    editing?.let { previous -> key(previous) {
        val original = groups.optJSONObject(previous)
        var name by rememberSaveable { mutableStateOf(original?.let { articleField(it, "name") }.orEmpty().ifBlank { previous }) }
        var limit by rememberSaveable { mutableStateOf(original?.optInt("limit")?.toString() ?: "5") }
        var categories by rememberSaveable { mutableStateOf(original?.optJSONArray("categories")?.strings()?.joinToString("\n").orEmpty()) }
        val members = categories.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
        val validLimit = (limit.toIntOrNull() ?: 0) > 0
        Text(ui(if (original != null) R.string.digest_groups_title else R.string.digest_group_add),
            style = MaterialTheme.typography.titleSmall)
        ValueField(ui(R.string.ui_name), name, { name = it })
        CountField(R.string.ui_limit, limit, { limit = it }, validLimit, R.string.digest_positive_limit)
        if (groupBy == "profile") {
            Text(ui(R.string.ui_processing_profiles))
            FlowRow {
                (profiles.keys + members).forEach { id ->
                    FilterChip(id in members, {
                        categories = (if (id in members) members - id else members + id).joinToString("\n")
                    }, { Text(profiles[id] ?: id) })
                }
            }
        } else {
            Text(ui(R.string.digest_categories_detail))
            ValueField(ui(R.string.ui_categories_or_profiles), categories, { categories = it }, multiline = true)
        }
        FlowRow {
            Button(enabled = name.isNotBlank() && validLimit && members.isNotEmpty() &&
                (groupBy != "profile" || members.all(profiles::containsKey)), onClick = {
                val id = previous.ifBlank { "group-" + java.util.UUID.randomUUID().toString().take(12) }
                val entry = (original?.copyObject() ?: JSONObject()).put("name", name.trim())
                    .put("limit", limit.toInt()).put("categories", JSONArray(members))
                change(groups.copyObject().put(id, entry))
                editing = null
            }) { Text(ui(R.string.editorial_done)) }
            TextButton(onClick = { editing = null }) { Text(ui(R.string.ui_cancel)) }
            if (original != null) TextButton(onClick = {
                change(groups.copyObject().apply { remove(previous) })
                editing = null
            }) { Text(ui(R.string.ui_remove)) }
        }
    } }
}

@Composable
internal fun AdvancedSettings(config: JSONObject, schema: JSONObject, ids: List<String>,
                              change: (String, Any) -> Unit) {
    val properties = schema.getJSONObject("properties")
    SettingsExpandable(R.string.ui_content_extraction, R.string.advanced_extract_summary) {
        Text(ui(R.string.advanced_extract_detail))
        EditValue(schema, properties.getJSONObject("extractors"), "extractors", config.opt("extractors"),
            { change("extractors", it) }, ids, showHeader = false)
    }
    SettingsExpandable(R.string.advanced_terminal_title, R.string.advanced_terminal_detail) {
        val display = config.getJSONObject("display")
        Choice(ui(R.string.advanced_icon_style), listOf("emoji", "nerd", "ascii"), display.optString("icon_style", "emoji"),
            { change("display", display.copyObject().put("icon_style", it)) }, {
                ui(when (it) {
                    "nerd" -> R.string.advanced_icons_nerd
                    "ascii" -> R.string.advanced_icons_ascii
                    else -> R.string.advanced_icons_emoji
                })
            }, row = true)
    }
}

@Composable
internal fun SecretsSettings(panel: Panel, model: ManagerModel, base: JSONObject, profiles: JSONArray,
                             dirtyChange: (Boolean) -> Unit) {
    val revision = panel.config.optInt("revision")
    val active = articleField(base.getJSONObject("ai"), "api_key_env")
    val saved = panel.config.optJSONArray("secrets")?.strings().orEmpty()
    val choices = (listOf(active) + panel.schema.optJSONArray("secrets")?.strings().orEmpty() + saved)
        .filter(String::isNotBlank).distinct()
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    var submitted by remember { mutableIntStateOf(-1) }
    fun close() { editing = null; value = ""; submitted = -1 }
    LaunchedEffect(revision) { if (submitted != -1 && submitted != revision) close() }
    SideEffect { dirtyChange(value.isNotEmpty()) }
    Tile {
        SettingHeading(ui(R.string.secrets_title), R.string.setting_help_secrets)
        Text(ui(R.string.secrets_intro))
        if (active.isNotBlank()) Text(ui(R.string.secrets_model_key, active), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = !panel.busy, onClick = {
            close()
            editing = ""
            name = choices.firstOrNull { it !in saved } ?: choices.firstOrNull().orEmpty()
        }) { Text(ui(R.string.secrets_add)) }
    }
    Tile {
        Text(ui(R.string.secrets_saved), style = MaterialTheme.typography.titleMedium)
        if (saved.isEmpty()) Text(ui(R.string.secrets_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (key in saved.sortedByDescending { it == active }) {
            GlassSettingRow(key, ui(if (key == active) R.string.secrets_current_model else R.string.secrets_saved_value),
                enabled = !panel.busy) { close(); name = key; editing = key }
        }
    }
    if (editing != null) {
        val valid = name in choices || name.matches(Regex("HM_SECRET_[A-Z0-9_]{1,48}"))
        Tile {
            Text(ui(if (editing.orEmpty().isBlank()) R.string.secrets_add else R.string.secrets_update),
                style = MaterialTheme.typography.titleMedium)
            if (editing.orEmpty().isBlank()) {
                Choice(ui(R.string.ui_known_variables), choices, name, { name = it }, { it }, row = true)
                ValueField(ui(R.string.ui_variable_name), name, { name = it.trim() }, help = R.string.secrets_custom_help)
                if (name.isNotEmpty() && !valid) Text(ui(R.string.secrets_custom_help), color = MaterialTheme.colorScheme.error)
            } else Text(name, style = MaterialTheme.typography.titleSmall)
            PasswordField(ui(R.string.ui_new_value), value) { value = it }
            Button(enabled = valid && value.isNotBlank() && value.length <= 8192 && !panel.busy, onClick = {
                submitted = revision
                model.saveConfig(base, profiles, JSONObject().put(name, value))
            }) { Text(ui(R.string.ui_save_secret)) }
            TextButton(enabled = !panel.busy, onClick = { close() }) { Text(ui(R.string.ui_cancel)) }
            if (name in saved) TextButton(enabled = !panel.busy, onClick = { removing = true }) {
                Text(ui(R.string.ui_delete_secret))
            }
        }
    }
    if (removing) HorizonAlertDialog(onDismissRequest = { removing = false }, title = { Text(ui(R.string.ui_delete, name)) },
        text = { Text(ui(R.string.ui_sources_apis_or_delivery_that_rely)) },
        confirmButton = { Button(onClick = {
            removing = false
            submitted = revision
            model.saveConfig(base, profiles, JSONObject().put(name, JSONObject.NULL))
        }) { Text(ui(R.string.ui_delete_86ea33)) } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text(ui(R.string.ui_cancel)) } })
}
