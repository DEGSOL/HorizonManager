package dev.horizonmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

private val labels = mapOf(
    "ai" to "AI", "sources" to R.string.ui_sources, "processing" to R.string.ui_processing, "collection" to R.string.ui_collection,
    "digest" to R.string.ui_digest, "display" to R.string.ui_display_options, "extractors" to R.string.ui_content_extraction,
    "email" to R.string.ui_email, "webhook" to "Webhook", "wechat" to "WeChat",
    "provider" to R.string.ui_provider, "provider_chain" to R.string.ui_provider_chain,
    "model" to R.string.ui_model_id, "base_url" to R.string.ui_api_address_base_url, "api_key_env" to R.string.ui_api_key_variable,
    "temperature" to R.string.ui_temperature, "max_tokens" to R.string.ui_maximum_tokens,
    "throttle_sec" to R.string.ui_pause_between_requests_sec, "analysis_concurrency" to R.string.ui_scoring_concurrency,
    "enrichment_concurrency" to R.string.ui_enrichment_concurrency, "languages" to R.string.ui_digest_languages,
    "azure_endpoint_env" to R.string.ui_secret_with_azure_address, "api_version" to R.string.ui_api_version,
    "extra_body" to R.string.ui_extra_request_parameters,
    "rss" to "RSS / Atom", "hackernews" to "Hacker News", "google_news" to "Google News",
    "telegram" to "Telegram", "reddit" to "Reddit", "github" to "GitHub", "twitter" to "X / Twitter",
    "gdelt" to "GDELT", "ossinsight" to "OSS Insight", "openbb" to "OpenBB",
    "name" to R.string.ui_name, "url" to R.string.ui_address, "enabled" to R.string.ui_enabled, "category" to R.string.ui_category,
    "profile" to R.string.ui_processing_profile, "content_extractor" to R.string.ui_content_extraction,
    "type" to R.string.ui_type, "query" to R.string.ui_search_query, "language" to R.string.ui_language, "country" to R.string.ui_country,
    "max_results" to R.string.ui_maximum_results, "username" to R.string.ui_username, "repo" to R.string.ui_repository,
    "owner" to R.string.ui_owner, "channel" to R.string.ui_public_channel, "channels" to R.string.ui_channels,
    "subreddits" to R.string.ui_subreddits, "subreddit" to R.string.ui_subreddit, "users" to R.string.ui_users,
    "watchlists" to R.string.ui_watchlists, "symbols" to R.string.ui_ticker_symbols, "mode" to R.string.ui_mode,
    "time_filter" to R.string.ui_time_range, "fetch_limit" to R.string.ui_item_limit, "keywords" to R.string.ui_keywords,
    "fetch_comments" to R.string.ui_number_of_comments, "min_score" to R.string.ui_minimum_rating,
    "fetch_top_stories" to R.string.ui_number_of_top_stories, "max_records" to R.string.ui_maximum_records,
    "max_items" to R.string.ui_maximum_items, "time_window_hours" to R.string.ui_collection_window_hours,
    "group_by" to R.string.ui_grouping, "profile_order" to R.string.ui_profile_order, "category_groups" to R.string.ui_group_quotas,
    "favor_precision" to R.string.advanced_precision, "favor_recall" to R.string.advanced_recall,
    "only_main_content" to R.string.advanced_only_content, "trafilatura" to "Trafilatura",
    "firecrawl" to "Firecrawl", "outcrawl" to "Outcrawl", "specstream" to "SpecStream",
    "default_group" to R.string.ui_default_group_name, "default_group_limit" to R.string.ui_default_group_limit,
    "categories" to R.string.ui_categories_or_profiles, "limit" to R.string.ui_limit,
    "threshold" to R.string.ui_importance_threshold, "topic_dedup" to R.string.ui_topic_deduplication,
    "default_profile" to R.string.ui_default_profile, "profile_settings" to R.string.ui_profile_settings,
    "definition" to R.string.ui_profile_definition, "id" to R.string.ui_id, "enrichment" to R.string.ui_enrichment,
    "content" to R.string.ui_text_preparation, "blocks" to R.string.ui_output_blocks,
    "tools" to R.string.ui_tools, "display_names" to R.string.ui_names_by_language,
    "primary" to R.string.ui_primary_block, "optional" to R.string.ui_optional,
    "match_prompt" to R.string.ui_profile_selection_prompt, "analysis_prompt" to R.string.ui_scoring_criteria_and_prompt,
    "enrichment_prompt" to R.string.ui_enrichment_prompt,
    "analysis_max_chars" to R.string.ui_text_limit_for_scoring, "enrichment_max_chars" to R.string.ui_text_limit_for_enrichment,
    "sampling" to R.string.ui_text_sampling, "password_env" to R.string.ui_password_secret_name,
    "url_env" to R.string.ui_address_secret_name, "smtp_server" to R.string.ui_smtp_server, "imap_server" to R.string.ui_imap_server,
    "email_address" to R.string.ui_sender_address, "apify_token_env" to R.string.ui_apify_token_variable,
    "sort" to R.string.ui_sort, "delivery" to R.string.ui_delivery_mode, "platform" to R.string.ui_platform,
    "headers" to R.string.ui_http_headers_secret_variables_supported, "request_body" to R.string.ui_request_template,
    "auto" to R.string.ui_automatic, "en" to R.string.ui_english, "zh" to R.string.ui_simplified_chinese,
)

