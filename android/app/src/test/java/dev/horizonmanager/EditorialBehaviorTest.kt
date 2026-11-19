package dev.horizonmanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditorialBehaviorTest {
    @Test
    fun normalizesPublicTelegramHandlesAndRejectsInviteLinks() {
        for (input in listOf("  @myChannel ", "myChannel", "https://t.me/myChannel/")) {
            assertEquals("myChannel", normalizeTelegramChannel(input))
        }
        for (input in listOf("", "https://t.me/+secret", "https://t.me/joinchat/key", "https://evil.test/myChannel",
            "https://user:secret@t.me/myChannel", "https://t.me/myChannel/42", "my channel")) {
            assertNull(input, normalizeTelegramChannel(input))
        }
    }

    @Test
    fun movingChannelPreservesOtherEntries() {
        val channels = listOf("first", "second", "third")
        assertEquals(listOf("third", "first", "second"), moveSource(channels, 2, 0))
        assertEquals(listOf("second", "third", "first"), moveSource(channels, 0, 2))
        assertEquals(channels, moveSource(channels, 0, 3))
    }

    @Test
    fun sortsBothScoreDirectionsAndKeepsUnknownValuesLast() {
        val missing = JSONObject().put("id", "missing")
        val low = JSONObject().put("score", 2).put("time", "2026-11-19T18:00:00+05:00")
        val high = JSONObject().put("score", 9).put("time", "2026-11-19T15:00:00Z")
        val entries = listOf(missing, low, high)
        assertEquals(listOf(high, low, missing), orderArticles(entries, NewsOrder.Score))
        assertEquals(listOf(low, high, missing), orderArticles(entries, NewsOrder.ScoreLow))
        assertEquals(listOf(high, low, missing), orderArticles(entries, NewsOrder.Newest))
        assertEquals(listOf(low, high, missing), orderArticles(entries, NewsOrder.Oldest))
    }

    @Test
    fun diagnosticCopyOmitsRawCodesAndStringsAndIncludesCriticalErrors() {
        val row = JSONObject().put("id", 8).put("code", "SSH_AUTH · SECRET_PASSWORD")
            .put("detail", "Bearer PRIVATE_TOKEN").put("level", "CRITICAL")
            .put("component", "android").put("api_key", "APISECRET")
        val sanitized = diagnosticEvent(row)
        assertEquals("SSH_AUTH", sanitized.getString("code"))
        assertFalse(sanitized.has("detail"))
        assertFalse(sanitized.toString().contains("SECRET"))
        assertTrue(matchesEventLevel("ERROR", "CRITICAL"))
        assertFalse(matchesEventLevel("WARNING", "CRITICAL"))
    }
}
