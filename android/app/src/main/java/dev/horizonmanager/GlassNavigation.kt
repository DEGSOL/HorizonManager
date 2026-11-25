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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

internal data class HorizonTab(@param:StringRes val label: Int, val icon: ImageVector)

private object NavigationStyle {
    val inset = 4.dp
    val label = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center)
}

internal enum class ReadingOverlay { None, Digest, Article }

@Composable
internal fun readingBarHeight() = maxOf(60.dp, with(LocalDensity.current) {
    androidx.compose.material3.MaterialTheme.typography.displaySmall.lineHeight.toDp() + 14.dp
})

internal val navigationBottomGap = 10.dp
internal val navigationMaxWidth = 520.dp
internal fun navigationBarWidth(available: Dp): Dp =
    ((available / 2).coerceAtLeast(156.dp) * 1.5f).coerceAtMost(available)
internal val defaultNavigationClearance = 60.dp + navigationBottomGap + 8.dp
internal val LocalNavigationClearance = compositionLocalOf { defaultNavigationClearance }

internal fun Modifier.swipeBetweenTabs(
    enabled: Boolean, selection: Int, count: Int, select: (Int) -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this
    val selectTab by rememberUpdatedState(select)
    val density = LocalDensity.current
    val layout = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    val edge = with(density) { 24.dp.toPx() }
    val left = maxOf(edge, gestures.getLeft(density, layout).toFloat())
    val right = maxOf(edge, gestures.getRight(density, layout).toFloat())
    val swipeDistance = with(density) { 54.dp.toPx() }
    this.pointerInput(selection, count, layout, left, right, swipeDistance) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < left || down.position.x > size.width - right) return@awaitEachGesture
            var distance = 0f
            val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                val delta = change.position - down.position
                if (abs(delta.x) > abs(delta.y) * 1.3f) {
                    distance = overSlop
                    change.consume()
                }
            } ?: return@awaitEachGesture
            val completed = horizontalDrag(slop.id) { change ->
                distance += change.positionChange().x
                change.consume()
            }
            if (completed && abs(distance) >= swipeDistance) {
                val movesForward = (distance < 0f) == (layout == LayoutDirection.Ltr)
                val destination = selection + if (movesForward) 1 else -1
                if (destination in 0 until count) selectTab(destination)
            }
        }
    }
}

internal fun unreadBadgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

@Composable
internal fun GlassNavigation(
    tabs: List<HorizonTab>, selection: Int, unread: Int, width: Dp, overlay: ReadingOverlay,
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
        Modifier.width(width).height(readingBarHeight())
            .onSizeChanged { measured(with(density) { it.height.toDp() }) }
            .then(if (overlay == ReadingOverlay.Digest) Modifier.blur(3.dp).clip(CircleShape).drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = .37f))
            } else Modifier)
            .readingNavigationGlass(CircleShape)
            .padding(NavigationStyle.inset),
    ) {
        val segment = maxWidth / tabs.size
        Box(Modifier.matchParentSize()) {
            Box(Modifier.align(Alignment.CenterStart).width(segment).fillMaxHeight()
                .graphicsLayer {
                    translationX = position.value.coerceIn(0f, tabs.lastIndex.toFloat()) * segment.toPx() * direction
                }
                .padding(horizontal = 2.dp)
                .glassSurface(CircleShape)
                .savedNoticeWash())
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selection
                GlassNavigationButton(tab, selected, if (tab.label == R.string.feed) unread else 0,
                    enabled = overlay == ReadingOverlay.None, modifier = Modifier.weight(1f)) {
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
    val tint by animateColorAsState(if (selected) Color.White else ReadingGlass.muted,
        tween(260), label = "Tab color")
    Column(
        modifier.fillMaxHeight().heightIn(min = readingBarHeight() - NavigationStyle.inset * 2)
            .clip(CircleShape)
            .selectable(selected, interaction, indication = null, enabled = enabled, role = Role.Tab, onClick = open)
            .semantics {
                contentDescription = label
                if (unreadDescription != null) stateDescription = unreadDescription
            }.padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(tab.icon, null, Modifier.fillMaxSize().graphicsLayer { scaleX = iconScale; scaleY = iconScale }, tint = tint)
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 13.dp, y = (-3).dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, Modifier.fillMaxWidth().clearAndSetSemantics {}, color = tint,
            style = NavigationStyle.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                .background(Brush.verticalGradient(listOf(ReadingGlass.muted, ReadingGlass.text)))
                .border(.8.dp, Color.White.copy(alpha = .50f), CircleShape)
                .padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label ?: lastLabel, Modifier.clearAndSetSemantics {}, color = Color.White,
                fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
