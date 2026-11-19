package dev.horizonmanager

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NewsOrderTest {
    @Test
    fun oldestFirstRespectsOffsetsAndLeavesMissingDatesAtTheEnd() {
        val oldest = article("2026-11-19T15:00:00+05:00")
        val middle = article("2026-11-19T10:30:00Z")
        val newest = article("2026-11-19T10:00:00-01:00")
        val missing = JSONObject()
        val input = listOf(missing, newest, middle, oldest)

        assertEquals(listOf(oldest, middle, newest, missing), input.sortedBy(::chronologicalTime))
    }

    private fun article(time: String) = JSONObject().put("time", time)
}
