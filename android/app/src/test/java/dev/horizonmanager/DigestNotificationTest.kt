package dev.horizonmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class DigestNotificationTest {
    @Test
    fun acceptsOnlyValidDigestMarkers() {
        assertTrue(validDigestMarker(""))
        assertTrue(validDigestMarker("a".repeat(32)))
        assertTrue(validDigestMarker("run-20261125T011200Z-aabbccdd"))
        assertFalse(validDigestMarker("a".repeat(32) + "&wait=0"))
        assertFalse(validDigestMarker("a".repeat(31)))
    }

    @Test
    fun checksNearTheScheduleAndBacksOffDuringLongPreparation() {
        val now = Instant.parse("2026-11-25T03:00:00Z")
        val next = now.plusSeconds(3600)
        assertEquals(null, nextDigestCheck(now, null, null))
        assertEquals(next.plusSeconds(12), nextDigestCheck(now, next, null))
        assertEquals(now.plusSeconds(15), nextDigestCheck(now, now.minusSeconds(90), null))
        assertEquals(now.plusSeconds(25), nextDigestCheck(now, next, now.minusSeconds(60)))
        assertEquals(now.plusSeconds(55), nextDigestCheck(now, next, now.minusSeconds(240)))
        assertEquals(now.plusSeconds(110), nextDigestCheck(now, next, now.minusSeconds(900)))
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
        assertTrue(agentUpdateAvailable("0.1.10"))
        assertTrue(agentUpdateAvailable("0.1.11"))
        assertTrue(agentUpdateAvailable("0.1.12"))
        assertTrue(agentUpdateAvailable("0.1.13"))
        assertTrue(agentUpdateAvailable("0.1.14"))
        assertTrue(agentUpdateAvailable("0.1.15"))
        assertTrue(agentUpdateAvailable("0.1.16"))
        assertTrue(agentUpdateAvailable("0.1.17"))
        assertFalse(agentUpdateAvailable(BuildConfig.AGENT_VERSION))
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class BackgroundDigestDeliveryTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val server = Server("server-work", "Fixture VPS", "example.invalid", 22, "reader", false,
        "", "", "", "", background = true, installed = true)

    @Test
    fun theDirectListenerHasAnIndependentBackgroundFallback() {
        assertEquals(listOf(server), deliveryProfiles(listOf(server, server.copy(installed = false),
            server.copy(background = false))))
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        DigestWorker.schedule(app, listOf(server))
        val work = androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWork("horizon-digests")
            .get(5, java.util.concurrent.TimeUnit.SECONDS).single()
        assertEquals(15 * 60_000L, work.periodicityInfo?.repeatIntervalMillis)
        assertEquals(androidx.work.NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    @Test
    fun theLiveListenerAndFallbackWorkerDeliverEachDigestOnce() {
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        val first = "a".repeat(32)
        val second = "b".repeat(32)
        val third = "c".repeat(32)
        shadowApp.grantPermissions(permission)
        DigestNotifications.marker(app, server, null, first)
        assertTrue(notifications.activeNotifications.isEmpty())

        shadowApp.denyPermissions(permission)
        DigestNotifications.marker(app, server, first, second)
        assertTrue(notifications.activeNotifications.isEmpty())
        assertEquals(first, DigestNotifications.currentMarker(app, server.id))

        shadowApp.grantPermissions(permission)
        DigestNotifications.marker(app, server, first, second)
        val posted = notifications.activeNotifications.single()
        assertEquals(server.name, posted.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        notifications.cancelAll()

        DigestNotifications.marker(app, server, first, third)
        DigestNotifications.marker(app, server, second, second)
        assertEquals(second, DigestNotifications.currentMarker(app, server.id))
        assertTrue(notifications.activeNotifications.isEmpty())
    }
}
