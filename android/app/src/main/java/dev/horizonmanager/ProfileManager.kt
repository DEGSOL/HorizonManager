package dev.horizonmanager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject

private const val summaryInstructionLimit = 12_000

private fun validInstruction(value: String): Boolean =
    value.isNotBlank() && '\u0000' !in value && value.codePointCount(0, value.length) <= summaryInstructionLimit

private fun profileNewsText(profile: JSONObject, defaultInstruction: String) = JSONObject()
    .put("summary_mode", profile.optString("summary_mode", "news"))
    .put("summary_instruction", profile.optString("summary_instruction", defaultInstruction))

private fun withoutNewsText(profile: JSONObject) = profile.copyObject().apply {
    remove("summary_mode")
    remove("summary_instruction")
}

internal fun changedProfileNewsText(original: JSONArray, edited: JSONArray, defaultInstruction: String): JSONObject? {
    if (original.length() != edited.length()) return null
    val changes = JSONObject()
    for (index in 0 until original.length()) {
        val before = original.getJSONObject(index)
        val after = edited.getJSONObject(index)
        if (withoutNewsText(before).toString() != withoutNewsText(after).toString()) return null
        val options = profileNewsText(after, defaultInstruction)
        if (options.toString() != profileNewsText(before, defaultInstruction).toString()) {
            changes.put(after.getJSONObject("definition").getString("id"), options)
        }
    }
    return changes.takeIf { it.length() != 0 }
}

