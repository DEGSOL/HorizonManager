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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal class DigestScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        DigestWorker.catchUp(context).result.addListener({ pending.finish() }, Executor { it.run() })
    }
}

internal class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val app = applicationContext
        val id = inputData.getString("server")
        val catchUp = inputData.getBoolean("catch-up", false)
        if (id == null && !catchUp && !DigestNotifications.deliveryAllowed(app)) return@withContext Result.success()
        val profiles = try {
            Vault(app).load()
        } catch (_: Exception) {
            return@withContext Result.retry()
        }
        if (catchUp) schedule(app, profiles)
        if (id == null && !DigestNotifications.deliveryAllowed(app)) return@withContext Result.success()
        var retry = false
        for (server in profiles.filter { it.installed && (id == it.id || id == null && it.background) }) {
            val immediate = id != null
            if (server.background) runCatching { DigestNotifications.connectAvailable(app, server.id) }
            val endpoint = DigestNotifications.endpoint(app, server.id)
            val registration = DigestNotifications.registration(app, server.id)
            var pushError: Exception? = null
            try {
                val marker = withAgent(server) { api ->
                    val registrationMarker = try {
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
                                null
                            }
                            else -> null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val code = (error as? Problem)?.code ?: "PUSH_SYNC_FAILED"
                        pushError = error
                        if (code in setOf("PUSH_ENDPOINT", "API_404") &&
                            endpoint.isNotEmpty() && DigestNotifications.endpoint(app, server.id) == endpoint) {
                            DigestNotifications.setEndpoint(app, server.id, "")
                        }
                        null
                    }
                    registrationMarker ?: latestMarker(api)
                }
                DigestNotifications.marker(app, server, marker)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val code = (error as? Problem)?.code ?: "BACKGROUND_CHECK_FAILED"
                DigestNotifications.record(app, server.id, code, error.connectionTrace())
                retry = retry || (immediate || catchUp) && runAttemptCount < 3 &&
                    code !in setOf("AUTH_REQUIRED", "API_404")
            } finally {
                pushError?.let { error ->
                    DigestNotifications.record(app, server.id, (error as? Problem)?.code ?: "PUSH_SYNC_FAILED",
                        error.connectionTrace())
                }
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

    private suspend fun <T> withAgent(server: Server, action: (AgentApi) -> T): T =
        if (server.https.isNotEmpty()) useTransport { action(AgentApi(server)) }
        else useTransport(server) { Ssh.withSession(server) { action(AgentApi(server, it)) } }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(app: Context, profiles: List<Server>) {
            val manager = WorkManager.getInstance(app)
            if (profiles.none { it.installed && it.background }) {
                manager.cancelUniqueWork("horizon-digests")
                return
            }
            // Registration doesn't prove the distributor can deliver the next push.
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
