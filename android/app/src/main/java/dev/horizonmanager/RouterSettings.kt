package dev.horizonmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun RouterSettings(
    router: AiRouter,
    currentModel: String,
    hasSavedKey: Boolean,
    busy: Boolean,
    load: suspend (String?) -> List<RouterModel>?,
    select: (String, String?) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var checkedKey by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<RouterModel>?>(null) }
    var error by remember { mutableStateOf("") }
    var chooseModel by remember { mutableStateOf(false) }
    var showKeyField by remember { mutableStateOf(!hasSavedKey) }

    LaunchedEffect(request) {
        val newKey = input.trim().ifEmpty { null }
        if (!hasSavedKey && newKey == null) return@LaunchedEffect
        loading = true
        error = ""
        models = null
        checkedKey = null
        try {
            val catalog = load(newKey)
            if (catalog == null) {
                showKeyField = true
                error = ui(R.string.ui_enter_a_key_to_load_the)
            } else {
                models = catalog
                checkedKey = newKey
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Problem) {
            if (problem.code == "ROUTER_KEY_REJECTED") showKeyField = true
            error = when (problem.code) {
                "ROUTER_KEY_FORMAT" -> ui(R.string.ui_check_the_api_key_for_spaces)
                "ROUTER_KEY_REJECTED" -> ui(R.string.ui_rejected_this_api_key_check_it, router.title)
                "ROUTER_RATE_LIMIT" -> ui(R.string.ui_limited_the_request_rate_try_again, router.title)
                "ROUTER_CATALOG_NETWORK" -> ui(R.string.router_vps_network, router.title)
                "AGENT_UPDATE_REQUIRED" -> ui(R.string.router_upgrade_required)
                "AUTH_REQUIRED", "SSH_CONNECT", "SSH_TIMEOUT", "SSH_DNS", "SSH_REFUSED", "AGENT_UNREACHABLE", "HTTPS_UNREACHABLE" ->
                    errorText(problem.code)
                "TLS_INVALID" -> ui(R.string.ui_could_not_verify_the_certificate, router.title)
                "ROUTER_CATALOG_HTTP" -> ui(R.string.ui_returned_http_error, router.title, problem.detail)
                else -> ui(R.string.ui_returned_the_catalog_in_an_unexpected, router.title)
            }
        } catch (_: Exception) {
            error = ui(R.string.router_vps_network, router.title)
        } finally {
            loading = false
        }
    }

    if (hasSavedKey) {
        GlassSettingRow(ui(R.string.ui_replace_api_key), ui(R.string.ui_key_saved_on_the_vps),
            enabled = !busy && !loading) { showKeyField = !showKeyField }
    }
    if (showKeyField) {
        AiHint(ui(R.string.hovq_ai_catalog_key_hint))
        SettingsTextField(
            ui(R.string.ui_api_key, router.title), input, {
                input = it
                error = ""
                models = null
                checkedKey = null
            }, enabled = !loading && !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (input.isNotBlank() && !busy && !loading) {
                    chooseModel = true
                    request++
                }
            }),
        )
    }
    val selection = models.orEmpty().firstOrNull { it.id == currentModel }
    AiModelRow(selection?.name ?: currentModel, enabled = !busy && (hasSavedKey || input.isNotBlank())) {
        chooseModel = true
        if (models == null && !loading) request++
    }
    if (chooseModel) RouterPicker(models.orEmpty(), currentModel, loading, error,
        refresh = { request++ }, close = { chooseModel = false }) { id ->
        chooseModel = false
        select(id, checkedKey)
    }
}

@Composable
internal fun ManualModelPicker(currentModel: String, busy: Boolean, select: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    AiModelRow(currentModel, enabled = !busy) { choosing = true }
    if (choosing) {
        var id by remember(currentModel) { mutableStateOf(currentModel) }
        HorizonAlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(ui(if (currentModel.isBlank()) R.string.ui_choose_a_model else R.string.ui_change_model)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AiHint(ui(R.string.hovq_ai_manual_model_hint))
                    ValueField(ui(R.string.ui_model_id), id, { id = it }, enabled = !busy)
                }
            },
            confirmButton = {
                Button(enabled = !busy && id.trim().isNotBlank(), onClick = {
                    choosing = false
                    select(id.trim())
                }) { Text(ui(R.string.ui_select_model)) }
            },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(ui(R.string.ui_cancel)) } },
            light = true,
        )
    }
}

@Composable
internal fun RouterPicker(
    models: List<RouterModel>, selectedId: String, loading: Boolean, error: String,
    refresh: () -> Unit, close: () -> Unit, select: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query) {
        val text = query.trim()
        models.filter { it.id.contains(text, ignoreCase = true) || it.name.contains(text, ignoreCase = true) }
    }
    GlassPickerDialog(ui(if (selectedId.isBlank()) R.string.ui_choose_a_model else R.string.ui_change_model),
        close, secondary = { TextButton(onClick = refresh, enabled = !loading) { Text(ui(R.string.ui_refresh_list)) } }) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SettingsTextField(ui(R.string.ui_search_by_name), query, { query = it })
                if (loading) DottedProgress(Modifier.fillMaxWidth())
                if (error.isNotBlank()) AiHint(error, color = MaterialTheme.colorScheme.error)
                if (!loading && error.isBlank() && filtered.isEmpty()) AiHint(ui(R.string.ui_no_models_found))
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filtered, key = RouterModel::id) { model ->
                        GlassPickerItem(
                            text = { Text(model.name) },
                            supportingContent = {
                                val context = model.contextLength?.let { ui(R.string.ui_token_context, it) }.orEmpty()
                                Text(model.id + context)
                            },
                            selected = model.id == selectedId, enabled = !loading, onClick = { select(model.id) },
                        )
                    }
                }
            }
    }
}
