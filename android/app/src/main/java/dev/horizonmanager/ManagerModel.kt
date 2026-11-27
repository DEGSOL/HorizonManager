package dev.horizonmanager

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal enum class ConnectionPhase { Idle, Connecting, SshAvailable, CheckingAgent, Connected }

internal data class Panel(
    val servers: List<Server> = emptyList(),
    val server: Server? = null,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val lastContact: String = "",
    val connectionProblem: String = "",
    val connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
    val connectionCancellable: Boolean = false,
    val probe: Map<String, String> = emptyMap(),
    val hostConfirmation: HostConfirmation? = null,
    val problem: String = "",
    val userProblem: String = "",
    val editedServer: String = "",
    val maintenanceResult: String = "",
    val status: JSONObject = JSONObject(),
    val config: JSONObject = JSONObject(),
    val schema: JSONObject = JSONObject(),
    val archive: List<JSONObject> = emptyList(),
    val hasMoreDigests: Boolean = false,
    val manuallyArchived: Set<String> = emptySet(),
    val hiddenDigests: Set<String> = emptySet(),
    val collectionHours: Int = DEFAULT_COLLECTION_HOURS,
    val jobs: List<JSONObject> = emptyList(),
    val sourceHistory: List<JSONObject> = emptyList(),
    val events: List<JSONObject> = emptyList(),
    val flags: List<ArticleFlag> = emptyList(),
    val schedule: JSONObject = JSONObject(),
    val syncedDigest: String = "",
    val selectedRun: String = "",
    val language: String = "",
    val markdown: String = "",
    val articles: List<JSONObject> = emptyList(),
    val total: Int = 0,
    val file: String? = null,
    val deviceFile: Boolean = false,
    val updatingAgent: Boolean = false,
    val agentVersion: String = "",
)

internal fun Panel.preferredDigestLanguage() = status.optString("digest_language", digestLocale())

internal fun Panel.agentGateRequired() = updatingAgent || server?.installed == true &&
    !agentCompatible(agentVersion)

internal data class SavedModelCheck(val model: String, val revision: Int, val pending: Boolean, val error: String = "")
internal data class ChatGptLoginStatus(val server: String, val waiting: Boolean, val error: String = "")

