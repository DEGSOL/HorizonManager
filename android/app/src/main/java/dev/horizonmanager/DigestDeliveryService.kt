package dev.horizonmanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val digestId = Regex("(?:[a-f0-9]{32}|run-[0-9]{8}T[0-9]{6}Z-[a-f0-9]{8})")
internal fun validDigestMarker(value: String): Boolean = value.isEmpty() || digestId.matches(value)

internal fun deliveryProfiles(profiles: List<Server>): List<Server> = profiles.filter { it.installed && it.background }

internal class DigestDeliveryService : Service() {
    private data class Listener(val server: Server, val job: Job)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = mutableMapOf<String, Listener>()
    private var reload: Job? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!DigestNotifications.deliveryAllowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foreground) {
            try {
                showForeground()
                foreground = true
            } catch (_: SecurityException) {
                stopSelf()
                return START_NOT_STICKY
            } catch (_: IllegalStateException) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        reload?.cancel()
        val restart = intent?.getStringExtra(RESTART)
        reload = scope.launch {
            val profiles = withContext(Dispatchers.IO) { savedProfiles(applicationContext) }
            val desired = deliveryProfiles(profiles).associateBy(Server::id)
            val obsolete = listeners.filter { (id, listener) -> id == restart || listener.server != desired[id] || !listener.job.isActive }.keys
            for (id in obsolete) {
                listeners.remove(id)?.job?.cancel()
                online.update { it - id }
            }
            if (desired.isEmpty()) {
                stopSelf()
            } else {
                for ((id, server) in desired) if (id !in listeners)
                    listeners[id] = Listener(server, scope.launch { watch(server) })
            }
        }
        return START_STICKY
    }

    private fun showForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, ui(R.string.hovq_listener_title),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = ui(R.string.hovq_listener_detail)
            setShowBadge(false)
        })
        val intent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_hovq_notification)
            .setContentTitle(ui(R.string.hovq_listener_title)).setContentText(ui(R.string.hovq_listener_detail))
            .setContentIntent(intent).setSilent(true).setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    private suspend fun watch(server: Server) {
        val app = applicationContext
        val coroutine = currentCoroutineContext()
        var backoff = 2_000L
        try {
            while (DigestNotifications.deliveryAllowed(app)) {
                try {
                    fun listen(api: AgentApi) {
                        // Check the last persisted marker immediately after reconnecting.
                        var first = true
                        while (DigestNotifications.deliveryAllowed(app)) {
                            coroutine.ensureActive()
                            val before = DigestNotifications.currentMarker(app, server.id)
                            val marker = api.digestNotificationMarker(if (first) null else before)
                            coroutine.ensureActive()
                            if (!DigestNotifications.deliveryAllowed(app)) break
                            // Protect against a reply from a connection which just lost its profile.
                            if (savedProfiles(app).none { it == server }) break
                            coroutine.ensureActive()
                            online.update { it + server.id }
                            DigestNotifications.marker(app, server, before, marker)
                            if (!first) backoff = 2_000L
                            first = false
                        }
                    }
                    // The notification connection owns its own SSH session. It never holds
                    // the mutex used by the feed, jobs, or settings while awaiting a digest.
                    useTransport {
                        if (server.https.isEmpty()) Ssh.withSession(server) { listen(AgentApi(server, it)) }
                        else listen(AgentApi(server))
                    }
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    online.update { it - server.id }
                    val code = (error as? Problem)?.code ?: "BACKGROUND_CHECK_FAILED"
                    if (code != "API_404") runCatching {
                        DigestNotifications.record(app, server.id, code, error.connectionTrace())
                    }
                    delay(if (code in setOf("API_404", "AUTH_REQUIRED", "SSH_AUTH")) 60_000 else backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                }
            }
        } finally {
            if (coroutine[Job]?.isCancelled != true) online.update { it - server.id }
            if (!DigestNotifications.deliveryAllowed(app)) stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        online.value = emptySet()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "horizon-live"
        private const val NOTIFICATION_ID = 0x484f5651
        private const val RESTART = "restart-server"
        private val online = MutableStateFlow(emptySet<String>())
        val connected = online.asStateFlow()

        private fun savedProfiles(app: Context): List<Server> = runCatching { Vault(app).load() }.getOrDefault(emptyList())

        suspend fun restore(app: Context) {
            val profiles = withContext(Dispatchers.IO) { savedProfiles(app) }
            reconcile(app, profiles)
        }

        fun reconcile(app: Context, profiles: List<Server>, restart: String? = null) {
            val intent = Intent(app, DigestDeliveryService::class.java).putExtra(RESTART, restart)
            if (deliveryProfiles(profiles).isEmpty() || !DigestNotifications.deliveryAllowed(app)) {
                app.stopService(intent)
            } else {
                try {
                    ContextCompat.startForegroundService(app, intent)
                } catch (_: IllegalStateException) {
                    // Android can defer a service start when the app is no longer visible.
                    // The periodic worker remains scheduled.
                } catch (_: SecurityException) {
                    // A managed device can restrict foreground service execution.
                }
            }
        }
    }
}
