package dev.horizonmanager

import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

internal const val DEFAULT_COLLECTION_HOURS = 24
internal const val DIGEST_PAGE_SIZE = 60

internal data class DigestSections(val feed: JSONObject?, val archive: List<JSONObject>)

internal fun digestTime(issue: JSONObject): String =
    issue.optString("ready_at").takeUnless { it.isBlank() || it == "null" } ?: issue.optString("created")

internal fun splitDigests(issues: List<JSONObject>, windowHours: Int, now: Instant,
                          manuallyArchived: Set<String> = emptySet(), hidden: Set<String> = emptySet()): DigestSections {
    val cutoff = now.minus(windowHours.coerceIn(1, 8760).toLong(), ChronoUnit.HOURS)
    val sorted = issues.mapNotNull { issue ->
        val timestamp = digestTimestamp(issue)
        timestamp?.let { it to issue }
    }.sortedByDescending { it.first }
    val feed = sorted.firstOrNull()?.takeIf { (ready, issue) ->
        val id = issue.optString("id")
        !ready.isBefore(cutoff) && id !in manuallyArchived && id !in hidden
    }?.second
    val feedId = feed?.optString("id")
    val archive = sorted.map { it.second }.filter { issue ->
        val id = issue.optString("id")
        id != feedId && id !in hidden
    }
    return DigestSections(feed, archive)
}

private fun digestTimestamp(issue: JSONObject): Instant? =
    (listOf(digestTime(issue), issue.optString("created"))).firstNotNullOfOrNull {
        runCatching { Instant.parse(it) }.getOrNull()
    }

internal fun Panel.feedIssue(issues: List<JSONObject> = archive, hours: Int = collectionHours): JSONObject? =
    splitDigests(issues, hours, Instant.now(), manuallyArchived, hiddenDigests).feed

internal fun digestIsPreparing(jobs: List<JSONObject>): Boolean = jobs.any { job ->
    job.optString("kind") == "run" &&
        job.optString("state") in setOf("queued", "running") &&
        job.optJSONObject("result")?.optString("ready_at").isNullOrBlank()
}