internal fun label(value: String) = when (val label = labels[value]) {
    is Int -> ui(label)
    is String -> label
    else -> value.replace('_', ' ')
}
internal fun validValue(value: Any?) = if (value == JSONObject.NULL) null else value

private val fixedPaths = setOf("profiles_dir", "chatgpt_account", "cookie_dir", "cookie_file_pattern")
internal fun definition(root: JSONObject, schema: JSONObject): JSONObject {
    val ref = schema.optString("\$ref")
    return if (ref.startsWith("#/\$defs/")) root.optJSONObject("\$defs")?.optJSONObject(ref.substringAfterLast('/')) ?: schema else schema
}

private fun inferred(value: Any?): JSONObject = JSONObject().put("type", when (value) {
    is JSONObject -> "object"
    is JSONArray -> "array"
    is Boolean -> "boolean"
    is Int, is Long -> "integer"
    is Number -> "number"
    else -> "string"
})

private fun alternatives(root: JSONObject, schema: JSONObject): List<JSONObject> =
    (schema.optJSONArray("anyOf") ?: schema.optJSONArray("oneOf"))?.objects()?.map { definition(root, it) }
        ?.filterNot { it.optString("type") == "null" }.orEmpty()

private fun variantId(schema: JSONObject): String =
    schema.optJSONObject("properties")?.optJSONObject("type")?.optString("const")
        ?.takeIf(String::isNotBlank) ?: schema.optString("type")

internal fun sample(root: JSONObject, raw: JSONObject, required: Boolean = false): Any {
    val schema = definition(root, raw)
    if (schema.has("default") && (!required || !schema.isNull("default"))) return schema.get("default")
    schema.optJSONArray("enum")?.let { if (it.length() > 0) return it.get(0) }
    if (schema.has("const")) return schema.get("const")
    val variants = alternatives(root, schema)
    if (schema.has("oneOf") && variants.isNotEmpty()) return sample(root, variants.first(), required = true)
    if (variants.isNotEmpty()) return if (required) sample(root, variants.first(), true) else JSONObject.NULL
    return when (schema.optString("type")) {
        "boolean" -> false
        "integer", "number" -> schema.opt("minimum") ?: 0
        "array" -> JSONArray()
        "object" -> JSONObject().apply {
            val properties = schema.optJSONObject("properties") ?: return@apply
            val necessary = schema.optJSONArray("required")?.strings().orEmpty()
            for (field in properties.keys()) {
                val child = properties.getJSONObject(field)
                if (field in necessary || child.has("default") || child.has("const"))
                    put(field, sample(root, child, field in necessary))
            }
        }
        else -> ""
    }
}

@Composable
internal fun Choice(
    title: String, entries: List<String>, current: String, change: (String) -> Unit,
    labelOf: (String) -> String = ::label, enabled: Boolean = true,
    row: Boolean = false,
    help: Int? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = if (current.isBlank() && "" !in entries) ui(R.string.ui_select) else labelOf(current)
    Column {
        if (row) GlassSettingRow(title, selected, enabled = enabled, help = help) { expanded = true }
        else {
            if (help == null) Text(title, style = MaterialTheme.typography.labelMedium)
            else SettingHeading(title, help)
            TextButton(onClick = { expanded = true }, enabled = enabled) { Text(selected) }
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            entries.forEach { value ->
                DropdownMenuItem(text = { Text(labelOf(value)) }, onClick = { change(value); expanded = false })
            }
        }
    }
}

