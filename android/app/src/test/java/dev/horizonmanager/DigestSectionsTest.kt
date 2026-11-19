package dev.horizonmanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun issue(id: String, ready: String): JSONObject =
        JSONObject().put("id", id).put("created", "2026-11-18T00:00:00Z").put("ready_at", ready)
}
