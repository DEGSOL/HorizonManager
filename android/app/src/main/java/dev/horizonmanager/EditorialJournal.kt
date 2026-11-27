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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

private val publicComponents = setOf("vps", "android", "agent", "horizon", "scheduler", "delivery", "push")
private val publicCode = Regex("[A-Z][A-Z0-9_]{1,79}")
private val publicJob = Regex("(?:[0-9a-f]{32}|run-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8})")
private val journalLevels = setOf("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL")
private val exitStatus = Regex("(?:preflight|packages|files|runtime|service|health) · exit [0-9]{1,3}")
private val sourceResult = Regex(".* · ([0-9]{1,9})(?: · (AUTH|RATE_LIMIT|HTTP_NOT_FOUND|TIMEOUT|INVALID_FORMAT|NETWORK|UPSTREAM_FAILURE))?")
private val jobTokenDetail = Regex("tokens ([0-9]{1,18}) ([0-9]{1,18})")
internal const val unavailableJobTokens = "tokens unavailable"

internal fun jobTokenCounts(detail: String): Pair<Long, Long>? =
    jobTokenDetail.matchEntire(detail)?.destructured?.let { (input, output) -> input.toLong() to output.toLong() }

internal fun journalEventDate(time: String, zone: ZoneId): LocalDate? =
    runCatching { Instant.parse(time).atZone(zone).toLocalDate() }.getOrNull()

private val contextResult = Regex("[0-9]{1,9} ms(?: · [A-Z][A-Z0-9_]{1,79})?")
private val safeException = setOf("TimeoutError", "ConnectionError", "ValueError", "RuntimeError", "OSError", "HTTPError")

private val traceFrame = Regex("(?:Problem|HostConfirmation|[A-Z][A-Za-z0-9]{0,60}(?:Exception|Error))(?: @ [A-Za-z0-9_.$-]{1,110}:-?[0-9]{1,5})?")

private fun isPublicConnectionTrace(value: String): Boolean {
    if (value.length !in 1..780) return false
    val frames = value.removePrefix("background · ").removePrefix("tcp/dns · ").removePrefix("ssh · ").split(" ← ")
    return frames.size in 1..5 && frames.all(traceFrame::matches)
}

internal fun diagnosticEvent(row: JSONObject): JSONObject {
    val code = row.optString("code").substringBefore(" · ")
        .takeIf { it in digestPhases || publicCode.matches(it) }.orEmpty()
    val raw = row.optString("detail")
    val detail = when {
        row.optString("component") == "android" && isConnectionFailure(code) -> raw.takeIf(::isPublicConnectionTrace)
        code.startsWith("INSTALL_") -> raw.takeIf(exitStatus::matches)
        code.startsWith("SOURCE_") -> sourceResult.matchEntire(raw)?.groupValues?.drop(1)?.filter(String::isNotBlank)?.joinToString(" · ")
        code == "JOB_QUEUED" -> raw.takeIf { it in setOf("run", "ai", "source", "localize", "article_context") }
        code == "JOB_SUCCESS" -> raw.takeIf { it == unavailableJobTokens || jobTokenDetail.matches(it) }
        code.startsWith("ARTICLE_CONTEXT_") -> raw.takeIf(contextResult::matches)
        code == "DIGEST_LANGUAGE_CHANGED" -> raw.takeIf { it in digestLanguages }
        code in setOf("LOCALIZATION_PARTIAL", "LOCALIZATION_RESPONSE_INVALID", "LOCALIZATION_RECOVERED",
            "LOCALIZATION_PROVIDER_TIMEOUT", "LOCALIZATION_PROVIDER_ERROR") -> raw.takeIf { it.length <= 9 && it.toIntOrNull() != null }
        code == "ENRICHMENT_MODE_CHANGED" -> raw.takeIf { it in setOf("on_demand", "automatic") }
        code in setOf("HORIZON_EXIT", "JOURNAL_CLEARED", "MEDIA_READY") -> raw.takeIf { it.length <= 9 && it.toIntOrNull() != null }
        else -> raw.takeIf { it in safeException || it == "src.ai.classifier · fallback" }
    }
    return JSONObject().apply {
        row.optLong("id", -1).takeIf { it >= 0 }?.let { put("id", it) }
        row.optString("time").takeIf { runCatching { Instant.parse(it) }.isSuccess }?.let { put("time", it) }
        row.optString("component").takeIf { it in publicComponents }?.let { put("component", it) }
        row.optString("level").takeIf { it in journalLevels }?.let { put("level", it) }
        row.optString("job").takeIf(publicJob::matches)?.let { put("job", it) }
        if (row.optString("job_kind") == "run") {
            put("job_kind", "run")
            row.optString("job_created").takeIf { runCatching { Instant.parse(it) }.isSuccess }
                ?.let { put("job_created", it) }
            (row.opt("digest_ready") as? Number)?.let { put("digest_ready", it.toInt() == 1) }
        }
        if (code.isNotBlank()) put("code", code)
        if (!detail.isNullOrBlank()) put("detail", detail)
    }
}

