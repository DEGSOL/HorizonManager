package dev.horizonmanager

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant

internal enum class NewsOrder(@param:StringRes val label: Int) {
    Score(R.string.ui_by_score), Oldest(R.string.oldest_first), Title(R.string.ui_by_title),
}

@Stable
internal class NewsFilters {
    var query by mutableStateOf("")
    var saved by mutableStateOf(false)
    var unread by mutableStateOf(false)
    var profile by mutableStateOf("")
    var order by mutableStateOf(NewsOrder.Score)
}

internal fun articleProfiles(articles: List<JSONObject>): List<String> =
    articles.mapNotNull { it.optString("profile").takeIf { profile -> profile.isNotBlank() && profile != "null" } }
        .distinct().sorted()

internal fun chronologicalTime(article: JSONObject): Instant =
    runCatching { Instant.parse(article.optString("time")) }.getOrDefault(Instant.MAX)

@Composable
internal fun GlassIconAction(icon: ImageVector, description: String, enabled: Boolean = true,
                            active: Boolean? = null, onClick: () -> Unit) {
    val palette = LocalHorizonPalette.current
    val indicator by animateColorAsState(if (active == true) palette.tint.copy(alpha = 0.27f) else Color.Transparent,
        tween(180), label = "icon highlight")
    val tint by animateColorAsState(when {
        !enabled -> palette.muted.copy(alpha = 0.53f)
        active == true -> palette.link
        else -> palette.text
    }, tween(180), label = "icon tint")
    val glyph: @Composable () -> Unit = {
        Icon(icon, description,
            Modifier.size(36.dp).background(indicator, CircleShape).padding(8.dp),
            tint = tint)
    }
    if (active != null) IconToggleButton(active, { onClick() }, Modifier.size(48.dp), enabled, content = glyph)
    else IconButton(onClick, Modifier.size(48.dp), enabled, content = glyph)
}

@Composable
internal fun NewsToolbar(filters: NewsFilters, profiles: List<String>, enabled: Boolean) {
    var showSearch by remember { mutableStateOf(false) }
    var showProfiles by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    Row(Modifier.glassPanel(CircleShape), verticalAlignment = Alignment.CenterVertically) {
        GlassIconAction(Icons.Default.Search, ui(R.string.ui_search_downloaded_articles), enabled,
            active = filters.query.isNotBlank()) { showSearch = true }
        GlassIconAction(if (filters.saved) Icons.Default.Star else Icons.Outlined.Star,
            ui(R.string.ui_saved), enabled, active = filters.saved) { filters.saved = !filters.saved }
        GlassIconAction(if (filters.unread) Icons.Default.Email else Icons.Outlined.MailOutline,
            ui(R.string.ui_unread), enabled, active = filters.unread) { filters.unread = !filters.unread }
        Box {
            GlassIconAction(Icons.Default.Person, ui(R.string.ui_profile), enabled, active = filters.profile.isNotEmpty()) {
                showProfiles = true
            }
            DropdownMenu(showProfiles, { showProfiles = false }) {
                for (profile in listOf("") + profiles) {
                    DropdownMenuItem(text = { Text(profile.ifBlank { ui(R.string.ui_all) }) }, onClick = {
                        filters.profile = profile
                        showProfiles = false
                    }, trailingIcon = if (profile == filters.profile) ({ Icon(Icons.Default.Check, null) }) else null)
                }
            }
        }
        Box {
            GlassIconAction(ImageVector.vectorResource(R.drawable.ic_sort), ui(R.string.ui_sort), enabled,
                active = filters.order != NewsOrder.Score) { showSort = true }
            DropdownMenu(showSort, { showSort = false }) {
                NewsOrder.entries.forEach { order ->
                    DropdownMenuItem(text = { Text(ui(order.label)) }, onClick = {
                        filters.order = order
                        showSort = false
                    }, trailingIcon = if (order == filters.order) ({ Icon(Icons.Default.Check, null) }) else null)
                }
            }
        }
    }
    if (showSearch) {
        val focus = remember { FocusRequester() }
        HorizonAlertDialog(
            onDismissRequest = { showSearch = false },
            title = { Text(ui(R.string.news_search)) },
            text = {
                OutlinedTextField(
                    filters.query, { filters.query = it }, Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(ui(R.string.ui_search_downloaded_articles)) },
                    trailingIcon = if (filters.query.isNotEmpty()) ({
                        GlassIconAction(Icons.Default.Close, ui(R.string.clear_search)) { filters.query = "" }
                    }) else null,
                    singleLine = true, colors = horizonFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { showSearch = false }),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            },
            confirmButton = { TextButton(onClick = { showSearch = false }) { Text(ui(R.string.ui_close)) } },
        )
    }
}
