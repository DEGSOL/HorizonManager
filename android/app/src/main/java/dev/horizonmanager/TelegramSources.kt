package dev.horizonmanager

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.math.roundToInt

private val telegramHandle = Regex("[A-Za-z][A-Za-z0-9_]{3,31}")

internal fun normalizeTelegramChannel(raw: String): String? {
    val text = raw.trim()
    val name = if (text.contains("://")) {
        val url = runCatching { URI(text) }.getOrNull() ?: return null
        if (!url.scheme.equals("https", true) || !url.host.equals("t.me", true) ||
            url.userInfo != null || url.port != -1 || url.query != null || url.fragment != null) return null
        url.rawPath.removePrefix("/").removeSuffix("/")
    } else text.removePrefix("@")
    return name.takeIf(telegramHandle::matches)
}

internal fun <T> moveSource(items: List<T>, from: Int, to: Int): List<T> {
    if (from == to || from !in items.indices || to !in items.indices) return items
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
internal fun TelegramSources(config: JSONObject, saved: JSONObject, root: JSONObject, channelSchema: JSONObject,
                             profiles: List<String>, busy: Boolean, change: (JSONObject) -> Unit, check: (Int?) -> Unit) {
    val theme = LocalHorizonPalette.current
    val channels = config.optJSONArray("channels")?.objects().orEmpty()
    val savedChannels = saved.optJSONArray("channels")?.objects().orEmpty()
    var editing by remember { mutableIntStateOf(-2) }
    var removing by remember { mutableIntStateOf(-1) }
    fun replace(items: List<JSONObject>) = change(config.copyObject().put("channels", JSONArray(items)))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Telegram", style = MaterialTheme.typography.titleMedium)
            Text(ui(R.string.editorial_channel_count, channels.size) + " · " +
                ui(if (config.optBoolean("enabled")) R.string.ui_enabled else R.string.ui_off), color = theme.muted)
        }
        Switch(config.optBoolean("enabled"), onCheckedChange = { change(config.copyObject().put("enabled", it)) })
    }
    Text(ui(R.string.editorial_public_channels), color = theme.muted, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { editing = -1 }) { Text(ui(R.string.editorial_add_channel)) }
        TextButton(onClick = { check(null) }, enabled = !busy && savedChannels.isNotEmpty()) {
            Text(ui(R.string.editorial_test_channels))
        }
    }
    if (channels.isEmpty()) Text(ui(R.string.editorial_no_channels), color = theme.muted)
    channels.forEachIndexed { index, entry -> key(entry.optString("channel"), index) {
        val savedIndex = savedChannels.indexOfFirst { it.toString() == entry.toString() }
        TelegramRow(entry, index, channels.lastIndex, savedIndex >= 0 && !busy, { editing = index },
            { check(savedIndex) }, { replace(channels.mapIndexed { i, value ->
                if (i == index) value.copyObject().put("enabled", !value.optBoolean("enabled", true)) else value
            }) }, { removing = index }, { to -> replace(moveSource(channels, index, to)) })
    } }
    if (editing >= -1) key(editing) {
        val position = editing
        val entry = channels.getOrNull(position)
        var draft by remember { mutableStateOf(entry?.copyObject() ?: JSONObject().put("enabled", true)) }
        var name by remember { mutableStateOf(entry?.optString("channel").orEmpty()) }
        var touched by remember { mutableStateOf(false) }
        var advanced by remember { mutableStateOf(false) }
        val normalized = normalizeTelegramChannel(name)
        val duplicate = normalized != null && channels.withIndex().any { (i, item) ->
            i != position && (normalizeTelegramChannel(item.optString("channel")) ?: item.optString("channel")).equals(normalized, true)
        }
        val unchanged = entry != null && name == entry.optString("channel")
        val nameError = when {
            duplicate -> ui(R.string.editorial_duplicate_channel)
            normalized == null && !unchanged && (touched || name.isNotEmpty()) -> ui(R.string.editorial_invalid_channel)
            else -> ""
        }
        HorizonAlertDialog(onDismissRequest = { editing = -2 },
            title = { Text(ui(if (entry == null) R.string.editorial_add_channel else R.string.editorial_edit)) },
            text = {
                val properties = definition(root, channelSchema).getJSONObject("properties")
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it; touched = true },
                        label = { Text(ui(R.string.ui_public_channel)) }, singleLine = true, isError = nameError.isNotBlank(),
                        supportingText = if (nameError.isBlank()) null else ({ Text(nameError) }),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { advanced = !advanced }) { Text(ui(R.string.ui_advanced_settings)) }
                    Reveal(advanced) {
                        for (field in listOf("fetch_limit", "category", "profile")) {
                            val schema = properties.getJSONObject(field)
                            EditValue(root, schema, field, draft.opt(field) ?: sample(root, schema),
                                { draft = draft.copyObject().put(field, it) }, profiles)
                        }
                    }
                }
            }, confirmButton = {
                Button(enabled = (normalized != null || unchanged) && !duplicate &&
                    draft.optInt("fetch_limit", 20) > 0, onClick = {
                    val item = draft.copyObject().put("channel", normalized ?: name)
                    replace(if (position < 0) channels + item else channels.mapIndexed { i, old -> if (i == position) item else old })
                    editing = -2
                }) { Text(ui(R.string.editorial_done)) }
            }, dismissButton = { TextButton(onClick = { editing = -2 }) { Text(ui(R.string.ui_cancel)) } })
    }
    if (removing in channels.indices) {
        HorizonAlertDialog(onDismissRequest = { removing = -1 }, title = { Text(ui(R.string.ui_remove)) },
            text = { Text(ui(R.string.editorial_remove_channel, channels[removing].optString("channel"))) },
            confirmButton = { Button(onClick = {
                val index = removing
                removing = -1
                replace(channels.filterIndexed { i, _ -> i != index })
            }) { Text(ui(R.string.ui_remove)) } },
            dismissButton = { TextButton(onClick = { removing = -1 }) { Text(ui(R.string.ui_cancel)) } })
    }
}

