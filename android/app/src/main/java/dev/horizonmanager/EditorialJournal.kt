package dev.horizonmanager

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

private val publicComponents = setOf("vps", "android", "agent", "horizon", "scheduler", "delivery", "push")
private val publicCode = Regex("[A-Z][A-Z0-9_]{1,79}")
private val publicJob = Regex("(?:[0-9a-f]{32}|run-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8})")
private val journalLevels = setOf("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL")
private val phases = setOf("fetch", "score", "filter", "enrich", "generate", "save", "delivery")
private val exitStatus = Regex("(?:preflight|packages|files|runtime|service|health) · exit [0-9]{1,3}")
private val sourceResult = Regex(".* · ([0-9]{1,9})(?: · (AUTH|RATE_LIMIT|HTTP_NOT_FOUND|TIMEOUT|INVALID_FORMAT|NETWORK|UPSTREAM_FAILURE))?")
private val safeException = setOf("TimeoutError", "ConnectionError", "ValueError", "RuntimeError", "OSError", "HTTPError")

internal fun diagnosticEvent(row: JSONObject): JSONObject {
    val code = row.optString("code").substringBefore(" · ")
        .takeIf { it in phases || publicCode.matches(it) }.orEmpty()
    val raw = row.optString("detail")
    val detail = when {
        code.startsWith("INSTALL_") -> raw.takeIf(exitStatus::matches)
        code.startsWith("SOURCE_") -> sourceResult.matchEntire(raw)?.groupValues?.drop(1)?.filter(String::isNotBlank)?.joinToString(" · ")
        code == "JOB_QUEUED" -> raw.takeIf { it in setOf("run", "ai", "source", "localize") }
        code in setOf("HORIZON_EXIT", "JOURNAL_CLEARED", "MEDIA_READY") -> raw.takeIf { it.length <= 9 && it.toIntOrNull() != null }
        else -> raw.takeIf { it in safeException || it == "src.ai.classifier · fallback" }
    }
    return JSONObject().apply {
        row.optLong("id", -1).takeIf { it >= 0 }?.let { put("id", it) }
        row.optString("time").takeIf { runCatching { Instant.parse(it) }.isSuccess }?.let { put("time", it) }
        row.optString("component").takeIf { it in publicComponents }?.let { put("component", it) }
        row.optString("level").takeIf { it in journalLevels }?.let { put("level", it) }
        row.optString("job").takeIf(publicJob::matches)?.let { put("job", it) }
        if (code.isNotBlank()) put("code", code)
        if (!detail.isNullOrBlank()) put("detail", detail)
    }
}

internal fun matchesEventLevel(filter: String, level: String) =
    filter.isBlank() || filter == level || filter == "ERROR" && level == "CRITICAL"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Journal(panel: Panel, confirm: (String, () -> Unit) -> Unit, model: ManagerModel, export: (String) -> Unit,
                     header: @Composable () -> Unit) {
    var level by rememberSaveable { mutableStateOf("") }
    var component by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var job by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    val context = LocalContext.current
    val theme = LocalHorizonPalette.current
    val safeEvents = remember(panel.events) { panel.events.map(::diagnosticEvent) }
    val components = remember(safeEvents) { safeEvents.map { it.optString("component") }.filter(String::isNotBlank).distinct() }
    val levels = listOf("" to R.string.ui_all, "ERROR" to R.string.editorial_errors, "WARNING" to R.string.editorial_warnings,
        "INFO" to R.string.editorial_information, "DEBUG" to R.string.editorial_debug)
    val events = remember(safeEvents, level, component, period, query, job) {
        val cutoff = when (period) {
            "day" -> Instant.now().minus(1, ChronoUnit.DAYS)
            "week" -> Instant.now().minus(7, ChronoUnit.DAYS)
            else -> Instant.EPOCH
        }
        safeEvents.filter { row ->
            matchesEventLevel(level, row.optString("level")) &&
                (component.isBlank() || row.optString("component") == component) &&
                (job.isBlank() || row.optString("job") == job) &&
                (period == "all" || runCatching { Instant.parse(row.optString("time")) >= cutoff }.getOrDefault(false)) &&
                (query.isBlank() || listOf("job", "code", "component", "detail").any { row.optString(it).contains(query.trim(), true) }
                    || errorText(row.optString("code")).contains(query.trim(), true))
        }
    }
    LazyColumn(Modifier.fillMaxSize().editorialInsets(), state = rememberLazyListState(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("heading") { header() }
        item("filters") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ValueField(ui(R.string.ui_search_events_or_ids), query, { query = it })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    levels.forEach { (key, title) ->
                        if (key != "DEBUG" || safeEvents.any { it.optString("level") == key }) {
                            FilterChip(level == key, { level = key }, { Text(ui(title)) })
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Choice(ui(R.string.ui_component), (listOf("") + components + component).distinct(),
                        component, { component = it }, { it.ifBlank { ui(R.string.ui_all) } })
                    Choice(ui(R.string.ui_time_range), listOf("all", "day", "week"), period, { period = it }, {
                        ui(when (it) { "day" -> R.string.ui_last_24_hours; "week" -> R.string.ui_last_7_days; else -> R.string.ui_all_dates })
                    })
                }
                if (job.isNotEmpty()) TextButton(onClick = { job = "" }) { Text(ui(R.string.ui_clear_operation_filter, job)) }
                FlowRow {
                    TextButton(onClick = { model.refresh() }, enabled = !panel.busy) { Text(ui(R.string.ui_sync)) }
                    TextButton(enabled = events.isNotEmpty(), onClick = { export(JSONArray(events).toString(2)) }) { Text(ui(R.string.ui_export)) }
                    TextButton(enabled = panel.events.isNotEmpty() && !panel.busy, onClick = {
                        val lastRemote = panel.events.maxOfOrNull { it.optLong("id") } ?: 0
                        confirm(ui(R.string.ui_delete_downloaded_journal_entries_and_vps)) { model.clearEvents(lastRemote) }
                    }) { Text(ui(R.string.ui_clear)) }
                }
                if (panel.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (events.isEmpty() && !panel.syncing) Text(ui(R.string.ui_no_entries_match_these_filters))
            }
        }
        items(events) { row ->
            val importance = row.optString("level")
            val color = when (importance) { "ERROR", "CRITICAL" -> theme.danger; "WARNING" -> theme.warning; else -> theme.muted }
            val code = row.optString("code")
            Tile(Modifier.clickable(role = Role.Button) { detail = row }) {
                Text(listOf(importance, row.optString("component"), localTime(row.optString("time")))
                    .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = color)
                Text(if (code in phases) phaseName(code) else errorText(code), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (code.isNotBlank()) Text(code, color = theme.tertiaryText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    detail?.let { row ->
        val code = row.optString("code")
        HorizonAlertDialog(onDismissRequest = { detail = null }, title = { Text(code.ifBlank { ui(R.string.journal) }) },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(localTime(row.optString("time")) + " · " + row.optString("component"))
                    Text(if (code in phases) phaseName(code) else errorText(code))
                    row.optString("detail").takeIf(String::isNotBlank)?.let { Text(it) }
                    val id = row.optString("job")
                    if (id.isNotBlank()) TextButton(onClick = { job = id; detail = null }) { Text(ui(R.string.ui_events_for_this_operation)) }
                }
            }, confirmButton = {
                Button(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Horizon", row.toString(2)))
                }) { Text(ui(R.string.ui_copy)) }
            }, dismissButton = { TextButton(onClick = { detail = null }) { Text(ui(R.string.ui_close)) } })
    }
}
