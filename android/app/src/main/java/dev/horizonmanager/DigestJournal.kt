package dev.horizonmanager

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val digestPhases = listOf("fetch", "score", "filter", "enrich", "generate", "save", "delivery", "media")
private val digestTerminalEvents = mapOf(
    "JOB_SUCCESS" to "success", "JOB_FAILED" to "failed", "JOB_TIMEOUT" to "timeout",
    "JOB_CANCELLED" to "cancelled", "JOB_INTERRUPTED" to "interrupted", "INTERRUPTED_BY_RESTART" to "interrupted",
)

internal sealed interface JournalEntry {
    val time: String

    data class Event(val row: JSONObject) : JournalEntry {
        override val time: String get() = row.optString("time")
    }

    data class Digest(
        val id: String,
        override val time: String,
        val events: List<JSONObject>,
        val status: DigestJournalStatus,
        val phase: String?,
        val progress: Float,
        val tokens: Pair<Long, Long>?,
    ) : JournalEntry
}

internal enum class DigestJournalStatus { Queued, Preparing, Completed, Incomplete }

internal fun groupJournalEvents(
    events: List<JSONObject>, matching: List<JSONObject>, jobs: List<JSONObject>, archive: List<JSONObject>,
): List<JournalEntry> {
    val digestJobs = jobs.filter { it.optString("kind") == "run" }.associateBy { it.optString("id") }
    val knownDigests = archive.associateBy { it.optString("id") }
    val digestIds = digestJobs.keys + knownDigests.keys + events.mapNotNull { row ->
        val queuedRun = row.optString("code") == "JOB_QUEUED" && row.optString("detail") == "run"
        row.optString("job").takeIf { it.isNotBlank() && (queuedRun || row.optString("job_kind") == "run") }
    }
    val allByJob = events.filter { it.optString("job") in digestIds }.groupBy { it.optString("job") }
    val matchingByJob = matching.filter { it.optString("job") in digestIds }.groupBy { it.optString("job") }
    val standalone = matching.filter { it.optString("job") !in digestIds }.map { JournalEntry.Event(it) }
    val digests = matchingByJob.map { (id, visible) ->
        val history = allByJob.getValue(id)
        val job = digestJobs[id]
        val terminal = history.firstOrNull { it.optString("code") in digestTerminalEvents }
        val state = terminal?.let { digestTerminalEvents[it.optString("code")] } ?: job?.optString("state")
        val latestPhase = history.firstNotNullOfOrNull { row -> row.optString("code").takeIf { it in digestPhases } }
        val latestStep = latestPhase?.let(digestPhases::indexOf) ?: -1
        val phase = job?.optString("phase")?.takeIf { digestPhases.indexOf(it) > latestStep }
            ?: latestPhase
        val formed = terminal?.takeIf { it.has("digest_ready") }?.optBoolean("digest_ready")
            ?: job?.optJSONObject("result")?.let { (it.optJSONArray("languages")?.length() ?: 0) > 0 }
            ?: (id in knownDigests || history.any { it.optString("code") in setOf("save", "MEDIA_READY") })
        val status = when (state) {
            "success" -> if (formed) DigestJournalStatus.Completed else DigestJournalStatus.Incomplete
            "failed", "timeout", "cancelled", "interrupted" -> DigestJournalStatus.Incomplete
            "queued" -> DigestJournalStatus.Queued
            "running" -> DigestJournalStatus.Preparing
            else -> when {
                id in knownDigests -> DigestJournalStatus.Completed
                history.any { it.optString("code") == "STOP_REQUESTED" } -> DigestJournalStatus.Incomplete
                phase != null || history.any { it.optString("code") == "JOB_STARTED" } -> DigestJournalStatus.Preparing
                else -> DigestJournalStatus.Queued
            }
        }
        val start = history.firstNotNullOfOrNull { row -> row.optString("job_created").takeIf(String::isNotBlank) }
            ?: job?.optString("created")?.takeIf(String::isNotBlank) ?: history.last().optString("time")
        val fill = when (status) {
            DigestJournalStatus.Completed, DigestJournalStatus.Incomplete -> 1f
            DigestJournalStatus.Queued -> 0f
            DigestJournalStatus.Preparing -> ((phase?.let(digestPhases::indexOf) ?: -1) + 2f) / (digestPhases.size + 2f)
        }
        val tokens = terminal?.takeIf { status == DigestJournalStatus.Completed }
            ?.let { jobTokenCounts(it.optString("detail")) }
        JournalEntry.Digest(id, start, visible.asReversed(), status, phase, fill, tokens)
    }
    return (standalone + digests).sortedByDescending { parseJournalTime(it.time) }
}

