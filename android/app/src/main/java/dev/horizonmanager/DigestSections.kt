package dev.horizonmanager

import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

internal const val DEFAULT_COLLECTION_HOURS = 24
internal const val DIGEST_PAGE_SIZE = 60

internal data class DigestSections(val feed: JSONObject?, val archive: List<JSONObject>)

internal fun digestTime(issue: JSONObject): String =
    issue.optString("ready_at").takeUnless { it.isBlank() || it == "null" } ?: issue.optString("created")

internal fun splitDigests(issues: List<JSONObject>, windowHours: Int, now: Instant): DigestSections {
    val cutoff = now.minus(windowHours.coerceIn(1, 8760).toLong(), ChronoUnit.HOURS)
    val sorted = issues.mapNotNull { issue ->
        val timestamp = runCatching { Instant.parse(digestTime(issue)) }.getOrNull()
        timestamp?.let { it to issue }
    }.sortedByDescending { it.first }
    val (archive, current) = sorted.partition { (ready, _) -> ready.isBefore(cutoff) }
    return DigestSections(current.firstOrNull()?.second, archive.map { it.second })
}
