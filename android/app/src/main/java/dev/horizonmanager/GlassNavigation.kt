package dev.horizonmanager

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

internal data class HorizonTab(@param:StringRes val label: Int, val icon: ImageVector)

private object NavigationStyle {
    val height = 74.dp
    val inset = 6.dp
    val maxWidth = 520.dp
    val inactive = Color(0xFFBDBDBD)
    val silver = Color(0xFFF5F5F5)
    val selected = Color(0xFF414141)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center)
}

internal val navigationBottomGap = 10.dp
internal val defaultNavigationClearance = NavigationStyle.height + navigationBottomGap + 8.dp
internal val LocalNavigationClearance = compositionLocalOf { defaultNavigationClearance }

internal fun unreadBadgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

@Composable
internal fun GlassNavigation(
    tabs: List<HorizonTab>, selection: Int, unread: Int, dimmed: Boolean,
    measured: (Dp) -> Unit, select: (Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val position = remember { Animatable(selection.toFloat()) }
    LaunchedEffect(selection) {
        position.animateTo(selection.toFloat(), spring(dampingRatio = .97f, stiffness = 280f, visibilityThreshold = .002f))
    }
    BoxWithConstraints(
        Modifier.widthIn(max = NavigationStyle.maxWidth).fillMaxWidth().heightIn(min = NavigationStyle.height)
            .onSizeChanged { measured(with(density) { it.height.toDp() }) }
            .then(if (dimmed) Modifier.blur(3.dp).clip(CircleShape).drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = .37f))
            } else Modifier)
            .navigationGlass(CircleShape)
            .padding(NavigationStyle.inset),
    ) {
        val segment = maxWidth / tabs.size
        Box(Modifier.matchParentSize()) {
            Box(Modifier.align(Alignment.CenterStart).width(segment).fillMaxHeight()
                .graphicsLayer {
                    translationX = position.value.coerceIn(0f, tabs.lastIndex.toFloat()) * segment.toPx() * direction
                    scaleX = 1f + .04f * (abs(position.velocity) / 7f).coerceIn(0f, 1f)
                }
                .padding(horizontal = 2.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(
                    NavigationStyle.selected.copy(alpha = .94f),
                    NavigationStyle.selected.copy(alpha = .58f),
                )))
                .border(1.dp, Brush.verticalGradient(listOf(
                    NavigationStyle.silver.copy(alpha = .25f),
                    Color.White.copy(alpha = .10f),
                )), CircleShape))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selection
                GlassNavigationButton(tab, selected, if (tab.label == R.string.feed) unread else 0,
                    enabled = !dimmed, modifier = Modifier.weight(1f)) {
                    if (!selected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    select(index)
                }
            }
        }
    }
}

@Composable
private fun GlassNavigationButton(
    tab: HorizonTab, selected: Boolean, unread: Int, enabled: Boolean,
    modifier: Modifier, open: () -> Unit,
) {
    val label = ui(tab.label)
    val unreadDescription = if (unread > 0) ui(R.string.nav_unread, unread) else null
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val iconScale by animateFloatAsState(if (pressed) .95f else 1f, tween(130), label = "Tab press")
    val tint by animateColorAsState(if (selected) NavigationStyle.silver else NavigationStyle.inactive,
        tween(260), label = "Tab color")
    Column(
        modifier.fillMaxHeight().heightIn(min = NavigationStyle.height - NavigationStyle.inset * 2)
            .clip(CircleShape)
            .selectable(selected, interaction, indication = null, enabled = enabled, role = Role.Tab, onClick = open)
            .semantics {
                contentDescription = label
                if (unreadDescription != null) stateDescription = unreadDescription
            }.padding(horizontal = 3.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 30.dp, height = 26.dp), contentAlignment = Alignment.Center) {
            Icon(tab.icon, null, Modifier.size(25.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }, tint = tint)
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-5).dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, Modifier.fillMaxWidth().clearAndSetSemantics {}, color = tint, style = NavigationStyle.label)
    }
}

@Composable
private fun UnreadBadge(count: Int, modifier: Modifier) {
    val label = unreadBadgeText(count)
    val pulse = remember { Animatable(1f) }
    var previous by remember { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (count > 0 && count != previous) {
            pulse.snapTo(.91f)
            pulse.animateTo(1f, tween(180))
        }
        previous = count
    }
    AnimatedVisibility(label != null, modifier, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
        // Keep the last label during the exit fade. Accessibility reports the count on the tab.
        var lastLabel by remember { mutableStateOf(label.orEmpty()) }
        SideEffect { if (label != null) lastLabel = label }
        Box(
            Modifier.graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }
                .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF4A4A4A), Color(0xFF2B2B2B))))
                .border(.8.dp, Color.White.copy(alpha = .50f), CircleShape)
                .padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label ?: lastLabel, Modifier.clearAndSetSemantics {}, color = NavigationStyle.silver,
                fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