private fun parseJournalTime(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

@Composable
internal fun DigestJournalHeader(digest: JournalEntry.Digest, expanded: Boolean, toggle: () -> Unit) {
    val locale = appLocale()
    val date = parseJournalTime(digest.time)?.atZone(ZoneId.systemDefault())?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(it)
    }.orEmpty()
    val title = if (date.isBlank()) ui(R.string.ui_digest) else ui(R.string.journal_digest_title, date)
    val status = when (digest.status) {
        DigestJournalStatus.Queued -> ui(R.string.ui_waiting)
        DigestJournalStatus.Preparing -> digest.phase?.let(::phaseName) ?: ui(R.string.journal_digest_started)
        DigestJournalStatus.Completed -> ui(R.string.journal_digest_ready)
        DigestJournalStatus.Incomplete -> ui(R.string.journal_digest_incomplete)
    }
    val expansion = ui(if (expanded) R.string.journal_digest_expanded else R.string.journal_digest_collapsed)
    val progress = animateFloatAsState(digest.progress, tween(480), label = "Digest preparation")
    val arrow = animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "Digest disclosure")
    val washPhase = rememberNoticeWashPhase()
    val palette = if (digest.status == DigestJournalStatus.Incomplete) NoticeWash.Disconnected else NoticeWash.Connected
    Box(Modifier.fillMaxWidth().glassSurface(feedGlassShape, raised = true)
        .semantics { stateDescription = "$status · $expansion" }
        .clickable(role = Role.Button, onClick = toggle)) {
        Box(Modifier.matchParentSize().digestWashClip(progress, filled = false)
            .savedNoticeWash(NoticeWash.Saved, washPhase))
        Box(Modifier.matchParentSize().digestWashClip(progress, filled = true)
            .savedNoticeWash(palette, washPhase))
        if (digest.status == DigestJournalStatus.Preparing) Canvas(Modifier.matchParentSize()) {
            if (progress.value > 0f && progress.value < 1f) {
                val edge = progressBoundary(size.width, progress.value, layoutDirection)
                val top = Offset(edge, 0f)
                val bottom = Offset(edge, size.height)
                drawLine(Color.Black.copy(alpha = .30f), top, bottom, 3.dp.toPx())
                drawLine(Color.White.copy(alpha = .84f), top, bottom, 1.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                val summary = when {
                    digest.tokens != null -> {
                        val number = NumberFormat.getIntegerInstance(locale)
                        ui(R.string.journal_job_tokens, number.format(digest.tokens.first), number.format(digest.tokens.second))
                    }
                    digest.status == DigestJournalStatus.Completed -> ui(R.string.journal_job_tokens_unavailable)
                    else -> status
                }
                Text(summary, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .9f))
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
                modifier = Modifier.rotate(arrow.value), tint = Color.White)
        }
    }
}

private fun Modifier.digestWashClip(progress: State<Float>, filled: Boolean): Modifier = drawWithContent {
    val startOnLeft = layoutDirection == LayoutDirection.Ltr
    val boundary = progressBoundary(size.width, progress.value, layoutDirection)
    if (filled == startOnLeft) clipRect(right = boundary) { this@drawWithContent.drawContent() }
    else clipRect(left = boundary) { this@drawWithContent.drawContent() }
}

private fun progressBoundary(width: Float, progress: Float, direction: LayoutDirection): Float =
    width * (if (direction == LayoutDirection.Ltr) progress else 1f - progress)
