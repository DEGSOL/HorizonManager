package dev.horizonmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONArray
import org.json.JSONObject

private fun aiProvider(ai: JSONObject): String = AiRouter.configured(ai)?.id ?: ai.optString("provider")

internal fun rememberedAiModel(settings: JSONObject, current: JSONObject, provider: String): JSONObject? {
    val saved = if (aiProvider(current) == provider) current
        else settings.optJSONObject("ai_providers")?.optJSONObject(provider)
    return saved?.takeIf { (it.opt("model") as? String)?.isNotBlank() == true }
}

@Composable
internal fun AiModelForm(
    panel: Panel, model: ManagerModel, stored: JSONObject, profiles: JSONArray, schema: JSONObject,
    profileIds: List<String>, dirtyChange: (Boolean) -> Unit,
) {
    val serverId = panel.server?.id ?: return
    val revision = panel.config.optInt("revision")
    val saved = stored.getJSONObject("ai")
    var ai by remember(serverId, revision) { mutableStateOf(saved.copyObject()) }
    val drafts = remember(serverId, revision) { mutableMapOf<String, JSONObject>() }
    val router = AiRouter.configured(ai)
    val provider = aiProvider(ai)
    val chatgpt = provider == "openai-chatgpt"
    val providers = panel.schema.getJSONObject("providers")
    val supported = AiRouter.entries.map(AiRouter::id) + providers.keys().asSequence()
        .filter { it != "openai-chatgpt" || panel.status.optBoolean("chatgpt_sign_in") }.toList()
    val secrets = panel.config.optJSONArray("secrets")?.strings().orEmpty()
    val alias = if (router == null) (ai.opt("api_key_env") as? String).orEmpty()
        else router.secretAlias(ai).takeIf { it in secrets } ?: router.secretName
    val hasKey = alias in secrets
    var enteredKey by remember(serverId, revision, provider, alias) { mutableStateOf("") }
    var showKey by remember(serverId, revision, provider, alias) { mutableStateOf(!hasKey) }
    val checks by model.savedModelChecks.collectAsStateWithLifecycle()
    val check = checks[serverId]
    val busy = panel.busy || check?.pending == true
    val dirty = ai.toString() != saved.toString() || enteredKey.isNotBlank()
    SideEffect { dirtyChange(dirty) }

    fun applyModel(selected: JSONObject, key: String? = null) {
        val keys = JSONObject()
        if (key != null && alias.isNotBlank()) {
            selected.put("api_key_env", alias)
            keys.put(alias, key)
        }
        ai = selected
        if (selected.toString() != saved.toString() || keys.length() > 0) {
            model.saveConfig(stored.copyObject().put("ai", selected), profiles, keys)
        }
    }

    fun selectModel(id: String, key: String? = null) {
        if (!busy && id.isNotBlank()) {
            val selected = ai.copyObject().put("model", id)
            if (router != null) selected.put("api_key_env", alias)
            applyModel(selected, key?.trim()?.takeIf(String::isNotBlank))
        }
    }

    fun switchProvider(target: String) {
        if (busy || target == provider) return
        drafts[provider] = ai
        val remembered = rememberedAiModel(panel.config, saved, target)
        val next = (remembered ?: drafts[target] ?: providerDefaults(ai, target, providers))
            .copyObject().put("languages", ai.opt("languages"))
        if (remembered == null) ai = next else applyModel(next)
    }

    val colors = MaterialTheme.colorScheme.copy(
        onSurface = ReadingGlass.text, onSurfaceVariant = ReadingGlass.muted, error = Color(0xFF791B26),
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalLightGlass provides true, LocalContentColor provides ReadingGlass.text) {
            AiGlassSection {
                Choice(ui(R.string.ui_provider), supported, provider, ::switchProvider, ::aiProviderLabel,
                    enabled = !busy, row = true)
                AiHint(ui(R.string.hovq_ai_autosave))
            }
            AiGlassSection(R.string.hovq_ai_account_model) {
                key(serverId, provider, revision) {
                    val currentModel = ai.optString("model")
                    when {
                        chatgpt -> ChatGptSettings(serverId, model, currentModel, busy) { selectModel(it) }
                        router != null -> RouterSettings(router, currentModel, hasKey, busy,
                            { key -> model.loadRouterCatalog(serverId, router, key) }) { id, key -> selectModel(id, key) }
                        else -> {
                            if (provider != "ollama") {
                                if (hasKey) {
                                    GlassSettingRow(ui(R.string.ui_replace_api_key), ui(R.string.ui_key_saved_on_the_vps),
                                        enabled = !busy) { showKey = !showKey }
                                }
                                Reveal(showKey) {
                                    PasswordField(ui(R.string.ui_api_key, aiProviderLabel(provider)), enteredKey,
                                        enabled = !busy, light = true) { enteredKey = it }
                                }
                            }
                            ManualModelPicker(currentModel, busy) { selectModel(it, enteredKey) }
                        }
                    }
                }
            }
            AiGlassSection(R.string.hovq_ai_connection_title) {
                SavedModelTest(ai, saved, revision, panel.busy, check, enteredKey.isBlank()) {
                    model.testSavedModel(serverId, revision, ai)
                }
            }
            if (!chatgpt) {
                AiGlassSection {
                    var advancedOpen by remember(serverId, provider) { mutableStateOf(false) }
                    GlassSettingRow(ui(R.string.ui_advanced_ai_options), ui(R.string.hovq_ai_advanced_hint)) {
                        advancedOpen = !advancedOpen
                    }
                    Reveal(advancedOpen) {
                        if (router == null) {
                            ValueField(ui(R.string.ui_compatible_api_address_optional), (ai.opt("base_url") as? String).orEmpty(), {
                                ai = ai.copyObject().put("base_url", it.ifBlank { null } ?: JSONObject.NULL)
                            }, enabled = !busy)
                        }
                        val raw = schema.getJSONObject("properties").getJSONObject("ai")
                        val advanced = definition(schema, raw).copyObject()
                        val fields = advanced.getJSONObject("properties")
                        listOf("provider", "model", "base_url", "languages").forEach { fields.remove(it) }
                        if (router != null) {
                            listOf("api_key_env", "azure_endpoint_env", "api_version", "provider_chain").forEach { fields.remove(it) }
                        }
                        EditValue(schema, advanced, "ai", ai, { if (!busy) ai = it as JSONObject }, profileIds,
                            showHeader = false)
                    }
                }
            }
        }
    }
}