@Composable
internal fun ProfileManager(panel: Panel, model: ManagerModel, dirtyChange: (Boolean) -> Unit) {
    val root = panel.schema.getJSONObject("profile")
    val base = panel.config.getJSONObject("config")
    val revision = panel.config.getInt("revision")
    val originals = panel.config.getJSONArray("profiles")
    val defaultInstruction = root.getJSONObject("properties").getJSONObject("summary_instruction").getString("default")
    val (draftState, renamesState, processingState) = key(revision) {
        Triple(
            rememberSaveable { mutableStateOf(originals.toString()) },
            rememberSaveable { mutableStateOf("[]") },
            rememberSaveable { mutableStateOf(base.getJSONObject("processing").toString()) },
        )
    }
    var draft by draftState
    var profileRenames by renamesState
    var processing by processingState
    val references = remember(base, profileRenames) {
        JSONArray(profileRenames).objects().fold(base) { config, rename ->
            renameProfileReferences(config, rename.getString("from"), rename.getString("to"))
        }
    }
    val profiles = remember(draft) { JSONArray(draft).objects() }
    val settings = remember(processing) { JSONObject(processing) }
    val ids = profiles.map { it.getJSONObject("definition").getString("id") }
    val active = settings.optString("default_profile")
    var editing by rememberSaveable { mutableStateOf("") }
    var remove by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val index = ids.indexOf(editing)
    val dirty =
        profileRenames != "[]" ||
            draft != originals.toString() ||
            processing != base.getJSONObject("processing").toString()
    SideEffect { dirtyChange(dirty) }
    // The toolbar and system back share the inner editor's history.
    val inner = LocalEditorBack.current
    DisposableEffect(index, inner) {
        if (index >= 0) inner?.value = { editing = "" }
        onDispose { inner?.value = null }
    }
    BackHandler(index >= 0) { editing = "" }
    fun add(template: JSONObject, duplicate: Boolean) {
        val doc = template.copyObject()
        val id = "profile-" + java.util.UUID.randomUUID().toString().take(12)
        val def = doc.getJSONObject("definition")
        def.put("id", id)
            .put(
                "name",
                if (duplicate) ui(R.string.ui_copy_a40f6e, def.optString("name"))
                else ui(R.string.mr_new_profile),
            )
        def.put("display_names", JSONObject())
        if (!duplicate) {
            doc.remove("summary_instruction")
            doc.put("summary_mode", "news")
        }
        draft = JSONArray(profiles).put(doc).toString()
        editing = id
    }
    if (index < 0) {
        ProfileGuide()
        val sorted =
            profiles.sortedByDescending { it.getJSONObject("definition").optString("id") == active }
        for (doc in sorted) {
            val def = doc.getJSONObject("definition")
            val id = def.getString("id")
            val summary = profilePurpose(id, doc.optString("match_prompt"))
            if (id == active)
                Tile(
                    Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        editing = id
                    },
                    accented = true,
                ) {
                    Text(
                        ui(R.string.mr_default_profile),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(def.optString("name"), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            else GlassSettingRow(def.optString("name"), summary) { editing = id }
        }
        val template = profiles.getOrNull(ids.indexOf(active)) ?: profiles.firstOrNull()
        if (template != null) {
            SettingsActionPair(R.string.mr_new_profile, R.string.ui_advanced_settings, !panel.busy, true,
                { add(template, false) }, { advanced = !advanced })
        } else {
            TextButton(onClick = { advanced = !advanced }) { Text(ui(R.string.ui_advanced_settings)) }
        }
        if (advanced) {
            val schema = panel.schema.getJSONObject("config")
            EditValue(
                schema,
                schema.getJSONObject("properties").getJSONObject("processing"),
                "processing",
                settings,
                { processing = it.toString() },
                ids,
                showHeader = false,
            )
        }
    } else {
        val doc = profiles[index]
        val def = doc.getJSONObject("definition")
        val docProps = root.getJSONObject("properties")
        val textFields = mapOf(
            "summary_instruction" to R.string.profile_summary_instruction,
            "match_prompt" to R.string.ui_profile_selection_prompt,
            "analysis_prompt" to R.string.ui_scoring_criteria_and_prompt,
            "enrichment_prompt" to R.string.ui_enrichment_prompt,
        )
        var promptEditor by rememberSaveable(editing) { mutableStateOf("") }
        fun update(changed: JSONObject) {
            draft = JSONArray(profiles).put(index, changed).toString()
        }
        Text(def.optString("name"), style = MaterialTheme.typography.headlineMedium)
        ValueField(
            ui(R.string.mr_name),
            def.optString("name"),
            { update(doc.copyObject().put("definition", def.copyObject().put("name", it))) },
        )
        if (editing != active)
            OutlinedButton(
                onClick = {
                    processing = settings.copyObject().put("default_profile", editing).toString()
                }
            ) {
                Text(ui(R.string.mr_set_default))
            }
        else Text(ui(R.string.mr_default_profile), color = HorizonPalette.muted)
        val allRules = settings.optJSONObject("profile_settings") ?: JSONObject()
        val rules = allRules.optJSONObject(editing) ?: JSONObject()
        fun rule(name: String, value: Any) {
            processing = settings.copyObject().put("profile_settings",
                allRules.copyObject().put(editing, rules.copyObject().put(name, value))).toString()
        }
        Tile {
            SettingHeading(ui(R.string.profile_rules_title), R.string.profile_guide_score_body)
            val threshold = rules.optDouble("threshold").takeIf(Double::isFinite)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ui(R.string.ui_importance_threshold), Modifier.weight(1f))
                Switch(threshold != null, onCheckedChange = { rule("threshold", if (it) 6.0 else JSONObject.NULL) })
            }
            if (threshold != null) {
                Text(ui(R.string.profile_score_min_value, threshold))
                Slider(threshold.toFloat(), { rule("threshold", it.toDouble()) }, valueRange = 0f..10f, steps = 19)
            } else Text(ui(R.string.profile_score_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ui(R.string.ui_topic_deduplication), Modifier.weight(1f))
                SettingHelp(ui(R.string.ui_topic_deduplication), R.string.profile_guide_duplicates_body)
                Switch(rules.optBoolean("topic_dedup", true), onCheckedChange = { rule("topic_dedup", it) })
            }
        }
        var section by rememberSaveable(editing) { mutableStateOf("") }
        val defProps =
            definition(root, docProps.getJSONObject("definition")).getJSONObject("properties")
        for ((key, title) in
            listOf("news" to R.string.profile_news_text, "prompts" to R.string.mr_prompts,
                "definition" to R.string.mr_structure)) {
            GlassSettingRow(ui(title), if (section == key) "−" else "+") {
                section = if (section == key) "" else key
            }
            if (section == key && key == "news") {
                ProfileNewsText(doc, defaultInstruction, { promptEditor = "summary_instruction" }, ::update)
            } else if (section == key)
                Tile {
                    if (key == "definition") {
                        var newId by rememberSaveable(editing) { mutableStateOf(editing) }
                        ValueField("ID", newId, { newId = it })
                        if (newId != editing)
                            TextButton(
                                enabled = newId.matches(Regex("[a-z][a-z0-9_-]*")) && newId !in ids,
                                onClick = {
                                    val renamed =
                                        renameProfileReferences(
                                            references.copyObject().put("processing", settings),
                                            editing,
                                            newId,
                                        )
                                    profileRenames = JSONArray(profileRenames)
                                        .put(JSONObject().put("from", editing).put("to", newId)).toString()
                                    processing = renamed.getJSONObject("processing").toString()
                                    update(
                                        doc.copyObject()
                                            .put("definition", def.copyObject().put("id", newId))
                                    )
                                    editing = newId
                                },
                            ) {
                                Text(ui(R.string.ui_save_settings))
                            }
                    }
                    val props = if (key == "definition") defProps else docProps
                    for (field in props.keys()) {
                        if (field in setOf("id", "name", "definition", "content", "summary_mode", "summary_instruction")) continue
                        if (key == "prompts") {
                            val promptTitle = textFields[field] ?: continue
                            ProfilePromptRow(ui(promptTitle), doc.optString(field)) { promptEditor = field }
                            continue
                        }
                        val obj = if (key == "definition") def else doc
                        EditValue(
                            root,
                            props.getJSONObject(field),
                            field,
                            obj.opt(field),
                            { value ->
                                val changed = obj.copyObject().put(field, value)
                                update(
                                    if (key == "definition")
                                        doc.copyObject().put("definition", changed)
                                    else changed
                                )
                            },
                            ids,
                        )
                    }
                }
        }
        if (promptEditor.isNotEmpty()) {
            val field = promptEditor
            val instruction = field == "summary_instruction"
            ProfilePromptDialog(ui(textFields.getValue(field)),
                doc.optString(field, if (instruction) defaultInstruction else ""), instruction,
                close = { promptEditor = "" }, save = {
                    update(doc.copyObject().put(field, it))
                    promptEditor = ""
                })
        }
        SettingsActionPair(R.string.ui_duplicate_and_edit, R.string.ui_remove, !panel.busy,
            editing != active && profiles.size > 1 && !panel.busy, { add(doc, true) }, { remove = true })
        if (remove)
            HorizonAlertDialog(
                onDismissRequest = { remove = false },
                title = { Text(ui(R.string.ui_delete, def.optString("name"))) },
                text = { Text(ui(R.string.mr_profile_delete)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            remove = false
                            draft =
                                JSONArray(profiles.filterIndexed { i, _ -> i != index }).toString()
                            editing = ""
                        }
                    ) {
                        Text(ui(R.string.ui_remove))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { remove = false }) { Text(ui(R.string.ui_cancel)) }
                },
            )
    }
    if (dirty)
        Tile {
            SettingNote(ui(R.string.editorial_unsaved))
            Button(
                enabled =
                    !panel.busy &&
                        profiles.all { profile ->
                            val def = profile.getJSONObject("definition")
                            def.optString("name").isNotBlank() &&
                                validInstruction(profile.optString("summary_instruction", defaultInstruction))
                        },
                onClick = {
                    val edited = JSONArray(draft)
                    val newsChanges = if (profileRenames == "[]" &&
                        processing == base.getJSONObject("processing").toString()
                    ) changedProfileNewsText(originals, edited, defaultInstruction) else null
                    if (newsChanges == null) {
                        model.saveConfig(references.copyObject().put("processing", settings), edited,
                            expectedRevision = revision)
                    } else model.saveProfileNewsText(newsChanges, revision)
                },
            ) {
                Text(ui(R.string.ui_save_settings))
            }
            TextButton(
                onClick = {
                    draft = originals.toString()
                    profileRenames = "[]"
                    processing = base.getJSONObject("processing").toString()
                }
            ) {
                Text(ui(R.string.ui_cancel))
            }
        }
}

