package dev.horizonmanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal data class Panel(
    val servers: List<Server> = emptyList(),
    val server: Server? = null,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val probe: Map<String, String> = emptyMap(),
    val hostConfirmation: HostConfirmation? = null,
    val problem: String = "",
    val notice: String = "",
    val status: JSONObject = JSONObject(),
    val config: JSONObject = JSONObject(),
    val schema: JSONObject = JSONObject(),
    val archive: List<JSONObject> = emptyList(),
    val hasMoreDigests: Boolean = false,
    val collectionHours: Int = DEFAULT_COLLECTION_HOURS,
    val jobs: List<JSONObject> = emptyList(),
    val sourceHistory: List<JSONObject> = emptyList(),
    val events: List<JSONObject> = emptyList(),
    val flags: List<ArticleFlag> = emptyList(),
    val schedule: JSONObject = JSONObject(),
    val selectedRun: String = "",
    val language: String = "",
    val markdown: String = "",
    val articles: List<JSONObject> = emptyList(),
    val total: Int = 0,
    val file: String? = null,
    val updatingAgent: Boolean = false,
)

internal class ManagerModel(private val app: Application) : AndroidViewModel(app) {
    private val vault = Vault(app)
    private val cache = ManagerDb.get(app).cache()
    private val mutable = MutableStateFlow(Panel())
    val state = mutable.asStateFlow()
    private var candidate: Server? = null
    private var flagObserver: Job? = null
    private var eventObserver: Job? = null
    private var active: Job? = null
    private var activeIsQuiet = false
    private var lastError = ""
    private var lastErrorTime = 0L
    private val selection = app.getSharedPreferences("selected_server", 0)
    private var journalSync = 0L
    private var pendingNotification = ""
    private var installOperation = ""
    private var archiveRun: String? = null
    private val archiveSettings = app.getSharedPreferences("digest_windows", 0)

    init {
        viewModelScope.launch {
            try {
                val servers = withContext(Dispatchers.IO) {
                    vault.load().also(vault::removeLegacyRouterKeys)
                }
                mutable.update { it.copy(servers = servers) }
                DigestWorker.schedule(app, servers)
                val id = pendingNotification.ifEmpty { selection.getString("id", "").orEmpty() }
                pendingNotification = ""
                servers.find { it.id == id }?.let(::select)
            } catch (_: Exception) {
                mutable.update { it.copy(problem = "VAULT_READ") }
            }
        }
    }

    private fun record(error: Throwable, server: Server?) {
        val problem = when (error) {
            is Problem -> error.code + error.detail.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
            is HostConfirmation -> "SSH_HOST_CHANGED"
            else -> "ANDROID_" + error.javaClass.simpleName.uppercase()
        }
        mutable.update { it.copy(problem = problem) }
        val now = android.os.SystemClock.elapsedRealtime()
        if (problem == lastError && now - lastErrorTime < 60_000) return
        lastError = problem
        lastErrorTime = now
        val ts = Instant.now()
        viewModelScope.launch {
            runCatching {
                val data = JSONObject().put("time", ts.toString()).put("component", "android")
                    .put("level", "ERROR").put("code", problem)
                val id = ts.epochSecond * 1_000_000 + ts.nano / 1000
                cache.saveEvents(listOf(LocalEvent(server?.id ?: "setup", "android", id, ts.toString(), data.toString())))
            }
        }
    }

