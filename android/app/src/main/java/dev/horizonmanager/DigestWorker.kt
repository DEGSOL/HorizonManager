package dev.horizonmanager

import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val app = applicationContext
        val id = inputData.getString("server")
        if (id == null && !DigestNotifications.deliveryAllowed(app)) return@withContext Result.success()
        val profiles = try {
            Vault(app).load()
        } catch (_: Exception) {
            return@withContext Result.retry()
        }
        var retry = false
        for (server in profiles.filter { it.installed && (id == it.id || id == null && it.background) }) {
            val immediate = id != null
            val endpoint = DigestNotifications.endpoint(app, server.id)
            val registration = DigestNotifications.registration(app, server.id)
            if (!immediate && !DigestNotifications.checkDue(app, server.id, endpoint, registration)) continue
            try {
                val marker = withAgent(server) { api ->
                    when {
                        endpoint.isNotEmpty() && server.background && registration != endpoint -> {
                            val body = JSONObject().put("endpoint", endpoint)
                                .put("secret", DigestNotifications.secret(app, server.id))
                            val value = api.put("push/${DigestNotifications.device(app)}", body) as JSONObject
                            DigestNotifications.setRegistered(app, server.id, endpoint)
                            value.optString("id")
                        }
                        registration.isNotEmpty() && (!server.background || endpoint.isEmpty()) -> {
                            api.delete("push/${DigestNotifications.device(app)}")
                            DigestNotifications.setRegistered(app, server.id, "")
                            latestMarker(api)
                        }
                        else -> latestMarker(api)
                    }
                }
                DigestNotifications.marker(app, server, marker)
                if (endpoint.isNotEmpty() && server.background &&
                    DigestNotifications.registration(app, server.id) == endpoint) {
                    DigestNotifications.reconciled(app, server.id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val code = (error as? Problem)?.code ?: "BACKGROUND_CHECK_FAILED"
                DigestNotifications.record(app, server.id, code)
                if (code in setOf("PUSH_ENDPOINT", "API_404") && endpoint.isNotEmpty()) {
                    DigestNotifications.setEndpoint(app, server.id, "")
                }
                retry = retry || immediate && runAttemptCount < 3 &&
                    code !in setOf("AUTH_REQUIRED", "PUSH_ENDPOINT", "API_404")
            }
        }
        if (id != null) schedule(app, profiles)
        if (retry) Result.retry() else Result.success()
    }

    private fun latestMarker(api: AgentApi): String = try {
        (api.get("digest-marker") as JSONObject).optString("id")
    } catch (error: Problem) {
        if (error.code != "API_404") throw error
        (api.get("digests?limit=1") as JSONArray).optJSONObject(0)?.optString("id").orEmpty()
    }

    private fun <T> withAgent(server: Server, action: (AgentApi) -> T): T =
        if (server.https.isNotEmpty()) action(AgentApi(server))
        else Ssh.withSession(server) { action(AgentApi(server, it)) }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(app: Context, profiles: List<Server>) {
            val manager = WorkManager.getInstance(app)
            val enabled = profiles.filter { it.installed && it.background }
            if (enabled.isEmpty()) {
                manager.cancelUniqueWork("horizon-digests")
                return
            }
            val minutes = if (enabled.all { DigestNotifications.hasPush(app, it.id) }) 360L else 15L
            val flex = if (minutes == 15L) 5L else 30L
            val work = PeriodicWorkRequestBuilder<DigestWorker>(minutes, TimeUnit.MINUTES, flex, TimeUnit.MINUTES)
                .setConstraints(network).build()
            manager.enqueueUniquePeriodicWork("horizon-digests", ExistingPeriodicWorkPolicy.UPDATE, work)
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
