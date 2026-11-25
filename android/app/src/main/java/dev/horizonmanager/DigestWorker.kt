package dev.horizonmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class DigestScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED)) return
        val pending = goAsync()
        val app = context.applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            try {
                DigestDeliveryService.restore(app)
                DigestWorker.catchUp(app)
            } finally {
                pending.finish()
            }
        }
    }
}

internal class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val app = applicationContext
        val id = inputData.getString("server")
        val catchUp = inputData.getBoolean("catch-up", false)
        val profiles = try {
            Vault(app).load()
        } catch (_: Exception) {
            return@withContext Result.retry()
        }
        if (catchUp) schedule(app, profiles)
        var retry = false
        for (server in profiles.filter { it.installed && (id == null || id == it.id) }) {
            val legacy = DigestNotifications.legacyDevice(app, server.id)
            val delivery = server.background && DigestNotifications.deliveryAllowed(app)
            if (!delivery && legacy == null) continue
            val before = DigestNotifications.currentMarker(app, server.id)
            var migrationError: Exception? = null
            try {
                val marker = withAgent(server) { api ->
                    val native = try {
                        api.digestNotificationMarker()
                    } catch (error: Problem) {
                        if (error.code != "API_404") throw error
                        null
                    }
                    if (native != null && legacy != null) {
                        try {
                            api.delete("push/$legacy")
                            DigestNotifications.clearLegacyRegistration(app, server.id)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            retry = true
                            migrationError = error
                        }
                    }
                    if (delivery) native ?: latestMarker(api) else null
                }
                if (marker != null && Vault(app).load().any { it == server })
                    DigestNotifications.marker(app, server, before, marker)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val code = (error as? Problem)?.code ?: "BACKGROUND_CHECK_FAILED"
                DigestNotifications.record(app, server.id, code, error.connectionTrace())
                retry = retry || (id != null || catchUp) && code !in setOf("AUTH_REQUIRED", "API_404")
            } finally {
                migrationError?.let {
                    DigestNotifications.record(app, server.id, (it as? Problem)?.code ?: "PUSH_SYNC_FAILED",
                        it.connectionTrace())
                }
            }
        }
        if (id != null) schedule(app, profiles)
        if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }

    private fun latestMarker(api: AgentApi): String = try {
        (api.get("digest-marker") as JSONObject).optString("id")
    } catch (error: Problem) {
        if (error.code != "API_404") throw error
        (api.get("digests?limit=1") as JSONArray).optJSONObject(0)?.optString("id").orEmpty()
    }

    private suspend fun <T> withAgent(server: Server, action: (AgentApi) -> T): T =
        if (server.https.isNotEmpty()) useTransport { action(AgentApi(server)) }
        else useTransport(server) { Ssh.withSession(server) { action(AgentApi(server, it)) } }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(app: Context, profiles: List<Server>) {
            val manager = WorkManager.getInstance(app)
            if (deliveryProfiles(profiles).isEmpty()) {
                manager.cancelUniqueWork("horizon-digests")
                return
            }
            // Periodic checks recover notifications when Android pauses the live service.
            val work = PeriodicWorkRequestBuilder<DigestWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
                .setConstraints(network).build()
            manager.enqueueUniquePeriodicWork("horizon-digests", ExistingPeriodicWorkPolicy.UPDATE, work)
        }

        fun catchUp(app: Context): androidx.work.Operation {
            val work = OneTimeWorkRequestBuilder<DigestWorker>()
                .setInputData(workDataOf("catch-up" to true)).setConstraints(network).build()
            return WorkManager.getInstance(app).enqueueUniqueWork("horizon-digests-catch-up", ExistingWorkPolicy.KEEP, work)
        }

        fun sync(app: Context, server: String) {
            val work = OneTimeWorkRequestBuilder<DigestWorker>()
                .setInputData(workDataOf("server" to server)).setConstraints(network).build()
            WorkManager.getInstance(app).enqueueUniqueWork("horizon-push-$server", ExistingWorkPolicy.REPLACE, work)
        }

        fun remove(app: Context, server: String, profiles: List<Server>) {
            WorkManager.getInstance(app).cancelUniqueWork("horizon-push-$server")
            schedule(app, profiles)
        }
    }
}