@Composable
private fun ProfilePromptRow(title: String, value: String, help: Int? = null, open: () -> Unit) {
    GlassSettingRow(title, ui(R.string.profile_edit_text), help = help, open = open)
    Text(value, Modifier.padding(horizontal = 6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall, color = ReadingGlass.muted)
}

@Composable
private fun ProfileNewsText(doc: JSONObject, instructionDefault: String, editInstruction: () -> Unit,
                            update: (JSONObject) -> Unit) {
    val instruction = doc.optString("summary_instruction", instructionDefault)
    val mode = doc.optString("summary_mode", "news")

    Tile {
        Choice(ui(R.string.profile_news_format), listOf("news", "original"), mode,
            { update(doc.copyObject().put("summary_mode", it)) },
            { ui(if (it == "original") R.string.profile_news_short else R.string.profile_news_full) },
            row = true, help = R.string.profile_news_format_help)
        if (mode == "news") {
            ProfilePromptRow(ui(R.string.profile_summary_instruction), instruction,
                R.string.profile_instruction_help, editInstruction)
            OutlinedButton(onClick = { update(doc.copyObject().put("summary_instruction", instructionDefault)) },
                enabled = instruction != instructionDefault, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(ui(R.string.profile_restore_instruction))
            }
        }
    }
}

@Composable
private fun ProfilePromptDialog(title: String, original: String, instruction: Boolean, close: () -> Unit,
                                save: (String) -> Unit) {
    var draft by rememberSaveable(title, original) { mutableStateOf(original) }
    val valid = if (instruction) validInstruction(draft) else draft.isNotBlank() && draft.length <= 100_000
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        // Give a long prompt its own viewport so the settings page does not scroll it
        // again after the keyboard has already reduced the available height.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.BottomCenter) {
            val height = maxHeight.coerceAtMost(520.dp)
            Box(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                Tile(Modifier.height(height)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().weight(1f).focusRequester(focus)
                        .semantics { contentDescription = title },
                        colors = horizonFieldColors(), shape = horizonFieldShape(), isError = !valid)
                    if (!valid && instruction) SettingNote(ui(R.string.profile_instruction_range))
                    SettingsActionPair(R.string.ui_cancel, R.string.profile_apply_text, true, valid, close, { save(draft) })
                }
            }
        }
        LaunchedEffect(Unit) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
}