    private fun operation(quiet: Boolean = false, task: suspend () -> Unit) {
        val previous = active?.takeIf { it.isActive }
        if (previous != null && (quiet || !activeIsQuiet)) return
        activeIsQuiet = quiet
        if (!quiet) mutable.update { it.copy(busy = true, problem = "", notice = "") }
        active = viewModelScope.launch {
            try {
                previous?.join()
                withContext(Dispatchers.IO) { task() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: HostConfirmation) {
                if (candidate != null) mutable.update { it.copy(hostConfirmation = error) }
                else record(error, state.value.server)
            } catch (error: Exception) {
                record(error, state.value.server)
            } finally {
                if (!quiet) mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun store(server: Server) {
        val profiles = state.value.servers.toMutableList()
        val index = profiles.indexOfFirst { it.id == server.id }
        if (index < 0) profiles.add(server) else profiles[index] = server
        vault.save(profiles)
        selection.edit().putString("id", server.id).apply()
        mutable.update { it.copy(servers = profiles, server = server, ready = server.installed) }
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

    fun add() {
        if (active?.isActive == true) {
            if (activeIsQuiet) viewModelScope.launch { active?.join(); add() }
            return
        }
        candidate = null
        selection.edit().remove("id").apply()
        mutable.update { Panel(servers = it.servers) }
        observe("setup")
    }

    fun select(server: Server) {
        if (active?.isActive == true) {
            if (activeIsQuiet) viewModelScope.launch { active?.join(); select(server) }
            return
        }
        candidate = null
        selection.edit().putString("id", server.id).apply()
        archiveRun = null
        mutable.update { Panel(servers = it.servers, server = server, ready = server.installed,
            collectionHours = archiveSettings.getInt(server.id, DEFAULT_COLLECTION_HOURS)) }
        observe(server.id)
        operation {
            loadArchive(server)
            val fresh = state.value.server ?: return@operation
            if (fresh.installed) {
                synchronize(fresh, everything = true)
            } else {
                probe(fresh)
            }
        }
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
            showFeed()
        }
    }

    fun connect(name: String, hostname: String, portText: String, user: String, key: Boolean,
                credential: String, passphrase: String, previous: Server?) {
        val host = hostname.trim().removeSurrounding("[", "]")
        val port = portText.toIntOrNull()
        if (host.isEmpty() || !host.matches(Regex("[A-Za-z0-9:._%\\-]+")) ||
            port == null || port !in 1..65535 || !user.matches(Regex("[A-Za-z0-9._-]{1,64}")) ||
            credential.isBlank() || credential.length > 65536) {
            mutable.update { it.copy(problem = "SERVER_FIELDS") }
            return
        }
        val matching = previous?.takeIf {
            it.host.equals(host, true) && it.port == port && it.user == user
        } ?: state.value.servers.find {
            it.host.equals(host, true) && it.port == port && it.user == user
        }
        val server = Server(
            matching?.id ?: UUID.randomUUID().toString(), name.trim().ifBlank { host }, host, port,
            user, key, credential, passphrase, matching?.pin.orEmpty(),
            matching?.token ?: SecureRandom().generateSeed(32).joinToString("") { "%02x".format(it) },
            matching?.installed ?: false, matching?.https.orEmpty(), matching?.background ?: false
        )
        candidate = server
        operation { probe(server, explicit = true) }
    }

    fun confirmHost(approved: Boolean) {
        val received = state.value.hostConfirmation ?: return
        mutable.update { it.copy(hostConfirmation = null) }
        val server = candidate ?: return
        if (approved) operation { probe(server.copy(pin = received.fingerprint), explicit = true) }
        else candidate = null
    }

    private suspend fun probe(server: Server, explicit: Boolean = false) {
        Ssh.withSession(server) { session ->
            val report = Ssh.probe(app, session).toMutableMap()
            if (report["agent"] == "active") {
                runCatching { AgentApi(server.copy(https = ""), session).get("health") as JSONObject }
                    .onSuccess { health ->
                        if (health.optInt("api") == 1) report["authenticated"] = "yes"
                    }.onFailure { report["agent_problem"] = (it as? Problem)?.code ?: "AGENT_UNREACHABLE" }
            }
            val connected = server.copy(installed = report["authenticated"] == "yes")
            val wasInstalled = state.value.server?.installed == true
            store(connected)
            if (connected.installed && !wasInstalled) {
                DigestNotifications.notify(app, connected.id, connected.name, ui(R.string.ui_horizon_manager_installed))
            }
            observe(connected.id)
            mutable.update { it.copy(probe = report, ready = connected.installed) }
            candidate = null
        }
        if (state.value.ready) synchronize(requireNotNull(state.value.server), everything = true)
        if (explicit) mutable.update { it.copy(notice = ui(R.string.ui_ssh_settings_verified)) }
    }

    fun install(password: String) {
        val server = state.value.server ?: return
        val report = state.value.probe
        operation {
            val stage = Ssh.withSession(server) { session ->
                Ssh.install(app, session, server, report["privileges"].orEmpty(),
                    password.ifEmpty { if (!server.keyMode) server.credential else "" })
                Ssh.probe(app, session)
            }
            mutable.update { it.copy(probe = stage, notice = ui(R.string.ui_installation_job_sent_to_the_vps)) }
        }
    }

    fun attachExisting(password: String) {
        val server = state.value.server ?: return
        val privileges = state.value.probe["privileges"]
        operation {
            val token = Ssh.withSession(server) { session ->
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
            probe(server.copy(token = token), explicit = true)
        }
    }

    fun updateAgent(password: String) {
        val server = state.value.server ?: return
        operation {
            val stage = Ssh.withSession(server) { session ->
                val before = Ssh.probe(app, session)
                installOperation = before["operation"].orEmpty()
                Ssh.install(app, session, server, before["privileges"].orEmpty(),
                    password.ifEmpty { if (!server.keyMode) server.credential else "" })
                Ssh.probe(app, session)
            }
            mutable.update { it.copy(probe = stage, updatingAgent = true, notice = ui(R.string.ui_agent_update_started)) }
        }
    }

    private suspend fun checkAgentUpdate(server: Server) {
        val report = Ssh.withSession(server) { Ssh.probe(app, it) }
        val fresh = report["operation"] != installOperation
        if (fresh && report["install"] == "failed") {
            mutable.update { it.copy(probe = report, updatingAgent = false) }
            throw Problem(if (report["code"] == "30") "ACTIVE_JOB" else "AGENT_UPDATE_FAILED",
                report["code"].orEmpty())
        }
        val version = runCatching { withAgent(server) { (it.get("health") as JSONObject).optString("agent") } }.getOrNull()
        if (version == BuildConfig.VERSION_NAME && (fresh && report["install"] == "success" || !fresh && report["install"] != "running")) {
            mutable.update { it.copy(probe = report, updatingAgent = false, schema = JSONObject(),
                notice = ui(R.string.ui_agent_updated)) }
            synchronize(server, everything = true)
            if (server.background) DigestWorker.sync(app, server.id)
        } else {
            mutable.update { it.copy(probe = report) }
        }
    }

    fun refreshInstallation(quiet: Boolean = true) {
        val server = state.value.server ?: return
        operation(quiet) { probe(server) }
    }

    private fun <T> withAgent(server: Server, action: (AgentApi) -> T): T =
        if (server.https.isNotEmpty()) action(AgentApi(server))
        else Ssh.withSession(server) { action(AgentApi(server, it)) }

    private suspend fun loadArchive(server: Server) {
        val cached = cache.archive(server.id) ?: return
        val history = JSONArray(cached).objects()
        if (state.value.server?.id != server.id) return
        mutable.update { it.copy(archive = history, hasMoreDigests = history.size >= DIGEST_PAGE_SIZE) }
        history.firstOrNull()?.let { latest ->
            val lang = digestLanguage(latest.getJSONArray("languages").strings()) ?: return@let
            val run = latest.getString("id")
            showCached(server.id, run, lang)
        }
    }

    private suspend fun showCached(server: String, run: String, language: String): Boolean {
        val data = cache.digest(server, run, language).map { JSONObject(it.data) }
        if (data.isEmpty()) return false
        mutable.update {
            it.copy(selectedRun = run, language = language, markdown = data.first().optString("markdown"),
                articles = data.flatMap { page -> page.getJSONArray("items").objects() }.distinctBy { card -> card.optString("id") },
                total = data.first().optInt("total"))
        }
        return true
    }

    private suspend fun synchronize(server: Server, everything: Boolean) {
        val cursor = cache.eventCursor(server.id)
        val remote = withAgent(server) { api ->
            val head = (api.get("digests?limit=$DIGEST_PAGE_SIZE") as JSONArray).objects()
            val panel = state.value
            val run = archiveRun
            val older = panel.archive.drop(DIGEST_PAGE_SIZE)
            val selected = if (run == null) head.firstOrNull() else head.find { it.optString("id") == run }
                ?: if (older.any { it.optString("id") == run }) {
                    runCatching { api.get("digest-info/$run") as JSONObject }
                        .getOrElse { older.find { it.optString("id") == run } }
                } else null
            val archive = (head + listOfNotNull(selected) + older).distinctBy { it.optString("id") }
            val hasMore = if (older.isNotEmpty()) panel.hasMoreDigests else head.size == DIGEST_PAGE_SIZE
            val lang = selected?.getJSONArray("languages")?.strings()?.let(::digestLanguage)
            val changed = selected?.getString("id") != panel.selectedRun || lang != panel.language
            val digest = if ((everything || changed) && selected != null && lang != null) {
                api.get("digests/${selected.getString("id")}/$lang") as JSONObject
            } else null
            val config = if (everything || panel.config.length() == 0) api.get("config") as JSONObject else panel.config
            val schema = if (panel.schema.length() == 0) api.get("schema") as JSONObject else panel.schema
            val schedule = if (everything || panel.schedule.length() == 0) api.get("schedule") as JSONObject else panel.schedule
            val sources = (api.get("sources") as JSONArray).objects()
            val status = api.get("status") as JSONObject
            val jobs = (api.get("jobs") as JSONArray).objects()
            val events = (api.get(if (cursor == 0L) "events?limit=250&latest=true" else "events?after=$cursor&limit=1000") as JSONArray).objects()
            Refresh(archive, hasMore, digest, config, schema, schedule, sources, status, jobs, events)
        }
        cache.saveArchive(CachedArchive(server.id, JSONArray(remote.archive).toString()))
        val hours = remote.config.optJSONObject("config")?.optJSONObject("collection")
            ?.optInt("time_window_hours", DEFAULT_COLLECTION_HOURS)?.coerceIn(1, 8760) ?: state.value.collectionHours
        if (hours != state.value.collectionHours) archiveSettings.edit().putInt(server.id, hours).apply()
        if (remote.digest != null) {
            val (run, lang) = saveDigest(server.id, remote.digest, 0)
            showCached(server.id, run, lang)
        }
        cache.saveEvents(remote.events.map {
            LocalEvent(server.id, "remote", it.getLong("id"), it.getString("time"), it.toString())
        })
        if (System.currentTimeMillis() - journalSync > 3_600_000) {
            cache.pruneEvents(server.id, Instant.now().minus(45, ChronoUnit.DAYS).toString())
            journalSync = System.currentTimeMillis()
        }
        mutable.update { panel ->
            panel.copy(archive = remote.archive, hasMoreDigests = remote.hasMore, collectionHours = hours,
                config = remote.config, schema = remote.schema,
                schedule = remote.schedule, sourceHistory = remote.sources, status = remote.status, jobs = remote.jobs,
                problem = if (panel.problem.startsWith("AGENT_") || panel.problem.startsWith("SSH_")) "" else panel.problem)
        }
    }


    private data class Refresh(
        val archive: List<JSONObject>, val hasMore: Boolean, val digest: JSONObject?, val config: JSONObject, val schema: JSONObject,
        val schedule: JSONObject, val sources: List<JSONObject>, val status: JSONObject, val jobs: List<JSONObject>,
        val events: List<JSONObject>,
    )

    private suspend fun saveDigest(server: String, page: JSONObject, offset: Int): Pair<String, String> {
        val run = page.getString("id")
        val language = page.getString("language")
        cache.saveDigest(CachedDigest(server, run, language, offset, page.toString()))
        return run to language
    }

    fun refresh(quiet: Boolean = false) {
        val server = state.value.server ?: return
        if (!server.installed) return
        operation(quiet) {
            if (state.value.updatingAgent) {
                checkAgentUpdate(server)
            } else {
                try {
                    synchronize(server, everything = !quiet || state.value.selectedRun.isEmpty())
                } catch (error: Problem) {
                    if (error.code !in setOf("SSH_CONNECT", "SSH_TIMEOUT", "AGENT_UNREACHABLE", "HTTPS_UNREACHABLE")) throw error
                    kotlinx.coroutines.delay(1_000)
                    synchronize(server, everything = !quiet || state.value.selectedRun.isEmpty())
                }
            }
        }
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
        val latest = state.value.archive.firstOrNull()
        if (latest?.optString("id") == state.value.selectedRun && state.value.markdown.isNotEmpty()) return
        operation {
            val run = latest?.optString("id")
            val lang = latest?.optJSONArray("languages")?.strings()?.let(::digestLanguage)
            if (run == null || lang == null || !showCached(server.id, run, lang)) synchronize(server, everything = true)
        }
    }

    fun openArchive(issue: JSONObject) {
        val run = issue.getString("id")
        val lang = issue.getJSONArray("languages").strings().let(::digestLanguage) ?: return
        archiveRun = run
        openDigest(run, lang, offset = 0)
    }

    fun openDigest(run: String, language: String, offset: Int) {
        val server = state.value.server ?: return
        operation {
            val cached = offset == 0 && showCached(server.id, run, language)
            if (offset == 0 && !cached)
                mutable.update { it.copy(selectedRun = run, language = language, articles = emptyList(), markdown = "", total = 0) }
            val page = withAgent(server) { it.get("digests/$run/$language?offset=$offset") as JSONObject }
            saveDigest(server.id, page, offset)
            showCached(server.id, run, language)
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

    fun flag(article: String, save: Boolean? = null, read: Boolean? = null) {
        val server = state.value.server ?: return
        val old = state.value.flags.find { it.article == article }
        viewModelScope.launch {
            cache.saveFlag(ArticleFlag(server.id, article, save ?: old?.saved ?: false, read ?: old?.read ?: false))
        }
    }

    fun submit(kind: String = "run", source: String? = null, bucket: String? = null, index: Int? = null) =
        mutate { api ->
            api.post("jobs", JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind)
                .apply { source?.let { put("source", it) }; bucket?.let { put("bucket", it) }; index?.let { put("index", it) } })
            ui(R.string.ui_job_queued)
        }

    fun translateDigest(run: String) = mutate { api ->
        api.post("jobs", JSONObject().put("id", UUID.randomUUID().toString())
            .put("kind", "localize").put("run", run))
        ui(R.string.ui_digest_translation_queued)
    }

    fun stop(id: String) = mutate { it.post("jobs/$id/stop"); ui(R.string.ui_stop_requested) }

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

    fun saveConfig(config: JSONObject, profiles: JSONArray, secrets: JSONObject = JSONObject()) {
        mutate(true) { api ->
            val response = api.put("config", JSONObject().put("revision", state.value.config.getInt("revision"))
                .put("config", config).put("profiles", profiles).put("secrets", secrets)) as JSONObject
            val missing = response.optJSONArray("missing")?.strings().orEmpty().joinToString()
            if (missing.isNotBlank()) ui(R.string.ui_settings_saved_add_the_following_secrets, missing)
            else ui(R.string.ui_settings_saved)
        }
    }

    fun importConfig(incoming: JSONObject) {
        val original = state.value.config
        val current = original.optJSONObject("config")?.copyObject() ?: return
        for (key in incoming.keys()) current.put(key, incoming.get(key))
        saveConfig(current, original.getJSONArray("profiles"))
    }

    fun copyExisting(path: String) {
        val server = state.value.server ?: return
        operation {
            val incoming = Ssh.withSession(server) { Ssh.existingConfig(it, path) }
            val current = state.value.config
            val config = current.getJSONObject("config").copyObject()
            for (key in incoming.keys()) config.put(key, incoming.get(key))
            withAgent(server) { api ->
                api.put("config", JSONObject().put("revision", current.getInt("revision"))
                    .put("config", config).put("profiles", current.getJSONArray("profiles")))
            }
            synchronize(server, everything = true)
            mutable.update { it.copy(notice = ui(R.string.ui_configuration_copied)) }
        }
    }

    fun revert() = mutate(true) {
        it.post("config/revert", JSONObject().put("revision", state.value.config.getInt("revision")))
        ui(R.string.ui_previous_configuration_restored)
    }

    fun check() = mutate {
        val result = it.post("config/check") as JSONObject
        val missing = result.getJSONArray("missing").strings()
        val enabled = result.getJSONArray("enabled_sources").length()
        ui(R.string.ui_enabled_sources, enabled) +
            (if (!result.optBoolean("model_configured")) ui(R.string.ui_select_an_ai_model) else "") +
            (if (missing.isEmpty()) "" else ui(R.string.ui_add, missing.joinToString()))
    }

    fun checkVersions() = mutate {
        val remote = it.get("upstream") as JSONObject
        val head = remote.getString("upstream_head")
        val installed = remote.getString("installed")
        if (head == installed) ui(R.string.ui_the_installed_revision_matches_the_main)
        else ui(R.string.ui_github_revision_verified_for_this_build, head.take(12), installed.take(12))
    }

    fun schedule(spec: JSONObject) = mutate(true) { it.put("schedule", spec); ui(R.string.ui_schedule_saved_on_the_vps) }

    fun export(password: String) = mutate {
        val result = it.post("backup", JSONObject().put("password", password)) as JSONObject
        mutable.update { panel -> panel.copy(file = result.getString("archive")) }
        ui(R.string.ui_encrypted_backup_ready_to_save)
    }

    fun restore(file: String, password: String) = mutate(true) {
        it.post("restore", JSONObject().put("password", password).put("archive", file)
            .put("revision", state.value.config.getInt("revision")))
        ui(R.string.ui_backup_restored)
    }

    fun clearEvents(before: Long) {
        val server = state.value.server ?: return
        operation {
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
            if (url.isNotBlank()) {
                val health = withAgent(updated) { it.get("health") as JSONObject }
                if (health.optInt("api") != 1) throw Problem("AGENT_PROTOCOL")
            }
            store(updated)
            if (!background && server.background) DigestNotifications.unregister(app, server.id)
            DigestWorker.schedule(app, state.value.servers)
            if (background || server.background) DigestWorker.sync(app, server.id)
            mutable.update { it.copy(notice = ui(R.string.ui_connection_settings_saved)) }
        }
    }

    fun restart(password: String) {
        val server = state.value.server ?: return
        operation {
            Ssh.withSession(server) { session ->
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
            mutable.update { it.copy(notice = ui(R.string.ui_agent_restarted)) }
        }
    }

    private fun mutate(full: Boolean = false, action: (AgentApi) -> String) {
        val server = state.value.server ?: return
        operation {
            val notice = withAgent(server, action)
            mutable.update { it.copy(notice = notice) }
            synchronize(server, everything = full)
        }
    }

    fun clearFile() = mutable.update { it.copy(file = null) }
    fun dismissMessage() = mutable.update { it.copy(problem = "", notice = "") }
}
