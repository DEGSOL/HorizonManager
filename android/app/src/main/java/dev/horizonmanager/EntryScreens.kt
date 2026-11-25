package dev.horizonmanager

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun SavedGlassButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.widthIn(min = 152.dp)
            .navigationGlass(noticeGlassShape)
            .then(if (enabled) Modifier.savedNoticeWash() else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else .65f),
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EntryScene(content: @Composable BoxScope.() -> Unit) {
    val palette = LocalHorizonPalette.current
    val layer = rememberGraphicsLayer()
    val scene = remember(layer) { GlassScene(layer) }
    Box(Modifier.fillMaxSize().background(palette.background)) {
        Canvas(Modifier.fillMaxSize().recordScene(scene)) {
            val glow = Offset(size.width * .48f, size.height * .42f)
            drawRect(Brush.radialGradient(
                listOf(palette.glassRaised, palette.background),
                center = glow, radius = size.maxDimension * .71f,
            ))
        }
        CompositionLocalProvider(LocalGlassScene provides scene) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center, content = content)
        }
    }
}

@Composable
private fun EntryWindow(title: String, scrollable: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 430.dp).fillMaxWidth().readingNavigationGlass(RoundedCornerShape(30.dp))
        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, Modifier.fillMaxWidth().semantics { heading() }, color = ReadingGlass.text,
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        content()
    }
}

@Composable
internal fun WelcomeWindow(begin: () -> Unit) {
    BackHandler { }
    EntryScene {
        EntryWindow(ui(R.string.hovq_welcome_title)) {
            Text(ui(R.string.hovq_welcome_steps), color = ReadingGlass.muted, style = MaterialTheme.typography.bodyLarge)
            SavedGlassButton(ui(R.string.hovq_welcome_start), Modifier.align(Alignment.CenterHorizontally), onClick = begin)
        }
    }
}

@Composable
internal fun AgentGate(panel: Panel, model: ManagerModel) {
    val server = panel.server ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val known = isAgentVersion(panel.agentVersion)
    LaunchedEffect(server.id, panel.updatingAgent, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(if (panel.updatingAgent) 4_000 else 12_000)
                model.refresh(quiet = true)
            }
        }
    }
    BackHandler { }
    if (panel.updatingAgent) {
        AgentUpdateAnimation()
        return
    }
    val problem = panel.problem.ifBlank { panel.connectionProblem }
    val sudoNeeded = server.user != "root" && panel.probe["privileges"] !in setOf("root", "sudo") &&
        (server.keyMode || problem.startsWith("SUDO_REQUIRED") || problem.startsWith("SSH_COMMAND"))
    var password by remember(server.id) { mutableStateOf("") }
    EntryScene {
        EntryWindow(ui(if (known) R.string.ui_update_vps_agent else R.string.hovq_agent_check_title)) {
            Text(
                if (known) ui(R.string.hovq_agent_update_offer, panel.agentVersion, BuildConfig.AGENT_VERSION)
                else ui(R.string.hovq_agent_check_detail),
                color = ReadingGlass.muted, style = MaterialTheme.typography.bodyMedium,
            )
            if (problem.isNotBlank() && !panel.busy) {
                Text(errorText(problem), color = Color(0xFF791B26), style = MaterialTheme.typography.bodyMedium)
            }
            if (known && sudoNeeded) {
                PasswordField(ui(R.string.hovq_update_sudo), password, enabled = !panel.busy, light = true) { password = it }
            }
            if (!known && (panel.busy || panel.syncing)) DottedProgress(Modifier.fillMaxWidth())
            else SavedGlassButton(ui(if (known) R.string.hovq_update_now else R.string.ui_check_again),
                Modifier.align(Alignment.CenterHorizontally), enabled = !panel.busy) {
                if (known) {
                    model.updateAgent(password)
                    password = ""
                } else model.refresh()
            }
        }
    }
}

@Composable
private fun AgentUpdateAnimation() {
    val motion = rememberInfiniteTransition(label = "Agent installation")
    val phase = motion.animateFloat(0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing)),
        label = "Glass orbit")
    EntryScene {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Canvas(Modifier.widthIn(max = 250.dp).fillMaxWidth(.76f).aspectRatio(1f)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
                val radius = size.minDimension * .32f
                val angle = phase.value * 360f
                val silver = Color(0xFFD9E2E9)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFBACDD9).copy(alpha = .26f), Color.Transparent),
                    center = center, radius = radius * 1.55f), radius * 1.55f)
                for (orbit in 0..2) {
                    val offset = orbit * radius * .21f
                    val extent = radius + offset
                    val ring = Size(extent * 2f, extent * 2f)
                    val origin = center - Offset(extent, extent)
                    drawCircle(silver.copy(alpha = .16f), extent, style = Stroke(1.dp.toPx()))
                    drawArc(silver.copy(alpha = .38f - orbit * .08f),
                        startAngle = angle * if (orbit == 1) -1f else 1f, sweepAngle = 102f,
                        useCenter = false, topLeft = origin, size = ring, style = Stroke(4.dp.toPx()))
                }
                val radians = angle * kotlin.math.PI.toFloat() / 180f
                val reflection = center + Offset(cos(radians) * radius, sin(radians) * radius)
                drawCircle(Brush.radialGradient(listOf(silver.copy(alpha = .65f), Color.Transparent),
                    center = reflection, radius = 21.dp.toPx()), 21.dp.toPx(), reflection)
                drawCircle(Color.White.copy(alpha = .9f), 4.dp.toPx(), reflection)
            }
            EntryWindow(ui(R.string.hovq_agent_updating_title), scrollable = false) {
                Text(ui(R.string.hovq_agent_updating_detail), Modifier.fillMaxWidth(), color = ReadingGlass.muted,
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
internal fun EntryLoading() {
    BackHandler { }
    EntryScene { DottedProgress(Modifier.widthIn(max = 140.dp).fillMaxWidth()) }
}