@Composable
private fun ProfileGuide() {
    SettingsExpandable(R.string.profile_guide_title, R.string.profile_guide_summary) {
        for ((title, description) in listOf(
            R.string.profile_guide_sources to R.string.profile_guide_sources_body,
            R.string.profile_guide_route to R.string.profile_guide_route_body,
            R.string.profile_guide_score to R.string.profile_guide_score_body,
            R.string.profile_guide_duplicates to R.string.profile_guide_duplicates_body,
            R.string.profile_guide_limits to R.string.profile_guide_limits_body,
            R.string.profile_guide_verify to R.string.profile_guide_verify_body,
        )) {
            Text(ui(title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text(ui(description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal val LocalEditorBack = compositionLocalOf<MutableState<(() -> Unit)?>?> { null }

private fun profilePurpose(id: String, prompt: String): String =
    when (id) {
        "ai-creator" -> ui(R.string.mr_creator)
        "tech-news" -> ui(R.string.mr_tech_news)
        "tech-blog" -> ui(R.string.mr_tech_blog)
        "finance-news" -> ui(R.string.mr_finance)
        else ->
            prompt
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                .orEmpty()
                .take(160)
    }

internal fun renameProfileReferences(config: JSONObject, old: String, new: String): JSONObject {
    val updated = config.copyObject()
    fun replaceList(values: JSONArray?) {
        if (values != null)
            for (i in 0 until values.length()) if (values.opt(i) == old) values.put(i, new)
    }
    fun walkSources(node: Any?) {
        when (node) {
            is JSONObject ->
                for (key in node.keys().asSequence().toList()) {
                    val item = node.opt(key)
                    if (key == "profile") {
                        if (item == old) node.put(key, new) else replaceList(item as? JSONArray)
                    } else walkSources(item)
                }
            is JSONArray -> for (i in 0 until node.length()) walkSources(node.opt(i))
        }
    }
    walkSources(updated.optJSONObject("sources"))
    val processing = updated.getJSONObject("processing")
    if (processing.optString("default_profile") == old) processing.put("default_profile", new)
    processing.optJSONObject("profile_settings")?.let {
        if (it.has(old)) it.put(new, it.remove(old))
    }
    updated.optJSONObject("digest")?.let { digest ->
        replaceList(digest.optJSONArray("profile_order"))
        if (digest.optString("group_by") == "profile")
            digest.optJSONObject("category_groups")?.let { groups ->
                for (group in groups.keys()) replaceList(
                    groups.optJSONObject(group)?.optJSONArray("categories")
                )
            }
    }
    return updated
}