private fun providerDefaults(current: JSONObject, provider: String, providers: JSONObject): JSONObject {
    val router = AiRouter.entries.find { it.id == provider }
    if (router != null) return router.defaults(current)
    val defaults = providers.getJSONObject(provider)
    return current.copyObject().put("provider", provider).put("model", "")
        .put("api_key_env", defaults.optString("api_key_env"))
        .put("base_url", defaults.opt("base_url") ?: JSONObject.NULL)
        .put("azure_endpoint_env", defaults.opt("azure_endpoint_env") ?: JSONObject.NULL)
        .put("api_version", defaults.opt("api_version") ?: JSONObject.NULL)
        .put("provider_chain", JSONObject.NULL).put("extra_body", JSONObject.NULL)
}

private fun aiProviderLabel(provider: String): String = AiRouter.entries.find { it.id == provider }?.title ?: when (provider) {
    "openai" -> ui(R.string.ui_openai_compatible_api)
    "openai-chatgpt" -> ui(R.string.hovq_chatgpt_provider)
    else -> provider
}

@Composable
private fun AiGlassSection(title: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().readingNavigationGlass(feedGlassShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Text(ui(title), Modifier.padding(horizontal = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = ReadingGlass.text)
        content()
    }
}

@Composable
internal fun AiHint(text: String, color: Color = ReadingGlass.muted) {
    Text(text, Modifier.padding(horizontal = 4.dp), color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun AiModelRow(model: String, enabled: Boolean, choose: () -> Unit) {
    GlassSettingRow(ui(if (model.isBlank()) R.string.ui_select_model else R.string.ui_change_model), model,
        enabled = enabled, open = choose)
}