internal fun matchesEventLevel(filter: String, level: String) =
    filter.isBlank() || filter == level || filter == "ERROR" && level == "CRITICAL"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Journal(panel: Panel, confirm: (String, () -> Unit) -> Unit, model: ManagerModel, export: (String) -> Unit) {
    var level by rememberSaveable { mutableStateOf("") }
    var component by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var job by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable(panel.server?.id) { mutableStateOf(arrayListOf<String>()) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    val context = LocalContext.current
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
                    || journalMessage(row).let { (title, body) ->
                        title.contains(query.trim(), true) || body.contains(query.trim(), true)
                    })
        }
    }
    val zone = ZoneId.systemDefault()
    val locale = appLocale()
    val dayLabel = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale) }
    val days = remember(safeEvents, events, panel.jobs, panel.archive, zone) {
        groupJournalEvents(safeEvents, events, panel.jobs, panel.archive).groupBy { journalEventDate(it.time, zone) }
    }
    LazyColumn(Modifier.fillMaxSize().editorialSideBottomInsets(), state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("filters") {
            Tile {
                SettingHeading(ui(R.string.journal_activity_title), R.string.journal_activity_help)
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
                        component, { component = it }, ::journalComponent)
                    Choice(ui(R.string.ui_time_range), listOf("all", "day", "week"), period, { period = it }, {
                        ui(when (it) { "day" -> R.string.ui_last_24_hours; "week" -> R.string.ui_last_7_days; else -> R.string.ui_all_dates })
                    })
                }
                if (job.isNotEmpty()) TextButton(onClick = { job = "" }) { Text(ui(R.string.ui_clear_operation_filter, job)) }
                FlowRow {
                    TextButton(onClick = { model.refresh() }, enabled = !panel.busy) { Text(ui(R.string.ui_sync)) }
                    TextButton(enabled = events.isNotEmpty(), onClick = { export(JSONArray(events).toString(2)) }) { Text(ui(R.string.ui_export)) }
                    TextButton(enabled = panel.events.isNotEmpty() && !panel.busy, onClick = {
                        confirm(ui(R.string.ui_delete_downloaded_journal_entries_and_vps), model::clearEvents)
                    }) { Text(ui(R.string.ui_clear)) }
                }
                if (panel.syncing) DottedProgress(Modifier.fillMaxWidth())
                if (events.isEmpty() && !panel.syncing) Text(ui(R.string.ui_no_entries_match_these_filters))
            }
        }
        for ((day, entries) in days) {
            if (day != null) item("day-$day") {
                Text(dayLabel.format(day), Modifier.padding(top = 4.dp, start = 4.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleSmall)
            }
            for (entry in entries) when (entry) {
                is JournalEntry.Event -> item { JournalEventCard(entry.row) { detail = entry.row } }
                is JournalEntry.Digest -> {
                    val open = entry.id in expanded
                    item("digest-${entry.id}") {
                        DigestJournalHeader(entry, open) {
                            expanded = ArrayList(if (open) expanded - entry.id else expanded + entry.id)
                        }
                    }
                    if (open) items(entry.events, key = { "digest-${entry.id}-event-${it.optLong("id")}" }) { row ->
                        Box(Modifier.padding(horizontal = 10.dp)) { JournalEventCard(row) { detail = row } }
                    }
                }
            }
        }
    }
    detail?.let { row ->
        val code = row.optString("code")
        val (title, explanation) = journalMessage(row)
        HorizonAlertDialog(onDismissRequest = { detail = null }, title = { Text(title) },
            text = {
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(listOf(journalComponent(row.optString("component")), localTime(row.optString("time"))).joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Text(explanation)
                    val id = row.optString("job")
                    if (id.isNotBlank()) TextButton(onClick = { job = id; detail = null }) { Text(ui(R.string.ui_events_for_this_operation)) }
                    Text(ui(R.string.journal_technical), style = MaterialTheme.typography.labelLarge)
                    val technical = listOf(code, row.optString("detail"), id).filter(String::isNotBlank).joinToString("\n")
                    if (technical.isNotBlank()) Text(technical, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, confirmButton = {
                Button(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Horizon", row.toString(2)))
                }) { Text(ui(R.string.ui_copy)) }
            }, dismissButton = { TextButton(onClick = { detail = null }) { Text(ui(R.string.ui_close)) } })
    }
}

@Composable
private fun JournalEventCard(row: JSONObject, open: () -> Unit) {
    val importance = row.optString("level")
    val color = when (importance) {
        "ERROR", "CRITICAL" -> MaterialTheme.colorScheme.error
        "WARNING" -> Color(0xFF614400)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val (title, explanation) = journalMessage(row)
    Tile(Modifier.clickable(role = Role.Button, onClick = open)) {
        Text(listOf(journalLevel(importance), journalComponent(row.optString("component")), localTime(row.optString("time")))
            .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = color)
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
