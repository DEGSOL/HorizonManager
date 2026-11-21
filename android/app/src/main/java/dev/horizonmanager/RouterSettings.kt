package dev.horizonmanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    selected: (String) -> Unit,
    save: (String?) -> Unit,
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

    Text(ui(R.string.ui_select_a_model_from_the_catalog))
    if (hasSavedKey && !showKeyField) {
        Text(ui(R.string.ui_key_saved_on_the_vps), style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !busy && !loading, onClick = { showKeyField = true }) { Text(ui(R.string.ui_replace_api_key)) }
    }
    if (showKeyField) {
        OutlinedTextField(
            value = input, onValueChange = {
                input = it
                error = ""
                models = null
                checkedKey = null
            }, label = { Text(ui(R.string.ui_api_key, router.title)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, colors = horizonFieldColors(), enabled = !loading && !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) request++ }),
        )
    }
    TextButton(enabled = !busy && !loading && (hasSavedKey || input.isNotBlank()), onClick = { request++ }) {
        Text(when {
            models != null -> ui(R.string.ui_refresh_list)
            input.isNotBlank() -> ui(R.string.ui_connect_and_load_models)
            else -> ui(R.string.ui_load_models)
        })
    }
    if (loading) {
        DottedProgress(Modifier.fillMaxWidth())
        Text(ui(R.string.ui_loading_the_catalog, router.title))
    }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    val available = models.orEmpty()
    val selection = available.find { it.id == currentModel }
    if (models != null) {
        Text(if (available.isEmpty()) ui(R.string.ui_the_catalog_returned_no_text_models) else ui(R.string.ui_available_models, available.size))
    }
    if (currentModel.isNotBlank()) {
        Text(selection?.name ?: currentModel, style = MaterialTheme.typography.titleMedium)
        if (models != null && selection == null) Text(ui(R.string.ui_the_current_model_is_unavailable_for))
    }
    OutlinedButton(onClick = { chooseModel = true }, enabled = available.isNotEmpty() && !busy && !loading) {
        Text(if (currentModel.isBlank()) ui(R.string.ui_select_model) else ui(R.string.ui_change_model))
    }
    if (chooseModel) RouterPicker(available, currentModel, { chooseModel = false }) {
        selected(it)
        chooseModel = false
    }
    Button(enabled = !busy && !loading && selection != null, onClick = { save(checkedKey) }) {
        Text(ui(R.string.ui_save_settings))
    }
}

@Composable
private fun RouterPicker(models: List<RouterModel>, selectedId: String, close: () -> Unit, select: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query) {
        val text = query.trim()
        models.filter { it.id.contains(text, ignoreCase = true) || it.name.contains(text, ignoreCase = true) }
    }
    HorizonAlertDialog(
        onDismissRequest = close,
        title = { Text(ui(R.string.ui_choose_a_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, label = { Text(ui(R.string.ui_search_by_name)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, colors = horizonFieldColors(),
                )
                if (filtered.isEmpty()) Text(ui(R.string.ui_no_models_found))
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(filtered, key = RouterModel::id) { model ->
                        ListItem(
                            headlineContent = { Text(model.name) },
                            supportingContent = {
                                val context = model.contextLength?.let { ui(R.string.ui_token_context, it) }.orEmpty()
                                Text(model.id + context)
                            },
                            trailingContent = if (model.id == selectedId) ({ Text(ui(R.string.ui_selected)) }) else null,
                            modifier = Modifier.clickable { select(model.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(ui(R.string.ui_close)) } },
    )
}
