package dev.horizonmanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdatesTest {
    private fun release(tag: String, name: String = "Hovq_${tag.removePrefix("v")}.apk", state: String = "uploaded") =
        JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
            .put("assets", JSONArray().put(JSONObject().put("name", name).put("state", state)))

    @Test
    fun comparesVersionPartsNumericallyAndOpensTheCanonicalRelease() {
        val remote = release("v0.5.10").put("html_url", "https://example.com/unrelated")
        val update = AppRelease.newerRelease(remote, "0.5.9")
        assertEquals("0.5.10", update?.version)
        assertEquals("https://github.com/DEGSOL/HorizonManager/releases/tag/v0.5.10", update?.pageUrl)
        assertNull(AppRelease.newerRelease(remote, "0.5.10"))
        assertNull(AppRelease.newerRelease(remote, "0.6.0"))
    }

    @Test
    fun ignoresPrereleasesMalformedVersionsAndReleasesWithoutAnUploadedApk() {
        assertNull(AppRelease.newerRelease(release("v0.6.0-rc.1"), "0.5.7"))
        assertNull(AppRelease.newerRelease(release("v0.6.0").put("prerelease", true), "0.5.7"))
        assertNull(AppRelease.newerRelease(release("v0.6.0").put("draft", true), "0.5.7"))
        assertNull(AppRelease.newerRelease(release("v0.6.0", name = "checksums.txt"), "0.5.7"))
        assertNull(AppRelease.newerRelease(release("v0.6.0", state = "starter"), "0.5.7"))
        assertNull(AppRelease.newerRelease(release("v0.6.0"), "w0.5.7"))
    }
}
