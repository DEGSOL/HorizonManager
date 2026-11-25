package dev.horizonmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal fun deliveryProfiles(profiles: List<Server>): List<Server> = profiles.filter { it.installed && it.background }

internal fun nextDigestCheck(now: Instant, nextRun: Instant?, preparingSince: Instant?): Instant? {
    val nearScheduledRun = nextRun?.plusSeconds(12)?.let { maxOf(it, now.plusSeconds(15)) }
    val whilePreparing = preparingSince?.let { since ->
        val age = now.epochSecond - since.epochSecond
        now.plusSeconds(when {
            age < 180 -> 25
            age < 600 -> 55
            else -> 110
        })
    }
    return listOfNotNull(nearScheduledRun, whilePreparing).minOrNull()
}

internal class DigestScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED)) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) DigestNotifications.removeLegacyWaitingNotice(context)
        val pending = goAsync()
        try {
            DigestWorker.catchUp(context).result.addListener({ pending.finish() }, Executor { it.run() })
        } catch (_: IllegalStateException) {
            pending.finish()
        }
    }
}

internal class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private data class Snapshot(val marker: String, val nextRun: Instant?, val preparingSince: Instant?, val canWait: Boolean)

    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val app = applicationContext
        val id = inputData.getString("server")
        val plannedAt = inputData.getLong("planned-at", 0L)
        val catchUp = inputData.getBoolean("catch-up", false)
        if (id != null && plannedAt != 0L && plannedTime(app, id) != plannedAt) return@withContext Result.success()
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
                val snapshot = withAgent(server, independent = id != null) { api ->
                    var current = readSnapshot(api)
                    if (current.canWait && legacy != null) {
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
                    // A short wait while Android is already running this check can catch
                    // the result without another wakeup. It never owns the feed's SSH lock.
                    if (delivery && id != null && current.canWait &&
                        current.preparingSince != null && before == current.marker) {
                        if (api.digestNotificationMarker(current.marker) != current.marker)
                            current = readSnapshot(api)
                    }
                    current
                }
                if (delivery && Vault(app).load().any { it == server }) {
                    DigestNotifications.marker(app, server, before, snapshot.marker)
                    updatePlan(app, server.id, plannedAt,
                        nextDigestCheck(Instant.now(), snapshot.nextRun, snapshot.preparingSince))
                }
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
        if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }

    private fun readSnapshot(api: AgentApi): Snapshot {
        val plan = try {
            api.get("digest-notification-plan") as JSONObject
        } catch (error: Problem) {
            if (error.code != "API_404") throw error
            null
        }
        if (plan != null) return Snapshot(markerId(plan), epoch(plan), timestamp(plan.optString("preparing_since")), true)

        // Agents installed before this release still supply the schedule and active jobs.
        val nativeMarker = try {
            api.digestNotificationMarker()
        } catch (error: Problem) {
            if (error.code != "API_404") throw error
            null
        }
        val marker = nativeMarker ?: latestMarker(api)
        val status = api.status()
        val jobs = (api.get("jobs?active=true&limit=10") as JSONArray).objects()
        val preparing = jobs.firstOrNull { it.optString("kind") == "run" &&
            it.optString("state") in setOf("queued", "running") && it.isNull("result") }
        return Snapshot(marker, epoch(status), timestamp(preparing?.optString("created")), nativeMarker != null)
    }

    private fun epoch(json: JSONObject): Instant? = json.optDouble("next").takeIf(Double::isFinite)
        ?.let { Instant.ofEpochSecond(it.toLong()) }

    private fun timestamp(value: String?): Instant? = value?.takeIf(String::isNotBlank)
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun markerId(json: JSONObject): String =
        (json.opt("id") as? String)?.takeIf(::validDigestMarker) ?: throw Problem("AGENT_PROTOCOL")

    private fun latestMarker(api: AgentApi): String = try {
        markerId(api.get("digest-marker") as JSONObject)
    } catch (error: Problem) {
        if (error.code != "API_404") throw error
        (api.get("digests?limit=1") as JSONArray).optJSONObject(0)?.let(::markerId).orEmpty()
    }

    private suspend fun <T> withAgent(server: Server, independent: Boolean, action: (AgentApi) -> T): T =
        if (server.https.isNotEmpty()) useTransport { action(AgentApi(server)) }
        else useTransport(server.takeUnless { independent }) { Ssh.withSession(server) { action(AgentApi(server, it)) } }

    companion object {
        private const val PLAN_TAG = "horizon-digest-planned"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        private fun plans(app: Context) = app.getSharedPreferences("digest_check_plan", Context.MODE_PRIVATE)
        private fun plannedTime(app: Context, server: String) = plans(app).getLong(server, 0L)
        private fun planName(server: String, at: Long) = "$PLAN_TAG-$server-$at"

        @Synchronized
        private fun updatePlan(app: Context, server: String, current: Long, next: Instant?) {
            val existing = plannedTime(app, server)
            if (current != 0L && existing != current) return
            val now = System.currentTimeMillis()
            // Coalesce checks near the same instant and retain an earlier pending wakeup.
            val at = next?.toEpochMilli()?.let { (it + 4999) / 5000 * 5000 } ?: 0L
            if (at != 0L && existing != current && existing in (now + 1)..at) return
            val manager = WorkManager.getInstance(app)
            if (at == 0L) plans(app).edit().remove(server).apply()
            else {
                plans(app).edit().putLong(server, at).apply()
                val work = OneTimeWorkRequestBuilder<DigestWorker>().setConstraints(network)
                    .setInputData(workDataOf("server" to server, "planned-at" to at))
                    .setInitialDelay((at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .addTag(PLAN_TAG).build()
                manager.enqueueUniqueWork(planName(server, at), ExistingWorkPolicy.KEEP, work)
            }
            if (existing != 0L && existing != current && existing != at)
                manager.cancelUniqueWork(planName(server, existing))
        }

        fun schedule(app: Context, profiles: List<Server>) {
            val manager = WorkManager.getInstance(app)
            if (deliveryProfiles(profiles).isEmpty()) {
                manager.cancelUniqueWork("horizon-digests")
                manager.cancelAllWorkByTag(PLAN_TAG)
                plans(app).edit().clear().apply()
                return
            }
            val work = PeriodicWorkRequestBuilder<DigestWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
                .setConstraints(network).build()
            manager.enqueueUniquePeriodicWork("horizon-digests", ExistingPeriodicWorkPolicy.UPDATE, work)
        }

        fun catchUp(app: Context): androidx.work.Operation {
            val work = OneTimeWorkRequestBuilder<DigestWorker>()
                .setInputData(workDataOf("catch-up" to true)).setConstraints(network).build()
            return WorkManager.getInstance(app).enqueueUniqueWork("horizon-digests-catch-up", ExistingWorkPolicy.KEEP, work)
        }

        fun sync(app: Context, server: String, reschedule: Boolean = false) {
            if (reschedule) updatePlan(app, server, 0L, null)
            val work = OneTimeWorkRequestBuilder<DigestWorker>()
                .setInputData(workDataOf("server" to server)).setConstraints(network)
            // On older Android, expedited WorkManager jobs need a foreground notification.
            if (Build.VERSION.SDK_INT >= 31) work.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            WorkManager.getInstance(app).enqueueUniqueWork("horizon-push-$server", ExistingWorkPolicy.REPLACE, work.build())
        }

        fun cancelChecks(app: Context, server: String) {
            WorkManager.getInstance(app).cancelUniqueWork("horizon-push-$server")
            updatePlan(app, server, 0L, null)
        }

        fun remove(app: Context, server: String, profiles: List<Server>) {
            cancelChecks(app, server)
            schedule(app, profiles)
        }
    }
}
