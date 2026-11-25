package dev.horizonmanager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun ChatGptSettings(
    serverId: String, model: ManagerModel, currentModel: String, busy: Boolean,
    select: (String) -> Unit,
) {
    val context = LocalContext.current
    val status by model.chatgptLogin.collectAsStateWithLifecycle()
    val login = status?.takeIf { it.server == serverId }
    var connected by remember(serverId) { mutableStateOf(false) }
    var email by remember(serverId) { mutableStateOf("") }
    var loading by remember(serverId) { mutableStateOf(true) }
    var error by remember(serverId) { mutableStateOf("") }
    var models by remember(serverId) { mutableStateOf(emptyList<RouterModel>()) }
    var refresh by remember(serverId) { mutableIntStateOf(0) }
    var choosing by remember(serverId) { mutableStateOf(false) }

    LaunchedEffect(serverId, login, refresh) {
        if (login?.waiting == true) return@LaunchedEffect
        loading = true
        error = ""
        try {
            val account = model.chatgptAccount(serverId)
            connected = account.optBoolean("plan_usage")
            email = (account.opt("email") as? String).orEmpty()
            models = if (connected) model.chatgptModels(serverId) else emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            models = emptyList()
            error = (problem as? Problem)?.code ?: "CHATGPT_STATUS_UNAVAILABLE"
        } finally {
            loading = false
        }
    }

    if (!connected) AiHint(ui(R.string.hovq_chatgpt_plan_help))
    if (login?.waiting == true) {
        DottedProgress(Modifier.fillMaxWidth())
        Text(ui(R.string.hovq_chatgpt_waiting),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = { model.cancelChatGptLogin(serverId) }) { Text(ui(R.string.ui_cancel)) }
    } else {
        val account = if (!connected) "" else if (email.isBlank()) ui(R.string.hovq_chatgpt_connected)
            else ui(R.string.hovq_chatgpt_account, email)
        GlassSettingRow(ui(if (connected) R.string.hovq_chatgpt_reconnect else R.string.hovq_chatgpt_sign_in),
            account, enabled = !busy && !loading) {
            model.signInChatGpt(serverId) { url ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    throw Problem("CHATGPT_BROWSER_UNAVAILABLE")
                }
            }
        }
    }
    if (loading && !choosing && login?.waiting != true) DottedProgress(Modifier.fillMaxWidth())
    val failure = login?.error?.takeIf(String::isNotBlank) ?: error
    if (!choosing && failure.isNotBlank()) AiHint(errorText(failure), color = MaterialTheme.colorScheme.error)
    if (connected && login?.waiting != true) {
        val selected = models.firstOrNull { it.id == currentModel }
        AiModelRow(selected?.name ?: currentModel, enabled = !busy && !loading) { choosing = true }
        if (choosing) RouterPicker(models, currentModel, loading, failure.takeIf(String::isNotBlank)?.let(::errorText).orEmpty(),
            refresh = { refresh++ }, close = { choosing = false }) {
            choosing = false
            select(it)
        }
    }
}

@Composable
internal fun SavedModelTest(
    selected: JSONObject, saved: JSONObject, revision: Int, busy: Boolean, check: SavedModelCheck?,
    ready: Boolean, test: () -> Unit,
) {
    val selectedModel = selected.optString("model")
    val applied = ready && selected.toString() == saved.toString() && selectedModel.isNotBlank()
    val result = check?.takeIf { applied && it.model == selectedModel && it.revision == revision }
    TextButton(onClick = test, enabled = !busy && applied && check?.pending != true) {
        Text(ui(R.string.ui_test_saved_model))
    }
    if (busy && !applied && selectedModel.isNotBlank()) {
        AiHint(ui(R.string.ui_saving))
    }
    if (result != null) {
        if (result.pending) DottedProgress(Modifier.fillMaxWidth())
        val message = when {
            result.pending -> ui(R.string.hovq_ai_testing, result.model)
            result.error.isNotEmpty() -> {
                val detail = if (result.error == "UPSTREAM_FAILURE") ui(R.string.hovq_ai_test_no_reply)
                else errorText(result.error)
                ui(R.string.hovq_ai_test_failed, result.model, detail)
            }
            else -> ui(R.string.hovq_ai_test_success, result.model)
        }
        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
            color = if (result.error.isNotEmpty()) MaterialTheme.colorScheme.error else ReadingGlass.text)
    } else if (!applied && !busy) {
        AiHint(ui(R.string.hovq_ai_test_save_first))
    } else if (applied && !busy) {
        AiHint(ui(R.string.hovq_ai_test_hint))
    }
}
