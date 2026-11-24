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
        assertTrue(agentUpdateAvailable("0.1.10"))
        assertTrue(agentUpdateAvailable("0.1.11"))
        assertTrue(agentUpdateAvailable("0.1.12"))
        assertFalse(agentUpdateAvailable("0.1.13"))
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class BackgroundDigestDeliveryTest {
    private val app get() = org.robolectric.RuntimeEnvironment.getApplication()
    private val server = Server("server-work", "Fixture VPS", "example.invalid", 22, "reader", false,
        "", "", "", "", background = true, installed = true)

    @Test
    fun aSavedPushRegistrationDoesNotSlowTheFallbackWorker() {
        DigestNotifications.setEndpoint(app, server.id, "https://ntfy.sh/up-test")
        DigestNotifications.setRegistered(app, server.id, "https://ntfy.sh/up-test")
        assertTrue(DigestNotifications.hasPush(app, server.id))
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
    fun aMissedPushIsDeliveredByTheNextMarkerAndDuplicatePushIsIgnored() {
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.grantPermissions(permission)
        DigestNotifications.marker(app, server, "first")
        assertTrue(notifications.activeNotifications.isEmpty())

        shadowApp.denyPermissions(permission)
        DigestNotifications.marker(app, server, "second")
        assertTrue(notifications.activeNotifications.isEmpty())

        shadowApp.grantPermissions(permission)
        DigestNotifications.marker(app, server, "second")
        val posted = notifications.activeNotifications.single()
        assertEquals(server.name, posted.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        notifications.cancelAll()

        DigestNotifications.push(app, server, "second")
        DigestNotifications.marker(app, server, "second")
        assertTrue(notifications.activeNotifications.isEmpty())
    }
}
