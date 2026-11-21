package dev.horizonmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DigestNotificationTest {
    @Test
    fun verifiesVpsMarkerAndRejectsAnExpiredOrAlteredPacket() {
        val run = "a".repeat(32)
        val timestamp = 1700000000L
        val secret = "2".repeat(64)
        val packet = "$run.$timestamp.ca999f48e9c0bf0987f0de510c159599bca466905b6a08f28b29c1bb6443c704"
        assertEquals(run, verifiedDigest(packet, secret, timestamp + 2))
        assertNull(verifiedDigest(packet, secret, timestamp + 86_401))
        assertNull(verifiedDigest("b" + packet.drop(1), secret, timestamp))
        assertNull(verifiedDigest(packet, "3".repeat(64), timestamp))
    }

    @Test
    fun digestTracksAndroidLanguageAndOnlyOlderAgentsNeedAnUpdate() {
        assertEquals("ru", digestLocale(Locale.forLanguageTag("ru-RU")))
        for (language in digestLanguages) assertEquals(language, digestLocale(Locale.forLanguageTag(language)))
        assertEquals("zh", digestLocale(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("en", digestLocale(Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("de", digestLocale(Locale.forLanguageTag("de-AT")))
        assertEquals("en", appLocale(Locale.forLanguageTag("nl-NL")).language)
        assertEquals("en", digestLocale(Locale.forLanguageTag("nl-NL")))
        assertTrue(agentUpdateAvailable("0.1.0"))
        assertTrue(agentUpdateAvailable("0.1.3"))
        assertTrue(agentUpdateAvailable("0.1.4"))
        assertTrue(agentUpdateAvailable("0.1.5"))
        assertTrue(agentUpdateAvailable("0.1.6"))
        assertTrue(agentUpdateAvailable("0.1.7"))
        assertTrue(agentUpdateAvailable("0.1.8"))
        assertTrue(agentUpdateAvailable("0.1.9"))
        assertFalse(agentUpdateAvailable("0.1.10"))
    }
}