@Composable
internal fun ValueField(title: String, text: String, set: (String) -> Unit, numeric: Boolean = false,
                        multiline: Boolean = false, help: Int? = null) {
    OutlinedTextField(value = text, onValueChange = set, label = { Text(title) },
        modifier = Modifier.fillMaxWidth(), singleLine = !multiline, colors = horizonFieldColors(),
        trailingIcon = if (help != null) ({ SettingHelp(title, help) }) else null,
        minLines = if (multiline) 3 else 1, maxLines = if (multiline) 10 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
internal fun EditValue(
    root: JSONObject,
    raw: JSONObject,
    field: String,
    value: Any?,
    onChange: (Any) -> Unit,
    profileIds: List<String>,
    title: String = label(field),
    expandedByDefault: Boolean = false,
    showHeader: Boolean = true,
) {
    if (field in fixedPaths) return
    val schema = definition(root, raw)
    val variants = alternatives(root, schema)
    val typed = if (variants.isNotEmpty()) {
        val kind = if (schema.has("oneOf")) (value as? JSONObject)?.optString("type") else inferred(value).optString("type")
        variants.firstOrNull { variantId(it) == kind } ?: variants.first()
    } else if (!schema.has("type") && !schema.has("enum")) inferred(value) else schema
    val type = typed.optString("type")
    val current = validValue(value)
    val help = when (field) {
        "time_window_hours" -> R.string.setting_help_collection
        "max_items" -> R.string.setting_help_issue_limit
        "min_score", "threshold" -> R.string.setting_help_score
        "max_tokens" -> R.string.setting_help_tokens
        "analysis_concurrency", "enrichment_concurrency" -> R.string.setting_help_concurrency
        "topic_dedup" -> R.string.setting_help_dedup
        "api_key_env" -> R.string.setting_help_secrets
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (current == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange(sample(root, typed, true)) }) { Text(ui(R.string.ui_set, title)) }
                if (help != null) SettingHelp(title, help)
            }
        } else {
            if (variants.size > 1) {
                val extractor = schema.has("oneOf") && schema.has("discriminator")
                Choice(if (extractor) ui(R.string.advanced_extractor_provider) else ui(R.string.ui_format, title),
                    variants.map(::variantId), variantId(typed),
                    { desired ->
                        val selected = variants.first { variantId(it) == desired }
                        onChange(when {
                            field == "profile" && desired == "string" -> "auto"
                            field == "profile" && desired == "array" && profileIds.isNotEmpty() -> JSONArray(listOf(profileIds.first()))
                            else -> sample(root, selected, true)
                        })
                    },
                    { mapOf("string" to ui(R.string.ui_text), "array" to ui(R.string.ui_list), "object" to ui(R.string.ui_fields))[it] ?: label(it) },
                    row = extractor)
            }
            val direct = when (field) {
                "profile", "default_profile" -> if (type == "string") (if (field == "profile") listOf("auto") else emptyList()) + profileIds else emptyList()
                "type" -> if (typed.optJSONArray("enum") == null && typed.optString("title") == "Type" && title == ui(R.string.ui_type)) emptyList() else emptyList()
                "group_by" -> listOf("category", "profile")
                "languages_item" -> listOf("en", "zh")
                "tools_item" -> listOf("history_search", "web_search")
                "profile_item", "profile_order_item" -> profileIds
                else -> typed.optJSONArray("enum")?.strings().orEmpty()
            }
            when {
                direct.isNotEmpty() -> Choice(title, direct, current.toString(), { onChange(it) }, row = true, help = help)
                typed.has("const") -> Text("$title · ${typed.get("const")}", style = MaterialTheme.typography.labelMedium)
                type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(title, modifier = Modifier.weight(1f))
                    if (help != null) SettingHelp(title, help)
                    Switch(checked = current == true, onCheckedChange = { onChange(it) })
                }
                type == "object" -> {
                    var shown by remember { mutableStateOf(expandedByDefault) }
                    if (showHeader) TextButton(onClick = { shown = !shown }) { Text(if (shown) ui(R.string.ui_collapse, title) else title) }
                    Reveal(!showHeader || shown) {
                        val obj = current as? JSONObject ?: JSONObject()
                        val known = typed.optJSONObject("properties")
                        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (known != null) for (name in known.keys()) key(name) {
                                EditValue(root, known.getJSONObject(name), name, obj.opt(name) ?: sample(root, known.getJSONObject(name)),
                                    { updated -> onChange(obj.copyObject().put(name, updated)) }, profileIds)
                            }
                            else {
                                val extra = typed.optJSONObject("additionalProperties")
                                for (name in obj.keys()) key(name) {
                                    EditValue(root, extra ?: inferred(obj.opt(name)), name, obj.opt(name),
                                        { onChange(obj.copyObject().put(name, it)) }, profileIds)
                                    TextButton(onClick = { onChange(obj.copyObject().apply { remove(name) }) }) { Text(ui(R.string.ui_remove_8ec40d, name)) }
                                }
                                var keyText by remember { mutableStateOf("") }
                                var valueType by remember { mutableStateOf("string") }
                                ValueField(ui(if (field == "extractors") R.string.advanced_extractor_name else R.string.ui_field_name),
                                    keyText, { keyText = it })
                                if (extra == null) Choice(ui(R.string.ui_value_type), listOf("string", "boolean", "number", "object", "array"), valueType, { valueType = it })
                                TextButton(enabled = keyText.isNotBlank() && !obj.has(keyText), onClick = {
                                    onChange(obj.copyObject().put(keyText, sample(root, extra ?: JSONObject().put("type", valueType), true)))
                                    keyText = ""
                                }) { Text(ui(if (field == "extractors") R.string.advanced_extractor_add else R.string.ui_add_field)) }
                            }
                        }
                    }
                }
                type == "array" -> {
                    var shown by remember { mutableStateOf(expandedByDefault) }
                    val array = current as? JSONArray ?: JSONArray()
                    val entry = typed.optJSONObject("items")
                    TextButton(onClick = { shown = !shown }) { Text("$title · ${array.length()}${if (shown) " ▴" else " ▾"}") }
                    Reveal(shown) {
                        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            val selectedProfiles = if (field == "profile") array.strings() else emptyList()
                            for (index in 0 until array.length()) {
                                val item = array.get(index)
                                key(index) {
                                    val options = if (field == "profile")
                                        profileIds.filter { it == item || it !in selectedProfiles } else profileIds
                                    EditValue(root, entry ?: inferred(item), field + "_item", item,
                                        { new -> onChange(JSONArray(array.toString()).put(index, new)) }, options,
                                        (item as? JSONObject)?.optString("name")?.ifBlank { null }
                                            ?: (item as? JSONObject)?.optString("channel")?.ifBlank { null } ?: "${index + 1}. $title")
                                    Row {
                                        TextButton(enabled = field != "profile" || array.length() > 1,
                                            onClick = { onChange(JSONArray(array.toString()).apply { remove(index) }) }) { Text(ui(R.string.ui_remove)) }
                                        TextButton(enabled = index > 0, onClick = {
                                            onChange(JSONArray(array.toString()).put(index, array.get(index - 1)).put(index - 1, array.get(index)))
                                        }) { Text(ui(R.string.ui_move_up)) }
                                    }
                                }
                            }
                            val nextProfile = profileIds.firstOrNull { it !in selectedProfiles }
                            TextButton(enabled = field != "profile" || nextProfile != null, onClick = {
                                val new = if (field == "profile") requireNotNull(nextProfile)
                                    else sample(root, entry ?: JSONObject().put("type", "string"), true)
                                onChange(JSONArray(array.toString()).put(new))
                            }) { Text(ui(R.string.ui_add_559a87)) }
                        }
                    }
                }
                type in listOf("integer", "number") -> {
                    var text by remember { mutableStateOf(current.toString()) }
                    ValueField(title, text, { next ->
                        text = next
                        if (type == "integer") next.toLongOrNull()?.let(onChange)
                        else next.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let(onChange)
                    }, numeric = true, help = help)
                }
                else -> ValueField(title, current.toString(), onChange,
                    multiline = field.endsWith("_prompt") || field in listOf("headers", "request_body"), help = help)
            }
            if (schema.optJSONArray("anyOf")?.objects()?.any { it.optString("type") == "null" } == true) {
                TextButton(onClick = { onChange(JSONObject.NULL) }) { Text(ui(R.string.ui_reset, title)) }
            }
        }
    }
}
