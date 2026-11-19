package dev.horizonmanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DigestSectionsTest {
    @Test
    fun feedShowsOnlyNewestAndArchiveUsesConfiguredCutoff() {
        val now = Instant.parse("2026-11-19T12:00:00Z")
        val recent = issue("newest", "2026-11-19T11:10:00Z")
        val boundary = issue("exact", "2026-11-19T06:00:00Z")
        val archived = issue("past", "2026-11-19T05:59:59Z")
        val older = issue("older", "2026-11-18T03:00:00Z")
        val all = listOf(boundary, older, archived, recent)
        val short = splitDigests(all, 6, now)
        assertEquals(recent, short.feed)
        assertEquals(listOf(archived, older), short.archive)
        assertEquals(boundary, splitDigests(listOf(boundary), 6, now).feed)
        assertEquals(listOf(older), splitDigests(all, 24, now).archive)
        assertNull(splitDigests(listOf(older), 24, now).feed)
    }

    @Test
    fun prefersReadyTimeAndStillReadsLegacyMetadata() {
        val legacy = JSONObject().put("created", "2026-11-18T23:00:00Z")
        val current = issue("new", "2026-11-19T11:30:00Z")
        val sections = splitDigests(listOf(legacy, current), 6, Instant.parse("2026-11-19T12:00:00Z"))
        assertEquals(current, sections.feed)
        assertEquals(listOf(legacy), sections.archive)
    }

    @Test
    fun manuallyArchivedIssueNeverWinsTheFeedSelection() {
        val now = Instant.parse("2026-11-19T12:00:00Z")
        val recent = issue("newest", "2026-11-19T11:10:00Z")
        val previous = issue("previous", "2026-11-19T10:00:00Z")
        val old = issue("old", "2026-11-18T11:00:00Z")
        val all = listOf(recent, previous, old)
        val first = splitDigests(all, 24, now, setOf("newest"))
        assertEquals(previous, first.feed)
        assertEquals(listOf(recent, old), first.archive)
        val second = splitDigests(all, 24, now, setOf("newest", "previous"))
        assertNull(second.feed)
        assertEquals(all, second.archive)
    }

    @Test
    fun preparingIndicatorTracksOnlyUnfinishedDigestRuns() {
        val run = JSONObject().put("kind", "run").put("state", "queued")
        assertTrue(digestIsPreparing(listOf(run)))
        assertTrue(digestIsPreparing(listOf(run.put("state", "running"))))
        assertFalse(digestIsPreparing(listOf(run.put("state", "failed"))))
        assertFalse(digestIsPreparing(listOf(run.put("state", "success"))))
        assertFalse(digestIsPreparing(listOf(run.put("state", "running")
            .put("result", JSONObject().put("ready_at", "2026-11-19T11:10:00Z")))))
        run.remove("result")
        assertFalse(digestIsPreparing(listOf(run.put("kind", "localize"))))
    }

    private fun issue(id: String, ready: String): JSONObject =
        JSONObject().put("id", id).put("created", "2026-11-18T00:00:00Z").put("ready_at", ready)
}