internal class ManagerModel(private val app: Application) : AndroidViewModel(app) {
    val appUpdates = AppUpdates(app.getSharedPreferences("app_updates", 0))
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    val networkAvailable = callbackFlow {
        val manager = connectivity ?: run { close(); return@callbackFlow }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(manager.activeNetwork != null) }
        }
        manager.registerDefaultNetworkCallback(callback)
        trySend(manager.activeNetwork != null)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private val vault = Vault(app)
    private val cache = ManagerDb.get(app).cache()
    private val mutable = MutableStateFlow(Panel())
    val state = mutable.asStateFlow()
    private val initializedProfiles = MutableStateFlow(false)
    val profilesLoaded = initializedProfiles.asStateFlow()
    val favorites = cache.favorites()
    private val articleDisplay = app.getSharedPreferences("article_display", 0)

    fun articleDisplayOptions(server: String): JSONObject =
        JSONObject(articleDisplay.getString(server, "{}") ?: "{}")

    fun unreadFeed(server: String, run: String, language: String) =
        cache.digestPages(server, run, language).map { pages ->
            pages.asSequence().flatMap { digestPublications(it).asSequence() }
                .map { it.optString("id") }.filter(String::isNotBlank).toSet()
        }.distinctUntilChanged().combine(cache.readArticleIds(server)) { articles, read ->
            articles.size - read.count(articles::contains)
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    private val saved = MutableStateFlow(0)
    val settingsSaved = saved.asStateFlow()
    private val updateNotices = Channel<Unit>(Channel.BUFFERED)
    val agentUpdated = updateNotices.receiveAsFlow()

    private fun settingsSaved() { saved.update { it + 1 } }
    private data class PendingConnection(val server: Server, val editing: Boolean = false)
    @Volatile private var candidate: PendingConnection? = null
    private var flagObserver: Job? = null
    private var eventObserver: Job? = null
    private var active: Job? = null
    private var activeIsQuiet = false
    @Volatile private var reconnectPaused = false
    @Volatile private var quietRetryAfter = 0L
    private var pendingSelection: Job? = null
    private var lastError = ""
    private var lastErrorTime = 0L
    private val selection = app.getSharedPreferences("selected_server", 0)
    private val verifiedAgentVersions = app.getSharedPreferences("verified_agent_versions", 0)
    private var journalSync = 0L
    private var pendingNotification = ""
    private var installOperation = ""
    private var agentUpdateDeadline = 0L
    @Volatile private var archiveRun: String? = null
    private class PageRequest(val run: String, val language: String)
    @Volatile private var pageRequest: PageRequest? = null
    private val archiveSettings = app.getSharedPreferences("digest_windows", 0)
    private val archiveOverrides = app.getSharedPreferences("digest_archive_overrides", 0)

    init {
        viewModelScope.launch {
            state.distinctUntilChangedBy {
                Triple(it.server?.id to it.selectedRun, canPrefetchArtwork(it), it.articles)
            }.collectLatest { panel ->
                val server = panel.server ?: return@collectLatest
                if (!canPrefetchArtwork(panel)) return@collectLatest
                val artwork = ArtworkCache.get(app)
                for (article in panel.articles) {
                    if (article.optJSONObject("media")?.let { articleField(it, "url").isNotBlank() } != true) continue
                    val id = articleField(article, "id")
                    artwork.load(artworkKey(server.id, article), article, coverRelay(panel, id), prefetch = true)
                }
            }
        }
        viewModelScope.launch {
            try {
                val servers = withContext(Dispatchers.IO) {
                    vault.load().also(vault::removeLegacyRouterKeys)
                }
                mutable.update { it.copy(servers = servers) }
                val id = pendingNotification.ifEmpty { selection.getString("id", "").orEmpty() }
                pendingNotification = ""
                servers.find { it.id == id }?.let(::select) ?: observe("setup")
                runCatching { DigestWorker.schedule(app, servers) }
                runCatching { DigestDeliveryService.reconcile(app, servers) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.update { it.copy(problem = "VAULT_READ", userProblem = "VAULT_READ") }
            } finally {
                initializedProfiles.value = true
            }
        }
    }

    private fun canPrefetchArtwork(panel: Panel): Boolean =
        panel.ready && panel.status.length() > 0 && panel.connectionPhase == ConnectionPhase.Connected

    private fun record(error: Throwable, server: Server?, announce: Boolean = true, affectsConnection: Boolean = true) {
        val code = when (error) {
            is Problem -> error.code
            is HostConfirmation -> "SSH_HOST_CHANGED"
            else -> "ANDROID_" + error.javaClass.simpleName.uppercase()
        }
        val detail = (error as? Problem)?.detail.orEmpty()
        val problem = listOf(code, detail).filter(String::isNotBlank).joinToString(" · ")
        val connectionError = isConnectionFailure(code)
        mutable.update {
            if (server != null && server.id != it.server?.id && server.id != candidate?.server?.id && affectsConnection) it
            else it.copy(problem = problem, userProblem = if (announce) problem else it.userProblem,
                connectionProblem = if (connectionError && affectsConnection) code else it.connectionProblem,
                connectionPhase = if (connectionError && affectsConnection) ConnectionPhase.Idle else it.connectionPhase)
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (affectsConnection && isTransientConnectionFailure(code)) quietRetryAfter = now + 60_000
        val signature = "${server?.id}:$problem:${error.cause?.javaClass?.simpleName}"
        if (signature == lastError && now - lastErrorTime < 60_000) return
        lastError = signature
        lastErrorTime = now
        val ts = Instant.now()
        viewModelScope.launch {
            runCatching {
                val data = JSONObject().put("time", ts.toString()).put("component", "android")
                    .put("level", "ERROR").put("code", code)
                val diagnostic = if (connectionError) listOfNotNull(
                    detail.takeIf { it == "ssh" || it == "tcp/dns" },
                    error.connectionTrace(),
                ).joinToString(" · ") else detail
                if (diagnostic.isNotBlank()) data.put("detail", diagnostic)
                val id = ts.epochSecond * 1_000_000 + ts.nano / 1000
                cache.saveEvents(listOf(LocalEvent(server?.id ?: "setup", "android", id, ts.toString(), data.toString())))
            }
        }
    }

    private fun operation(quiet: Boolean = false, connectionCheck: Boolean = false,
                          editedProfile: Server? = null, task: suspend () -> Unit) {
        val previous = active?.takeIf { !it.isCompleted }
        if (previous != null && (quiet || !activeIsQuiet)) return
        activeIsQuiet = quiet
        if (!quiet) mutable.update {
            it.copy(busy = true, problem = "", userProblem = "", connectionCancellable = connectionCheck)
        }
        active = viewModelScope.launch {
            try {
                previous?.join()
                withContext(Dispatchers.IO) { task() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: HostConfirmation) {
                if (connectionCheck && candidate != null) {
                    mutable.update {
                        it.copy(hostConfirmation = error, connectionPhase = if (editedProfile == null)
                            ConnectionPhase.Idle else it.connectionPhase)
                    }
                } else record(error, state.value.server, announce = !quiet)
            } catch (error: Exception) {
                record(error, editedProfile ?: state.value.server, announce = !quiet,
                    affectsConnection = editedProfile == null)
                if (editedProfile != null) candidate = null
            } finally {
                if (!quiet) mutable.update { it.copy(busy = false, connectionCancellable = false) }
            }
        }
    }

    private fun connectionStage(phase: ConnectionPhase) {
        mutable.update {
            if (reconnectPaused) it else it.copy(connectionPhase = phase, connectionProblem = "")
        }
    }

    private fun connected(server: Server) {
        quietRetryAfter = 0
        mutable.update {
            if (it.server != server || reconnectPaused) it
            else it.copy(lastContact = Instant.now().toString(), connectionProblem = "",
                connectionPhase = ConnectionPhase.Connected)
        }
    }

    fun cancelConnection() {
        if (!state.value.connectionCancellable) return
        val editing = candidate?.editing == true
        candidate = null
        active?.cancel()
        if (!editing) {
            reconnectPaused = true
            mutable.update { it.copy(connectionPhase = ConnectionPhase.Idle, connectionProblem = "") }
        }
    }

    fun networkChanged(available: Boolean) {
        val panel = state.value
        if (reconnectPaused || panel.server == null || !panel.ready) return
        if (!available && panel.connectionPhase == ConnectionPhase.Connected) {
            record(Problem("NETWORK_UNAVAILABLE"), panel.server, announce = false)
        } else if (available && isTransientConnectionFailure(panel.connectionProblem)) {
            quietRetryAfter = 0
            refresh(quiet = true)
        }
    }

    private suspend fun <T> retryConnection(task: suspend () -> T): T {
        val intervals = listOf(750L, 2_000L)
        for (interval in intervals) {
            try {
                return task()
            } catch (error: Problem) {
                if (!isTransientConnectionFailure(error.code)) throw error
                delay(interval)
            }
        }
        return task()
    }

    private fun store(server: Server) {
        val profiles = state.value.servers.toMutableList()
        val index = profiles.indexOfFirst { it.id == server.id }
        val previous = profiles.getOrNull(index)
        if (index < 0) profiles.add(server) else profiles[index] = server
        vault.save(profiles)
        if (previous?.background == true && !server.background) DigestNotifications.resetMarker(app, server.id)
        selection.edit().putString("id", server.id).apply()
        mutable.update {
            it.copy(servers = profiles, server = server, ready = server.installed,
                agentVersion = if (it.server?.id == server.id) it.agentVersion else "")
        }
        if (server != previous && (server.background || previous?.background == true)) {
            DigestWorker.schedule(app, profiles)
            DigestDeliveryService.reconcile(app, profiles)
            if (server.installed && server.background) DigestWorker.sync(app, server.id, reschedule = true)
            else if (previous?.background == true) DigestWorker.cancelChecks(app, server.id)
        }
    }

    private fun observe(server: String) {
        flagObserver?.cancel()
        eventObserver?.cancel()
        flagObserver = viewModelScope.launch {
            cache.flags(server).collect { flags -> mutable.update { it.copy(flags = flags) } }
        }
        eventObserver = viewModelScope.launch {
            cache.events(server).collect { rows ->
                mutable.update { it.copy(events = rows.map { row -> JSONObject(row.data) }) }
            }
        }
    }

    private fun afterConnectionCloses(next: () -> Unit): Boolean {
        val previous = active?.takeIf { !it.isCompleted } ?: return false
        if (!activeIsQuiet && !state.value.connectionCancellable) return true
        previous.cancel()
        pendingSelection?.cancel()
        pendingSelection = viewModelScope.launch {
            previous.join()
            next()
        }
        return true
    }

    fun add() {
        if (afterConnectionCloses(::add)) return
        reconnectPaused = false
        quietRetryAfter = 0
        candidate = null
        selection.edit().remove("id").apply()
        mutable.update { Panel(servers = it.servers) }
        observe("setup")
    }

    fun select(server: Server) {
        if (afterConnectionCloses { select(server) }) return
        reconnectPaused = false
        quietRetryAfter = 0
        selectLocally(server, state.value.servers)
        operation(connectionCheck = true) { loadSelected(server) }
    }

    private fun selectLocally(server: Server?, servers: List<Server>) {
        candidate = null
        archiveRun = null
        pageRequest = null
        selection.edit().putString("id", server?.id).apply()
        mutable.update { previous ->
            Panel(servers = servers, server = server, ready = server?.installed == true, busy = previous.busy,
                agentVersion = server?.let { verifiedAgentVersions.getString(it.id, "").orEmpty() }.orEmpty(),
                collectionHours = server?.let { archiveSettings.getInt(it.id, DEFAULT_COLLECTION_HOURS) } ?: DEFAULT_COLLECTION_HOURS,
                manuallyArchived = server?.let { archiveOverrides.getStringSet(it.id, emptySet()).orEmpty().toSet() }.orEmpty())
        }
        observe(server?.id ?: "setup")
    }

    private suspend fun loadSelected(server: Server) {
        loadArchive(server)
        retryConnection {
            if (server.installed) synchronize(server, everything = true) else probe(server)
        }
    }

    fun removeServer(id: String) = operation {
        val panel = state.value
        if (panel.servers.none { it.id == id }) return@operation
        val remaining = panel.servers.filterNot { it.id == id }
        vault.save(remaining)
        verifiedAgentVersions.edit().remove(id).apply()
        settingsSaved()
        // The connection is removed locally. The remote service and its data are untouched.
        runCatching { DigestNotifications.forget(app, id) }
        runCatching { DigestWorker.remove(app, id, remaining) }
        DigestDeliveryService.reconcile(app, remaining)
        if (panel.server?.id == id) {
            val next = remaining.firstOrNull()
            withContext(Dispatchers.Main.immediate) { selectLocally(next, remaining) }
            if (next != null) loadSelected(next)
        } else mutable.update { it.copy(servers = remaining) }
    }

    fun openNotification(server: String) {
        val profile = state.value.servers.find { it.id == server }
        if (profile == null) {
            pendingNotification = server
        } else if (active?.isActive == true) {
            viewModelScope.launch { active?.join(); openNotification(server) }
        } else if (profile.id != state.value.server?.id) {
            select(profile)
        } else {
            archiveRun = null
            pageRequest = null
            operation { synchronize(profile, everything = false) }
        }
    }

    fun connect(draft: ServerDraft, previous: Server?) {
        if (active?.isActive == true && !activeIsQuiet) return
        reconnectPaused = false
        val server = draft.update(Server("", "", "", 22, "", false, "", "", "", "")) ?: run {
            mutable.update { it.copy(problem = "SERVER_FIELDS", userProblem = "SERVER_FIELDS") }
            return
        }
        val matching = previous?.takeIf { it.sameSshAddress(server) && it.user == server.user }
            ?: state.value.servers.find { it.sameSshAddress(server) && it.user == server.user }
        val profile = if (matching != null) server.copy(id = matching.id, pin = matching.pin, token = matching.token,
            installed = matching.installed, https = matching.https, background = matching.background)
        else server.copy(id = UUID.randomUUID().toString(),
            token = SecureRandom().generateSeed(32).joinToString("") { "%02x".format(it) }, https = "")
        candidate = PendingConnection(profile)
        operation(connectionCheck = true) { retryConnection { probe(profile) } }
    }

    fun editServer(id: String, draft: ServerDraft) {
        if (active?.isActive == true && !activeIsQuiet) return
        val profiles = state.value.servers
        val previous = profiles.firstOrNull { it.id == id } ?: return
        val updated = draft.update(previous)
        val problem = when {
            updated == null -> "SERVER_FIELDS"
            !AgentApi.validHttps(updated.https) -> "HTTPS_URL"
            profiles.any { it.id != id && it.sameSshAddress(updated) && it.user == updated.user } -> "SERVER_FIELDS"
            else -> null
        }
        if (problem != null || updated == null) {
            mutable.update { it.copy(problem = problem.orEmpty(), userProblem = problem.orEmpty()) }
            return
        }
        candidate = PendingConnection(updated, editing = true)
        operation(connectionCheck = true, editedProfile = updated) { verifyServerEdit(updated) }
    }

    fun clearServerEditResult() {
        mutable.update { it.copy(editedServer = "", problem = "") }
    }

    private suspend fun verifyServerEdit(updated: Server) {
        val panel = state.value
        val previous = panel.servers.firstOrNull { it.id == updated.id } ?: return
        val addressChanged = !previous.sameSshAddress(updated)
        val httpsChanged = previous.https != updated.https
        val verifySsh = addressChanged || !previous.sameSshCredentials(updated) || httpsChanged && updated.https.isEmpty()
        val verifyAgentViaSsh = previous.installed && (addressChanged || httpsChanged && updated.https.isEmpty())
        if (verifySsh) {
            retryConnection {
                shell(updated) { session ->
                    // An edited address for an installed profile must still serve its original agent.
                    if (verifyAgentViaSsh) AgentApi(updated.copy(https = ""), session).health()
                }
            }
        }
        if (httpsChanged && updated.https.isNotEmpty()) {
            retryConnection { useTransport { AgentApi(updated).health() } }
        }
        val profiles = panel.servers.map { if (it.id == updated.id) updated else it }
        vault.save(profiles)
        if (addressChanged || httpsChanged) verifiedAgentVersions.edit().remove(updated.id).apply()
        mutable.update {
            it.copy(servers = profiles, server = if (it.server?.id == updated.id) updated else it.server,
                agentVersion = if (it.server?.id == updated.id && (addressChanged || httpsChanged)) "" else it.agentVersion,
                editedServer = updated.id)
        }
        candidate = null
        if (updated.background) {
            DigestDeliveryService.reconcile(app, profiles)
            DigestWorker.sync(app, updated.id, reschedule = true)
        }
        settingsSaved()
    }

    fun confirmHost(approved: Boolean) {
        val received = state.value.hostConfirmation ?: return
        mutable.update { it.copy(hostConfirmation = null) }
        val pending = candidate ?: return
        if (!approved) {
            candidate = null
            return
        }
        val server = pending.server.copy(pin = received.fingerprint)
        candidate = pending.copy(server = server)
        if (pending.editing) operation(connectionCheck = true, editedProfile = server) {
            verifyServerEdit(server)
        } else operation(connectionCheck = true) {
            retryConnection { probe(server) }
        }
    }

    private suspend fun probe(server: Server) {
        connectionStage(ConnectionPhase.Connecting)
        val (report, agentError) = useTransport(server) {
            Ssh.withSession(server) { session ->
                connectionStage(ConnectionPhase.SshAvailable)
                val report = Ssh.probe(app, session).toMutableMap()
                val error = if (report["agent"] == "active" || server.https.isNotEmpty()) {
                    connectionStage(ConnectionPhase.CheckingAgent)
                    try {
                        AgentApi(server, session).health()
                        report["authenticated"] = "yes"
                        null
                    } catch (error: Problem) {
                        report["agent_problem"] = error.code
                        error
                    }
                } else if (server.installed || report["install"] == "success") {
                    Problem("AGENT_INACTIVE").also { report["agent_problem"] = it.code }
                } else null
                report to error
            }
        }
        val authenticated = report["authenticated"] == "yes"
        val wasInstalled = server.installed
        val profile = server.copy(installed = wasInstalled || authenticated,
            background = server.background || authenticated && !wasInstalled && DigestNotifications.deliveryAllowed(app))
        val changedSelection = state.value.server?.id != profile.id
        if (state.value.server != profile || state.value.servers.none { it == profile }) store(profile)
        if (changedSelection) observe(profile.id)
        mutable.update {
            it.copy(probe = report, ready = profile.installed)
        }
        candidate = null
        if (agentError != null) throw agentError
        if (authenticated) synchronize(profile, everything = true, checkConnection = false)
    }

    fun install(password: String) {
        val server = state.value.server ?: return
        val report = state.value.probe
        operation {
            val stage = shell(server) { session ->
                Ssh.install(app, session, server, report["privileges"].orEmpty(),
                    password.ifEmpty { if (!server.keyMode) server.credential else "" })
                Ssh.probe(app, session)
            }
            mutable.update { it.copy(probe = stage) }
        }
    }

    fun attachExisting(password: String) {
        val server = state.value.server ?: return
        operation {
            val token = shell(server) { session ->
                val privileges = Ssh.probe(app, session)["privileges"]
                val command = "/bin/cat /etc/horizon-manager/api-token"
                val input = password.ifEmpty { if (!server.keyMode) server.credential else "" }
                when (privileges) {
                    "root" -> Ssh.exec(session, command)
                    "sudo" -> Ssh.exec(session, "sudo -n -- $command")
                    else -> {
                        if (input.isEmpty() || '\n' in input || '\r' in input) throw Problem("SUDO_REQUIRED")
                        Ssh.exec(session, "sudo -S -p '' -- $command", (input + "\n").toByteArray())
                    }
                }
            }
            if (!token.matches(Regex("[a-f0-9]{64}"))) throw Problem("AUTH_REQUIRED")
            probe(server.copy(token = token))
        }
    }

    fun updateAgent(password: String) {
        val server = state.value.server ?: return
        operation {
            // The VPS installer is limited to 35 minutes. Allow time to reconnect and verify it.
            agentUpdateDeadline = android.os.SystemClock.elapsedRealtime() + 36 * 60_000L
            mutable.update { it.copy(updatingAgent = true) }
            try {
                val stage = shell(server) { session ->
                    val before = Ssh.probe(app, session)
                    // After reopening the app, a previous attempt may still be installing on the VPS.
                    if (before["install"] == "running") {
                        installOperation = ""
                        before
                    } else {
                        installOperation = before["operation"].orEmpty()
                        Ssh.install(app, session, server, before["privileges"].orEmpty(),
                            password.ifEmpty { if (!server.keyMode) server.credential else "" })
                        Ssh.probe(app, session)
                    }
                }
                mutable.update { it.copy(probe = stage) }
            } catch (error: Exception) {
                mutable.update { it.copy(updatingAgent = false) }
                throw error
            }
        }
    }

    private fun rememberAgentVersion(server: Server, version: String) {
        if (state.value.server?.id != server.id || !isAgentVersion(version)) return
        if (verifiedAgentVersions.getString(server.id, null) != version)
            verifiedAgentVersions.edit().putString(server.id, version).apply()
        mutable.update { it.copy(agentVersion = version) }
    }

    private suspend fun checkAgentUpdate(server: Server): String? {
        val report = shell(server) { Ssh.probe(app, it) }
        mutable.update { it.copy(probe = report) }
        val fresh = report["operation"] != installOperation
        if (fresh && report["install"] == "failed") {
            throw Problem(if (report["code"] == "30") "ACTIVE_JOB" else "AGENT_UPDATE_FAILED",
                report["code"].orEmpty())
        }
        if (!fresh || report["install"] != "success") {
            if (android.os.SystemClock.elapsedRealtime() >= agentUpdateDeadline) throw Problem("AGENT_UPDATE_FAILED")
            return null
        }
        val version = withAgent(server) { it.health().optString("agent") }
        if (!isAgentVersion(version)) throw Problem("AGENT_PROTOCOL")
        if (!agentCompatible(version)) throw Problem("AGENT_UPDATE_FAILED")
        return version
    }

    private suspend fun finishAgentUpdate(server: Server, version: String) {
        rememberAgentVersion(server, version)
        mutable.update { it.copy(updatingAgent = false, schema = JSONObject(), problem = "", userProblem = "") }
        updateNotices.trySend(Unit)
        DigestDeliveryService.reconcile(app, state.value.servers, restart = server.id)
        DigestWorker.sync(app, server.id, reschedule = true)
        synchronize(server, everything = true)
    }

    fun refreshInstallation(quiet: Boolean = true) {
        if (!quiet) reconnectPaused = false
        if (reconnectPaused || quiet && android.os.SystemClock.elapsedRealtime() < quietRetryAfter) return
        val server = state.value.server ?: return
        operation(quiet, connectionCheck = true) { retryConnection { probe(server) } }
    }

    private suspend fun <T> shell(server: Server, action: (com.jcraft.jsch.Session) -> T): T =
        useTransport(server) { Ssh.withSession(server, action) }

    private suspend fun <T> withAgent(server: Server, checkingConnection: Boolean = false,
                                     action: (AgentApi) -> T): T {
        if (checkingConnection) connectionStage(ConnectionPhase.Connecting)
        if (server.https.isNotEmpty()) return useTransport {
            if (checkingConnection) connectionStage(ConnectionPhase.CheckingAgent)
            action(AgentApi(server))
        }
        return shell(server) { session ->
            if (checkingConnection) connectionStage(ConnectionPhase.SshAvailable)
            action(AgentApi(server, session))
        }
    }

    fun coverRelay(panel: Panel, articleId: String): (suspend () -> ByteArray?)? {
        val server = panel.server ?: return null
        if (!panel.status.optBoolean("cover_relay")) return null
        return coverRelay(server, panel.selectedRun, articleId)
    }

    fun coverRelay(server: Server?, run: String, articleId: String): (suspend () -> ByteArray?)? {
        if (server?.installed != true || run.isBlank()) return null
        return {
            try {
                val result = withAgent(server) {
                    it.get("covers/$run/${contextHash(articleId)}") as JSONObject
                }
                val encoded = result.optString("image")
                if (encoded.length in 1..6_666_668) android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                else null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun loadArchive(server: Server) {
        val hidden = cache.hiddenDigests(server.id).toSet()
        val history = cache.archive(server.id)?.let { JSONArray(it).objects() }.orEmpty()
        if (state.value.server?.id != server.id) return
        mutable.update { it.copy(archive = history, hiddenDigests = hidden, hasMoreDigests = history.size >= DIGEST_PAGE_SIZE) }
        state.value.feedIssue(history)?.let { latest ->
            val lang = digestLanguage(latest.getJSONArray("languages").strings(), state.value.preferredDigestLanguage()) ?: return@let
            val run = latest.getString("id")
            showCached(server.id, run, lang)
        }
    }

    private suspend fun showCached(server: String, run: String, language: String): Boolean {
        val data = cache.digest(server, run, language).map { JSONObject(it.data) }
        if (data.isEmpty()) return false
        mutable.update {
            if (it.server?.id != server || run in it.hiddenDigests ||
                (run in it.manuallyArchived && archiveRun != run)) return@update it
            it.copy(selectedRun = run, language = language, markdown = data.first().optString("markdown"),
                articles = data.flatMap { page -> page.getJSONArray("items").objects() }.distinctBy { card -> card.optString("id") },
                total = data.first().optInt("total"))
        }
        return true
    }

    private suspend fun synchronize(server: Server, everything: Boolean, checkConnection: Boolean = true) {
        mutable.update { it.copy(syncing = true) }
        try {
            fetchPanel(server, everything, checkConnection)
        } finally {
            mutable.update { it.copy(syncing = false) }
        }
    }

    private class DigestUpgrades {
        val translations = mutableSetOf<Int>()
        val originalLinks = mutableSetOf<Int>()
        val primaryAnalysis = mutableSetOf<Int>()

        fun pending(version: Int): Set<Int> =
            (if (version >= 2) translations else emptySet()) + (if (version >= 3) originalLinks else emptySet()) +
                (if (version >= 4) primaryAnalysis else emptySet())
    }

    private suspend fun staleDigestOffsets(server: String, run: String, language: String): DigestUpgrades {
        val upgrades = DigestUpgrades()
        for (page in cache.digest(server, run, language)) {
            val value = JSONObject(page.data)
            val version = value.optInt("content_version")
            if (version < 4) upgrades.primaryAnalysis.add(page.offset)
            if (version >= 3) continue
            val items = value.optJSONArray("items")?.objects().orEmpty()
            if (version < 2 && items.any {
                it.optString("title") in setOf("Translation unavailable", "暂时无法翻译", "Перевод недоступен")
            }) upgrades.translations.add(page.offset)
            if (items.any {
                articleField(it, "source").lowercase(java.util.Locale.ROOT) in setOf("reddit", "hackernews", "telegram") &&
                    originalPublicationUrl(it) == null
            }) upgrades.originalLinks.add(page.offset)
        }
        return upgrades
    }

    private suspend fun fetchPanel(server: Server, everything: Boolean, checkConnection: Boolean) {
        val cursor = cache.eventCursor(server.id)
        val current = state.value
        val stale = if (current.selectedRun.isNotEmpty())
            staleDigestOffsets(server.id, current.selectedRun, current.language) else DigestUpgrades()
        val checking = checkConnection && (everything || state.value.connectionPhase != ConnectionPhase.Connected)
        val remote = withAgent(server, checkingConnection = checking) { api ->
            if (checking) connectionStage(ConnectionPhase.CheckingAgent)
            val status = api.status()
            connected(server)
            val version = status.optString("agent")
            if (!isAgentVersion(version)) throw Problem("AGENT_PROTOCOL")
            rememberAgentVersion(server, version)
            if (!agentCompatible(version)) return@withAgent null
            status.optJSONObject("article_options")?.let { options ->
                val serialized = options.toString()
                if (articleDisplay.getString(server.id, null) != serialized)
                    articleDisplay.edit().putString(server.id, serialized).apply()
            }
            if (status.has("digest_language") && !status.optBoolean("digest_language_chosen")) {
                val accepted = status.optJSONArray("digest_languages")?.strings() ?: legacyDigestLanguages
                val language = digestLocale()
                // Leave an old agent's default uncommitted so the next upgrade can use the device language.
                if (language in accepted) {
                    val selected = api.put("digest-language", JSONObject()
                        .put("language", language).put("initialize", true)) as JSONObject
                    status.put("digest_language", selected.getString("language")).put("digest_language_chosen", true)
                }
            }
            val head = (api.get("digests?limit=$DIGEST_PAGE_SIZE") as JSONArray).objects()
            val panel = state.value
            val run = archiveRun
            val older = panel.archive.drop(DIGEST_PAGE_SIZE)
            val config = if (everything || panel.config.length() == 0) api.get("config?include_ai_providers=true") as JSONObject else panel.config
            val hours = collectionHours(config, panel.collectionHours)
            val selected = if (run == null) panel.feedIssue(head + older, hours) else head.find { it.optString("id") == run }
                ?: if (older.any { it.optString("id") == run }) {
                    runCatching { api.get("digest-info/$run") as JSONObject }
                        .getOrElse { older.find { it.optString("id") == run } }
                } else null
            val archive = (head + listOfNotNull(selected) + older).distinctBy { it.optString("id") }
            val hasMore = if (older.isNotEmpty()) panel.hasMoreDigests else head.size == DIGEST_PAGE_SIZE
            val lang = selected?.getJSONArray("languages")?.strings()?.let {
                digestLanguage(it, status.optString("digest_language", digestLocale()))
            }
            val changed = selected?.getString("id") != panel.selectedRun || lang != panel.language
            val repairVersion = when {
                status.optInt("digest_content_version") >= 4 -> 4
                status.optBoolean("article_source_links") -> 3
                status.optBoolean("cover_relay") -> 2
                else -> 1
            }
            val needsRepair = !changed && stale.pending(repairVersion).isNotEmpty()
            val digest = if ((everything || changed || needsRepair) && selected != null && lang != null) {
                api.get("digests/${selected.getString("id")}/$lang") as JSONObject
            } else null
            val repairs = if (needsRepair && digest != null) {
                val id = digest.getString("id")
                stale.pending(digest.optInt("content_version")).filter { it != 0 }.map { offset ->
                    offset to (api.get("digests/$id/$lang?offset=$offset") as JSONObject)
                }
            } else emptyList()
            val schema = if (panel.schema.length() == 0) api.get("schema") as JSONObject else panel.schema
            val schedule = if (everything || panel.schedule.length() == 0) api.get("schedule") as JSONObject
                else if (status.has("next")) panel.schedule.copyObject().put("next", status.get("next"))
                else panel.schedule
            val sources = (api.get("sources") as JSONArray).objects()
            val jobs = (api.get("jobs") as JSONArray).objects()
            val events = journalEvents(api, cursor)
            val media = if (!changed && selected?.optBoolean("media_ready") == true &&
                panel.articles.any { !it.has("media") }) {
                val id = selected.getString("id")
                runCatching { Triple(id, requireNotNull(lang), api.get("media/$id") as JSONObject) }.getOrNull()
            } else null
            Refresh(archive, hasMore, digest, config, schema, schedule, sources, status, jobs, events, media, repairs)
        } ?: return
        cache.saveArchive(CachedArchive(server.id, JSONArray(remote.archive).toString()))
        val hours = collectionHours(remote.config, state.value.collectionHours)
        if (hours != state.value.collectionHours) archiveSettings.edit().putInt(server.id, hours).apply()
        if (remote.digest != null) {
            val (run, lang) = saveDigest(server.id, remote.digest, 0)
            remote.repairs.forEach { (offset, page) -> saveDigest(server.id, page, offset) }
            showCached(server.id, run, lang)
        }
        remote.media?.let { (id, language, media) ->
            cache.mergeArtwork(server.id, id, language, media)
            if (state.value.selectedRun == id) showCached(server.id, id, language)
        }
        saveJournalEvents(server, remote.events)
        mutable.update { panel ->
            if (panel.server?.id != server.id) return@update panel
            panel.copy(archive = remote.archive, hasMoreDigests = remote.hasMore, collectionHours = hours,
                syncedDigest = remote.digest?.optString("id") ?: panel.syncedDigest,
                config = remote.config, schema = remote.schema,
                schedule = remote.schedule, sourceHistory = remote.sources, status = remote.status, jobs = remote.jobs,
                problem = if (panel.problem.startsWith("AGENT_") || panel.problem.startsWith("SSH_")) "" else panel.problem)
        }
    }

    private fun journalEvents(api: AgentApi, cursor: Long): List<JSONObject> {
        val route = if (cursor == 0L) "events?limit=1000&latest=true" else "events?after=$cursor&limit=1000"
        return (api.get(route) as JSONArray).objects()
    }

    private suspend fun saveJournalEvents(server: Server, events: List<JSONObject>) {
        if (events.isNotEmpty()) cache.saveEvents(events.map {
            LocalEvent(server.id, "remote", it.getLong("id"), it.getString("time"), it.toString())
        })
        if (System.currentTimeMillis() - journalSync > 3_600_000) {
            cache.pruneEvents(server.id, Instant.now().minus(45, ChronoUnit.DAYS).toString())
            journalSync = System.currentTimeMillis()
        }
    }

    private suspend fun synchronizeJournal(server: Server) {
        val cursor = cache.eventCursor(server.id)
        val (jobs, events) = withAgent(server) { api ->
            (api.get("jobs") as JSONArray).objects() to journalEvents(api, cursor)
        }
        connected(server)
        saveJournalEvents(server, events)
        mutable.update { if (it.server?.id == server.id) it.copy(jobs = jobs) else it }
    }


    private fun collectionHours(config: JSONObject, fallback: Int) =
        config.optJSONObject("config")?.optJSONObject("collection")
            ?.optInt("time_window_hours", DEFAULT_COLLECTION_HOURS)?.coerceIn(1, 8760) ?: fallback

    private data class Refresh(
        val archive: List<JSONObject>, val hasMore: Boolean, val digest: JSONObject?, val config: JSONObject, val schema: JSONObject,
        val schedule: JSONObject, val sources: List<JSONObject>, val status: JSONObject, val jobs: List<JSONObject>,
        val events: List<JSONObject>, val media: Triple<String,String,JSONObject>?, val repairs: List<Pair<Int, JSONObject>>,
    )

    private suspend fun saveDigest(server: String, page: JSONObject, offset: Int): Pair<String, String> {
        val run = page.getString("id")
        val language = page.getString("language")
        cache.saveDigest(CachedDigest(server, run, language, offset, page.toString()))
        return run to language
    }

    fun refresh(quiet: Boolean = false, journalOnly: Boolean = false) {
        if (!quiet) reconnectPaused = false
        if (reconnectPaused || quiet && android.os.SystemClock.elapsedRealtime() < quietRetryAfter) return
        val panel = state.value
        val server = panel.server ?: return
        if (!server.installed) return
        if (quiet && !panel.updatingAgent && panel.connectionProblem.isNotBlank() &&
            !isTransientConnectionFailure(panel.connectionProblem)) return
        operation(quiet, connectionCheck = !panel.updatingAgent) {
            if (state.value.updatingAgent) {
                val version = try {
                    retryConnection { checkAgentUpdate(server) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutable.update { it.copy(updatingAgent = false) }
                    throw error
                }
                if (version != null) finishAgentUpdate(server, version)
            } else if (journalOnly && state.value.connectionPhase == ConnectionPhase.Connected) {
                retryConnection { synchronizeJournal(server) }
            } else {
                retryConnection { synchronize(server, everything = !quiet || state.value.selectedRun.isEmpty()) }
            }
        }
    }

    fun leaveArchive() {
        archiveRun = null
        pageRequest = null
    }

    fun showFeed() {
        val server = state.value.server?.takeIf { it.installed } ?: return
        archiveRun = null
        if (active?.isActive == true && !activeIsQuiet) {
            viewModelScope.launch {
                active?.join()
                if (archiveRun == null && state.value.server?.id == server.id) showFeed()
            }
            return
        }
        val latest = state.value.feedIssue()
        if (latest?.optString("id") == state.value.selectedRun && state.value.markdown.isNotEmpty()) return
        operation {
            val run = latest?.optString("id")
            val lang = latest?.optJSONArray("languages")?.strings()?.let { digestLanguage(it, state.value.preferredDigestLanguage()) }
            if (run == null || lang == null || !showCached(server.id, run, lang)) synchronize(server, everything = true)
        }
    }

    fun archiveDigest(serverId: String, run: String): Boolean {
        val panel = state.value
        if (panel.server?.id != serverId || run.isBlank() || run in panel.manuallyArchived ||
            run in panel.hiddenDigests || panel.archive.none { it.optString("id") == run }) return false
        val archived = panel.manuallyArchived + run
        archiveOverrides.edit().putStringSet(serverId, archived).apply()
        mutable.update { current ->
            if (current.server?.id != serverId) current
            else if (current.selectedRun == run && archiveRun != run) {
                current.copy(manuallyArchived = archived, selectedRun = "", markdown = "", articles = emptyList(), total = 0)
            } else current.copy(manuallyArchived = archived)
        }
        if (state.value.feedIssue() != null) showFeed()
        return true
    }

    fun openArchive(issue: JSONObject) {
        val run = issue.getString("id")
        if (run in state.value.hiddenDigests) return
        val lang = digestLanguage(issue.getJSONArray("languages").strings(), state.value.preferredDigestLanguage()) ?: return
        archiveRun = run
        openDigest(run, lang, offset = 0)
    }

    fun deleteArchive(run: String) {
        val panel = state.value
        val server = panel.server ?: return
        if (splitDigests(panel.archive, panel.collectionHours, Instant.now(), panel.manuallyArchived,
                panel.hiddenDigests).archive.none { it.optString("id") == run }) return
        operation {
            cache.deleteDigest(server.id, run)
            mutable.update { current ->
                if (current.server?.id == server.id) current.copy(hiddenDigests = current.hiddenDigests + run)
                else current
            }
        }
    }

    fun openDigest(run: String, language: String, offset: Int) {
        val server = state.value.server ?: return
        operation {
            val cached = offset == 0 && showCached(server.id, run, language)
            if (offset == 0 && !cached)
                mutable.update { it.copy(selectedRun = run, language = language, articles = emptyList(), markdown = "", total = 0) }
            val stale = if (offset == 0) staleDigestOffsets(server.id, run, language) else DigestUpgrades()
            val pages = withAgent(server) { api ->
                val page = api.get("digests/$run/$language?offset=$offset") as JSONObject
                val repairs = stale.pending(page.optInt("content_version")).filter { it != offset }.map { cursor ->
                    cursor to (api.get("digests/$run/$language?offset=$cursor") as JSONObject)
                }
                listOf(offset to page) + repairs
            }
            pages.forEach { (cursor, page) -> saveDigest(server.id, page, cursor) }
            if (offset == 0) mutable.update { it.copy(syncedDigest = run) }
            showCached(server.id, run, language)
        }
    }

    fun cancelChronology(run: String, language: String) {
        val request = pageRequest
        if (request?.run == run && request.language == language) pageRequest = null
    }

    fun loadChronology(run: String, language: String) {
        val panel = state.value
        val server = panel.server ?: return
        if (panel.selectedRun != run || panel.language != language || panel.articles.size >= panel.total) return
        if (pageRequest?.let { it.run == run && it.language == language } == true) return
        val request = PageRequest(run, language)
        pageRequest = request
        val blocking = active?.takeIf { it.isActive && !activeIsQuiet }
        if (blocking != null) {
            viewModelScope.launch {
                blocking.join()
                if (pageRequest === request) {
                    pageRequest = null
                    loadChronology(run, language)
                }
            }
            return
        }
        operation {
            if (pageRequest !== request) return@operation
            val downloaded = mutableListOf<Pair<Int, JSONObject>>()
            try {
                val pages = cache.digest(server.id, run, language)
                var offset = 0
                for (page in pages) {
                    if (page.offset > offset) break
                    offset = maxOf(offset, page.offset + JSONObject(page.data).getJSONArray("items").length())
                }
                var total = state.value.total
                if (offset < total && pageRequest === request) withAgent(server) { api ->
                    while (offset < total && pageRequest === request) {
                        val page = api.get("digests/$run/$language?offset=$offset&limit=40") as JSONObject
                        val count = page.getJSONArray("items").length()
                        if (count == 0) throw Problem("AGENT_PROTOCOL")
                        downloaded += offset to page
                        offset += count
                        total = page.getInt("total")
                    }
                }
            } finally {
                downloaded.forEach { (offset, page) -> saveDigest(server.id, page, offset) }
                if (pageRequest === request) {
                    pageRequest = null
                    showCached(server.id, run, language)
                }
            }
        }
    }

    fun loadMoreDigests() {
        val server = state.value.server ?: return
        operation {
            val catalog = state.value.archive
            val cursor = catalog.lastOrNull()?.getString("id") ?: return@operation
            val page = withAgent(server) {
                (it.get("digests?limit=$DIGEST_PAGE_SIZE&after=$cursor") as JSONArray).objects()
            }
            val extended = (catalog + page).distinctBy { it.optString("id") }
            cache.saveArchive(CachedArchive(server.id, JSONArray(extended).toString()))
            mutable.update { it.copy(archive = extended, hasMoreDigests = page.size == DIGEST_PAGE_SIZE && extended.size > catalog.size) }
        }
    }

    fun flag(article: JSONObject, save: Boolean? = null, read: Boolean? = null) {
        val panel = state.value
        val server = panel.server ?: return
        val id = article.getString("id")
        val snapshot = if (save == true) SavedPublication(server.id, id, panel.selectedRun, panel.language,
            System.currentTimeMillis(), article.toString()) else null
        flag(server.id, id, save, read, snapshot)
    }

    fun flag(publication: SavedPublication, save: Boolean? = null, read: Boolean? = null) {
        flag(publication.server, publication.article, save, read,
            if (save == true) publication.copy(savedAt = System.currentTimeMillis()) else null)
    }

    suspend fun refreshSavedArticle(snapshot: SavedPublication) {
        if (snapshot.run.isBlank() || snapshot.language.isBlank()) return
        val server = state.value.servers.find { it.id == snapshot.server && it.installed } ?: return
        try {
            val article = withAgent(server) { api ->
                val key = android.net.Uri.encode(snapshot.article)
                val page = api.get("digests/${snapshot.run}/${snapshot.language}?article=$key") as JSONObject
                page.optJSONArray("items")?.objects()?.find {
                    it.optString("id") == snapshot.article && it.optBoolean("summary_from_analysis")
                }
            } ?: return
            cache.refreshSavedArticle(snapshot.server, snapshot.article, snapshot.run, snapshot.language, article.toString())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A favorite still opens offline with its cached metadata and source link.
        }
    }

    private fun flag(serverId: String, article: String, save: Boolean?, read: Boolean?, snapshot: SavedPublication?) {
        viewModelScope.launch {
            try {
                cache.updateFlag(serverId, article, save, read, snapshot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.value.servers.find { it.id == serverId }?.let { record(error, it) }
            }
        }
    }

    fun submit(kind: String = "run", source: String? = null, bucket: String? = null, index: Int? = null) =
        mutate { api ->
            api.post("jobs", JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind)
                .apply { source?.let { put("source", it) }; bucket?.let { put("bucket", it) }; index?.let { put("index", it) } })
            if (kind == "run") state.value.server?.takeIf { it.background }?.let { DigestWorker.sync(app, it.id) }
        }

    private val modelChecks = MutableStateFlow<Map<String, SavedModelCheck>>(emptyMap())
    val savedModelChecks = modelChecks.asStateFlow()

    fun testSavedModel(serverId: String, revision: Int, selected: JSONObject) {
        val panel = state.value
        val server = panel.server ?: return
        val savedAi = panel.config.optJSONObject("config")?.optJSONObject("ai") ?: return
        if (panel.busy || server.id != serverId || panel.config.optInt("revision") != revision ||
            savedAi.toString() != selected.toString() || modelChecks.value[server.id]?.pending == true) return
        val model = savedAi.optString("model")
        if (model.isBlank()) return
        fun show(pending: Boolean, error: String = "") {
            modelChecks.update { it + (server.id to SavedModelCheck(model, revision, pending, error)) }
        }
        show(pending = true)
        viewModelScope.launch {
            try {
                val job = UUID.randomUUID().toString()
                val jobId = job.replace("-", "")
                retryConnection { withAgent(server) { api ->
                    api.post("jobs", JSONObject().put("id", job).put("kind", "ai").put("config_revision", revision))
                } }
                val result = withTimeoutOrNull(115_000) {
                    while (true) {
                        val probe = retryConnection {
                            withAgent(server) { it.get("jobs/$jobId") as JSONObject }
                        }
                        if (probe.optString("state") !in setOf("queued", "running")) return@withTimeoutOrNull probe
                        delay(2000)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                } ?: throw Problem("TIMEOUT")
                if (result.optString("state") == "success" && result.optJSONObject("result")?.optBoolean("verified") == true) {
                    show(pending = false)
                } else {
                    show(pending = false, result.optString("phase").takeUnless { it in setOf("", "queued", "complete") }
                        ?: "UPSTREAM_FAILURE")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                show(pending = false, (error as? Problem)?.code ?: "AGENT_UNREACHABLE")
            }
        }
    }

    private val chatgptLoginState = MutableStateFlow<ChatGptLoginStatus?>(null)
    val chatgptLogin = chatgptLoginState.asStateFlow()
    private var chatgptTask: Job? = null

    suspend fun chatgptAccount(serverId: String): JSONObject {
        val server = state.value.server?.takeIf { it.id == serverId } ?: throw Problem("AGENT_UNREACHABLE")
        return withAgent(server) { it.get("ai/chatgpt") as JSONObject }
    }

    suspend fun chatgptModels(serverId: String): List<RouterModel> {
        val server = state.value.server?.takeIf { it.id == serverId } ?: throw Problem("AGENT_UNREACHABLE")
        return RouterCatalog.parse(withAgent(server) { it.get("ai/chatgpt/models").toString() })
    }

    fun signInChatGpt(serverId: String, openBrowser: (String) -> Unit) {
        val server = state.value.server?.takeIf { it.id == serverId } ?: return
        if (chatgptTask?.isActive == true) return
        chatgptLoginState.value = ChatGptLoginStatus(server.id, waiting = true)
        chatgptTask = viewModelScope.launch {
            var attemptPath = ""
            try {
                val started = withAgent(server) { it.post("ai/chatgpt/login") as JSONObject }
                val id = started.optString("id")
                if (!id.matches(Regex("[a-f0-9]{32}"))) throw Problem("AGENT_PROTOCOL")
                attemptPath = "ai/chatgpt/login/$id"
                val url = started.getString("url")
                val callback = withContext(Dispatchers.IO) {
                    ChatGptCallback(url).use { listener ->
                        withContext(Dispatchers.Main) { openBrowser(url) }
                        listener.await()
                    }
                }
                if (callback.has("error")) throw Problem("CHATGPT_LOGIN_CANCELLED")
                var status = retryConnection { withAgent(server) { it.post(attemptPath, callback) as JSONObject } }
                val result = withTimeoutOrNull(60_000) {
                    while (status.optString("state") in setOf("checking", "waiting")) {
                        delay(1000)
                        status = retryConnection { withAgent(server) { it.get(attemptPath) as JSONObject } }
                    }
                    status
                } ?: throw Problem("CHATGPT_LOGIN_EXPIRED")
                if (result.optString("state") != "connected")
                    throw Problem(result.optString("code").ifBlank { "CHATGPT_LOGIN_FAILED" })
                chatgptLoginState.value = ChatGptLoginStatus(server.id, waiting = false)
            } catch (error: CancellationException) {
                chatgptLoginState.value = ChatGptLoginStatus(server.id, waiting = false, error = "CHATGPT_LOGIN_CANCELLED")
                throw error
            } catch (error: Exception) {
                chatgptLoginState.value = ChatGptLoginStatus(server.id, waiting = false,
                    error = (error as? Problem)?.code ?: "CHATGPT_LOGIN_FAILED")
            } finally {
                if (attemptPath.isNotEmpty() && chatgptLoginState.value?.error?.isNotEmpty() == true) {
                    withContext(NonCancellable) { runCatching { withAgent(server) { it.delete(attemptPath) } } }
                }
            }
        }
    }

    fun cancelChatGptLogin(serverId: String) {
        if (chatgptLoginState.value?.server == serverId) chatgptTask?.cancel()
    }

    fun checkDigestNotifications() {
        val servers = state.value.servers
        DigestDeliveryService.reconcile(app, servers)
        if (deliveryProfiles(servers).isNotEmpty() && DigestNotifications.deliveryAllowed(app))
            DigestWorker.catchUp(app)
    }

    fun translateDigest(run: String) = mutate { api ->
        api.post("jobs", JSONObject().put("id", UUID.randomUUID().toString())
            .put("kind", "localize").put("run", run))
    }

    fun stop(id: String) = mutate { it.post("jobs/$id/stop") }

    suspend fun loadRouterCatalog(serverId: String, router: AiRouter, enteredKey: String?): List<RouterModel>? =
        withContext(Dispatchers.IO) {
            val server = state.value.server?.takeIf { it.id == serverId } ?: return@withContext null
            try {
                val catalog = withAgent(server) { api ->
                    val path = "ai/catalog/${router.id}"
                    if (enteredKey == null) api.get(path) else api.post(path, JSONObject().put("key", enteredKey))
                }
                RouterCatalog.parse(catalog.toString())
            } catch (error: Problem) {
                when (error.code) {
                    "ROUTER_KEY_MISSING" -> null
                    "API_404" -> throw Problem("AGENT_UPDATE_REQUIRED")
                    else -> throw error
                }
            }
        }

    suspend fun getArticleContext(
        serverId: String, run: String, article: String, language: String, submit: Boolean,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (!Regex("(?:[a-f0-9]{32}|run-[0-9]{8}T[0-9]{6}Z-[a-f0-9]{8})").matches(run) ||
            language !in digestLanguages || article.isBlank()) {
            throw Problem("ARTICLE_UNKNOWN")
        }
        val articleKey = contextHash(article)
        val directory = File(app.cacheDir, "article-context")
        val file = AtomicFile(File(directory, contextHash("$serverId/$run/$articleKey/$language") + ".json"))
        runCatching { JSONObject(file.readFully().toString(Charsets.UTF_8)) }.getOrNull()?.let {
            if (it.optString("state") == "ready") return@withContext it
        }
        val panel = state.value
        val server = panel.server?.takeIf { it.id == serverId } ?: panel.servers.find { it.id == serverId }
            ?: throw Problem("AGENT_UNREACHABLE")
        val path = "article-context/$run/$articleKey/$language"
        val result = try {
            withAgent(server) { (if (submit) it.post(path) else it.get(path)) as JSONObject }
        } catch (error: Problem) {
            if (error.code == "API_404") throw Problem("AGENT_UPDATE_REQUIRED")
            throw error
        }
        if (result.optString("state") == "ready") {
            runCatching {
                directory.mkdirs()
                val output = file.startWrite()
                try {
                    output.write(result.toString().toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
            }
        }
        result
    }

    fun setDigestLanguage(language: String) = mutate(setting = true) { api ->
        val supported = state.value.status.optJSONArray("digest_languages")?.strings() ?: legacyDigestLanguages
        if (language !in supported) throw Problem("AGENT_UPDATE_REQUIRED")
        api.put("digest-language", JSONObject().put("language", language))
    }

    fun setHeadlineRange(minimum: Int, maximum: Int) = mutate(setting = true) { api ->
        val serverId = state.value.server?.id
        val confirmed = api.put("headline-range", JSONObject().put("minimum", minimum).put("maximum", maximum)) as JSONObject
        mutable.update {
            if (it.server?.id == serverId) it.copy(status = it.status.copyObject().put("headline_range", confirmed)) else it
        }
    }

    fun setArticleTextMode(mode: String) = mutate(setting = true) { api ->
        api.put("article-options", JSONObject().put("card_text_mode", mode))
    }

    fun setShowScoreReason(enabled: Boolean) = mutate(setting = true) { api ->
        api.put("article-options", JSONObject().put("show_score_reason", enabled))
    }

    fun enrichmentMode(mode: String) = mutate(setting = true) { api ->
        api.put("enrichment-mode", JSONObject().put("mode", mode))
    }

    fun mediaSearch(enabled: Boolean) = mutate(setting = true) { api ->
        api.put("media-search", JSONObject().put("enabled", enabled))
    }

    fun saveConfig(config: JSONObject, profiles: JSONArray, secrets: JSONObject = JSONObject(),
                   expectedRevision: Int? = null) =
        saveConfiguration("config?include_ai_providers=true",
            JSONObject().put("config", config).put("profiles", profiles).put("secrets", secrets), expectedRevision)

    fun saveProfileNewsText(changes: JSONObject, revision: Int) =
        saveConfiguration("profile-news-text", JSONObject().put("profiles", changes), revision)

    private fun saveConfiguration(path: String, values: JSONObject, expectedRevision: Int? = null) {
        val panel = state.value
        val server = panel.server ?: return
        val revision = expectedRevision ?: panel.config.optInt("revision")
        operation {
            if (state.value.server?.id != server.id) return@operation
            val savedConfig = withAgent(server) { api ->
                api.put(path, values.put("revision", revision)) as JSONObject
            }
            if (state.value.server?.id != server.id) return@operation
            // The PUT response already contains the new config revision. Forms must see it
            // before the success notice, even when the subsequent panel refresh is slow.
            mutable.update { if (it.server?.id == server.id) it.copy(config = savedConfig) else it }
            settingsSaved()
            synchronize(server, everything = true)
        }
    }

    fun check() = inspect {
        val result = it.post("config/check") as JSONObject
        val missing = result.getJSONArray("missing").strings()
        val enabled = result.getJSONArray("enabled_sources").length()
        ui(R.string.ui_enabled_sources, enabled) +
            (if (!result.optBoolean("model_configured")) ui(R.string.ui_select_an_ai_model) else "") +
            (if (missing.isEmpty()) "" else ui(R.string.ui_add, missing.joinToString()))
    }

    fun checkVersions() = inspect {
        val remote = it.get("upstream") as JSONObject
        val head = remote.getString("upstream_head")
        val installed = remote.getString("installed")
        if (head == installed) ui(R.string.ui_the_installed_revision_matches_the_main)
        else ui(R.string.ui_github_revision_verified_for_this_build, head.take(12), installed.take(12))
    }

    fun refreshSchedule(quiet: Boolean = true) {
        val server = state.value.server?.takeIf { it.installed } ?: return
        operation(quiet) {
            val schedule = withAgent(server) { it.get("schedule") as JSONObject }
            mutable.update { if (it.server?.id == server.id) it.copy(schedule = schedule) else it }
        }
    }

    fun schedule(spec: JSONObject) {
        val server = state.value.server ?: return
        operation {
            val saved = withAgent(server) { it.put("schedule", spec) as JSONObject }
            settingsSaved()
            if (server.background) DigestWorker.sync(app, server.id, reschedule = true)
            mutable.update {
                if (it.server?.id != server.id) it
                else it.copy(schedule = saved, status = it.status.copyObject().put("next", saved.opt("next")))
            }
        }
    }

    fun export(password: String) = mutate {
        val result = it.post("backup", JSONObject().put("password", password)) as JSONObject
        mutable.update { panel -> panel.copy(file = result.getString("archive"), deviceFile = false) }
    }

    fun restore(file: android.net.Uri, password: String) = mutate(full = true, setting = true) {
        val archive = BackupDocument.read(app, file)
        it.post("restore", JSONObject().put("password", password).put("archive", archive)
            .put("revision", state.value.config.getInt("revision")))
    }

    fun exportDevice(password: String) = operation {
        val panel = state.value
        val settings = DeviceBackup.read(app, vault.load(), panel.server?.id.orEmpty(), cache.backupFavorites())
        val encoded = DeviceBackup.pack(settings, password)
        mutable.update { it.copy(file = encoded, deviceFile = true) }
    }

    fun restoreDevice(file: android.net.Uri, password: String) = operation {
        val incoming = DeviceBackup.unpack(BackupDocument.read(app, file), password)
        val previous = vault.load()
        val profiles = (previous + incoming.servers).associateBy { it.id }.values.toList()
        vault.save(profiles)
        cache.restoreFavorites(incoming.favorites)
        val selected = incoming.selected.ifEmpty { state.value.server?.id.orEmpty() }
        val next = profiles.find { it.id == selected }
        withContext(Dispatchers.Main.immediate) {
            app.getSharedPreferences("reading", 0).edit().putString("default_order", incoming.order.name).apply()
            incoming.icon.apply(app)
            selectLocally(next, profiles)
        }
        DigestWorker.schedule(app, profiles)
        DigestDeliveryService.reconcile(app, profiles)
        val previouslyEnabled = previous.filter { it.background }.map(Server::id).toSet()
        for (profile in profiles.filter { it.installed }) {
            if (!profile.background && profile.id in previouslyEnabled) {
                DigestNotifications.resetMarker(app, profile.id)
                DigestWorker.cancelChecks(app, profile.id)
            }
            if (profile.background) DigestWorker.sync(app, profile.id, reschedule = true)
        }
        settingsSaved()
        if (next != null) {
            try {
                loadSelected(next)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                record(error, next, announce = false)
            }
        }
    }

    fun clearEvents() {
        val server = state.value.server ?: return
        operation {
            val before = cache.eventCursor(server.id)
            if (before > 0) withAgent(server) { it.delete("events?before=$before") }
            cache.clearEvents(server.id, before)
            synchronize(server, everything = false)
        }
    }

    fun settings(url: String, background: Boolean) {
        val server = state.value.server ?: return
        operation {
            if (!AgentApi.validHttps(url)) throw Problem("HTTPS_URL")
            val updated = server.copy(https = url.trim(), background = background)
            if (updated.https != server.https) {
                withAgent(updated, checkingConnection = true) {
                    connectionStage(ConnectionPhase.CheckingAgent)
                    it.status()
                }
            }
            store(updated)
            if (updated.https != server.https) connected(updated)
            settingsSaved()
        }
    }

    fun restart(password: String) {
        val server = state.value.server ?: return
        operation {
            shell(server) { session ->
                val report = Ssh.probe(app, session)
                val command = "/bin/systemctl restart horizon-manager.service"
                val input = password.ifEmpty { if (!server.keyMode) server.credential else "" }
                when (report["privileges"]) {
                    "root" -> Ssh.exec(session, command)
                    "sudo" -> Ssh.exec(session, "sudo -n -- $command")
                    else -> {
                        if (input.isEmpty() || '\n' in input || '\r' in input) throw Problem("SUDO_REQUIRED")
                        Ssh.exec(session, "sudo -S -p '' -- $command", (input + "\n").toByteArray())
                    }
                }
            }
            synchronize(server, everything = true)
        }
    }

    private fun mutate(full: Boolean = false, setting: Boolean = false, action: (AgentApi) -> Unit) {
        val server = state.value.server ?: return
        operation {
            withAgent(server, action = action)
            if (setting) settingsSaved()
            synchronize(server, everything = full)
        }
    }

    fun clearFile() = mutable.update { it.copy(file = null) }
    fun consumeProblem(problem: String) = mutable.update {
        if (it.userProblem == problem) it.copy(userProblem = "") else it
    }

    private fun inspect(action: (AgentApi) -> String) = mutate { api ->
        mutable.update { it.copy(maintenanceResult = "") }
        val result = action(api)
        mutable.update { it.copy(maintenanceResult = result) }
    }
}