@Composable
private fun TelegramRow(entry: JSONObject, index: Int, lastIndex: Int, testEnabled: Boolean, edit: () -> Unit,
                        test: () -> Unit, toggle: () -> Unit, remove: () -> Unit, move: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val theme = LocalHorizonPalette.current
    val enabled = entry.optBoolean("enabled", true)
    var menu by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var destination by remember(index) { mutableIntStateOf(index) }
    val currentMove by rememberUpdatedState(move)
    val currentHeight by rememberUpdatedState(height)
    val currentLastIndex by rememberUpdatedState(lastIndex)
    val display = if (enabled) ui(R.string.ui_enabled) else ui(R.string.ui_off)
    Row(Modifier.fillMaxWidth().onSizeChanged { height = it.height }
        .settingsItemSurface(highlighted = dragging).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).pointerInput(index) {
            var distance = 0f
            detectDragGesturesAfterLongPress(onDragStart = { distance = 0f; destination = index; dragging = true },
                onDragCancel = { dragging = false }, onDragEnd = {
                    dragging = false
                    if (destination != index) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentMove(destination)
                    }
                }, onDrag = { change, delta ->
                    change.consume()
                    distance += delta.y
                    destination = (index + (distance / currentHeight.coerceAtLeast(1)).roundToInt()).coerceIn(0, currentLastIndex)
                })
        }, contentAlignment = Alignment.Center) {
            Text(if (dragging) "#${destination + 1}" else "⠿", color = if (dragging) theme.link else theme.muted, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.editorialDescription(ui(R.string.editorial_drag_channel)))
        }
        Column(Modifier.weight(1f)) {
            Text(entry.optString("channel"), style = MaterialTheme.typography.titleMedium)
            Text(display, color = if (enabled) theme.success else theme.muted, style = MaterialTheme.typography.labelMedium)
        }
        Box {
            GlassIconAction(Icons.Default.MoreVert, ui(R.string.editorial_article_actions)) { menu = true }
            GlassDropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(ui(R.string.editorial_edit)) }, onClick = { menu = false; edit() })
                DropdownMenuItem(text = { Text(ui(R.string.editorial_test_saved)) }, enabled = testEnabled,
                    onClick = { menu = false; test() })
                DropdownMenuItem(text = { Text(ui(if (enabled) R.string.editorial_disable else R.string.editorial_enable)) },
                    onClick = { menu = false; toggle() })
                DropdownMenuItem(text = { Text(ui(R.string.ui_move_up)) }, enabled = index > 0, onClick = { menu = false; move(index - 1) })
                DropdownMenuItem(text = { Text(ui(R.string.editorial_move_down)) }, enabled = index < lastIndex, onClick = { menu = false; move(index + 1) })
                DropdownMenuItem(text = { Text(ui(R.string.ui_remove)) }, onClick = { menu = false; remove() })
            }
        }
    }
}

private fun Modifier.editorialDescription(text: String): Modifier = semantics { contentDescription = text }
