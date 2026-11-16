package com.aurelis.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** How loud things are right now: the microphone, or AURELIS's own voice while it speaks. */
@Composable
fun liveLevel(state: CoreState, beat: Float, phase: Float): Float {
    val mic by Levels.mic.collectAsState()
    val target = when (state) {
        CoreState.SPEAKING -> 0.35f + 0.4f * beat * (0.6f + 0.4f * sin(phase * 3f))
        CoreState.LISTENING, CoreState.STANDBY, CoreState.WAKE -> (mic * 1.6f).coerceIn(0f, 1f)
        CoreState.THINKING -> 0.15f
        else -> 0f
    }
    val v by animateFloatAsState(target, tween(110), label = "level")
    return v
}

@Composable
fun AiCore(state: CoreState, online: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val tr = rememberInfiniteTransition(label = "core")
    val slow by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "slow")
    val fast by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "fast")
    val breath by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val beat by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "beat")
    val phase by tr.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    val level = liveLevel(state, beat, phase)

    val ctx = LocalContext.current
    val artId = remember { ctx.resources.getIdentifier("aurelis_art", "drawable", ctx.packageName) }
    val dim = !online && (state == CoreState.IDLE || state == CoreState.STANDBY)
    val glow = when (state) {
        CoreState.ERROR -> Pal.Amber
        CoreState.THINKING -> Pal.Blue
        CoreState.SPEAKING -> Pal.Amber
        else -> if (dim) Pal.Blue else Pal.Cyan
    }
    val calm = (
        when (state) {
            CoreState.WAKE -> 1f
            CoreState.LISTENING -> 0.8f
            CoreState.SPEAKING -> 0.75f
            CoreState.THINKING -> 0.7f
            else -> 0.35f + 0.25f * breath
        }
        ) * (if (dim) 0.6f else 1f)
    val intensity = (calm + level * 0.6f).coerceIn(0f, 1.2f)
    val scale = 1f + 0.018f * breath + 0.05f * level

    Box(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.aspectRatio(1f)) {
            val c = center
            val r = size.minDimension * 0.36f
            // Aura
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.5f * intensity), glow.copy(alpha = 0.14f * intensity), Color.Transparent),
                    center = c, radius = r * (1.45f + 0.25f * level)
                ),
                radius = r * (1.45f + 0.25f * level), center = c
            )
            // Sound-reactive ring of bars around the core
            val bars = 72
            for (i in 0 until bars) {
                val a = (i * 360.0 / bars + slow * 0.5) * PI / 180.0
                val wobble = 0.5f + 0.5f * sin(i * 0.9f + phase * 2f) * cos(i * 0.37f - phase)
                val len = r * (0.03f + (0.06f + 0.22f * level) * wobble)
                val r1 = r * 1.13f
                drawLine(
                    glow.copy(alpha = (0.25f + 0.6f * intensity * wobble).coerceIn(0f, 1f)),
                    Offset(c.x + (cos(a) * r1).toFloat(), c.y + (sin(a) * r1).toFloat()),
                    Offset(c.x + (cos(a) * (r1 + len)).toFloat(), c.y + (sin(a) * (r1 + len)).toFloat()),
                    strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round
                )
            }
            // State orbit
            drawArc(
                glow.copy(alpha = (0.35f + 0.5f * intensity).coerceIn(0f, 1f)),
                if (state == CoreState.THINKING) fast else slow * 2f,
                if (state == CoreState.THINKING) 110f else 220f,
                false,
                topLeft = Offset(c.x - r * 1.06f, c.y - r * 1.06f), size = Size(r * 2.12f, r * 2.12f),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            )
            val oa = Math.toRadians((slow * 2f).toDouble())
            drawCircle(Pal.Amber.copy(alpha = if (dim) 0.5f else 1f), 3.dp.toPx(), Offset(c.x + (cos(oa) * r * 1.06f).toFloat(), c.y + (sin(oa) * r * 1.06f).toFloat()))
            if (state == CoreState.WAKE) drawCircle(Pal.Cyan.copy(alpha = 0.8f), r * (1.0f + 0.06f * beat), c, style = Stroke(4.dp.toPx()))
            if (artId == 0) {
                drawCircle(
                    brush = Brush.radialGradient(listOf(Color(0xFF1C3A5C), Color(0xFF0A192C), Pal.Midnight), center = c, radius = r),
                    radius = r, center = c
                )
                drawCircle(glow.copy(alpha = 0.7f), r, c, style = Stroke(2.dp.toPx()))
            }
        }
        if (artId != 0) {
            Image(
                painterResource(artId), contentDescription = "AURELIS",
                modifier = Modifier
                    .fillMaxSize(0.72f)
                    .aspectRatio(1f)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape),
                contentScale = ContentScale.Fit,
                alpha = if (dim) 0.7f else 1f
            )
            Canvas(Modifier.aspectRatio(1f).graphicsLayer { scaleX = scale; scaleY = scale }) {
                val c = center
                val r = size.minDimension * 0.36f
                if (state == CoreState.LISTENING) {
                    val mic = Offset(c.x, c.y + r * 0.5f)
                    drawCircle(Pal.Cyan.copy(alpha = 0.4f + 0.5f * level), r * (0.2f + 0.12f * level), mic, style = Stroke(3.dp.toPx()))
                }
                if (state == CoreState.THINKING) {
                    val ir = r * 0.66f
                    for (i in 0 until 3) {
                        drawArc(
                            Pal.Cyan.copy(alpha = 0.85f), fast + i * 120f, 50f, false,
                            topLeft = Offset(c.x - ir, c.y - ir), size = Size(ir * 2, ir * 2),
                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }
        } else {
            Text("A", color = Pal.Soft.copy(alpha = if (dim) 0.6f else 1f), fontSize = 60.sp, fontWeight = FontWeight.ExtraLight)
        }
    }
}

/** Flowing sound waves that follow the live audio level. */
@Composable
fun SoundWaves(state: CoreState, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "waves")
    val phase by tr.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "p")
    val beat by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val level = liveLevel(state, beat, phase)
    val base = when (state) {
        CoreState.STANDBY, CoreState.IDLE, CoreState.OFFLINE -> 0.06f
        CoreState.THINKING -> 0.12f
        else -> 0.1f
    }
    val amp = (base + level * 0.9f).coerceIn(0f, 1f)
    val colors = when (state) {
        CoreState.SPEAKING -> listOf(Pal.Amber, Pal.Cyan, Pal.Blue)
        CoreState.THINKING -> listOf(Pal.Blue, Pal.Cyan, Pal.Silver)
        else -> listOf(Pal.Cyan, Pal.Blue, Pal.Amber)
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mid = h / 2
        for (layer in 0 until 3) {
            val path = Path()
            val steps = 90
            val freq = 2.2f + layer * 0.9f
            val speed = 1f + layer * 0.45f
            val a = amp * h * 0.42f * (1f - layer * 0.22f)
            for (i in 0..steps) {
                val x = w * i / steps
                val t = i / steps.toFloat()
                val env = sin(t * PI).toFloat()
                val y = mid + a * env * sin(t * freq * 2 * PI.toFloat() + phase * speed + layer)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path, colors[layer].copy(alpha = 0.9f - layer * 0.25f),
                style = Stroke((2.6f - layer * 0.6f).dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun Glass(modifier: Modifier = Modifier, accent: Color = Color.White, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val m = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.025f))))
        .border(1.dp, accent.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
    Column((if (onClick != null) m.clickable { onClick() } else m).padding(16.dp), content = content)
}

@Composable
fun HomeScreen(requestPerms: () -> Unit, openSettings: () -> Unit, openSetup: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val state by AppState.core.collectAsState()
    val online by AppState.online.collectAsState()
    val running by AppState.serviceRunning.collectAsState()
    val wake by AppState.wakeActive.collectAsState()
    val heard by AppState.heard.collectAsState()
    val status by AppState.status.collectAsState()
    val messages by AppState.messages.collectAsState()
    val visible by AppState.uiVisible.collectAsState()
    val speechDl by AppState.speechDownload.collectAsState()
    var wanted by remember(visible, running) { mutableStateOf(prefs.listeningWanted) }
    val speechReady = remember(visible, speechDl) { Ears.isInstalled(ctx) }
    val updateUrl by Updater.available.collectAsState()
    val updateDl by Updater.progress.collectAsState()
    val mode = remember(visible, online, running) { Assistant.mode(ctx) }
    val missing = remember(visible) { Needs.missing(ctx) }

    val talk: () -> Unit = {
        when {
            !Control.hasMic(ctx) -> requestPerms()
            state == CoreState.SPEAKING -> Control.hush(ctx)
            else -> Control.talk(ctx)
        }
    }
    val toggleWake: () -> Unit = {
        when {
            wanted -> {
                Control.stopListening(ctx)
                wanted = false
            }
            !Control.hasMic(ctx) -> requestPerms()
            else -> {
                Control.ensureListening(ctx)
                Control.startStandby(ctx)
                wanted = true
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF0E2542), Pal.Midnight), radius = 1400f, center = Offset(540f, 420f)))
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AURELIS", color = Pal.Soft, fontSize = 18.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp)
                    Text("YOUR INTELLIGENCE. ALWAYS.", color = Pal.Cyan.copy(alpha = 0.6f), fontSize = 8.sp, letterSpacing = 2.5.sp)
                }
                Box {
                    IconButton(onClick = openSetup) { Icon(Icons.Default.Tune, "Set up", tint = Pal.Soft) }
                    if (missing > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp).clip(CircleShape).background(Pal.Amber),
                            contentAlignment = Alignment.Center
                        ) { Text("$missing", color = Pal.Midnight, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(if (online) "ONLINE" else "OFFLINE", if (online) Pal.Cyan else Pal.Amber)
                StatusChip(
                    when (mode) {
                        BrainMode.ONLINE -> "CLOUD AI"
                        BrainMode.OFFLINE -> "LOCAL AI"
                        BrainMode.LIMITED -> "COMMANDS"
                    },
                    if (mode == BrainMode.LIMITED) Pal.Silver else Pal.Blue
                )
                StatusChip(if (running && wake) "LISTENING" else if (running) "ACTIVE" else "MIC OFF", if (running) Pal.Cyan else Pal.Silver)
            }

            AiCore(state, online, Modifier.fillMaxWidth().weight(1f), onTap = talk)

            SoundWaves(state, Modifier.fillMaxWidth().height(64.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                (if (state == CoreState.IDLE && !online) "Offline mode" else state.label).uppercase(),
                color = if (state == CoreState.ERROR) Pal.Amber else Pal.Soft.copy(alpha = 0.9f),
                fontSize = 11.sp, letterSpacing = 3.sp, textAlign = TextAlign.Center
            )
            AnimatedVisibility(visible = heard.isNotBlank() && state != CoreState.STANDBY, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    "“$heard”", color = Pal.Cyan, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (status.isNotBlank()) {
                Text(status, color = Pal.Amber, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            if (speechDl >= 0) {
                Text("Preparing offline listening… $speechDl%", color = Pal.Cyan, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))

            if (updateUrl != null) {
                Glass(accent = Pal.Cyan, onClick = { Updater.install(ctx) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, null, tint = Pal.Cyan)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (updateDl >= 0) "Downloading update… $updateDl%" else "A new version is ready · tap to update",
                            color = Pal.Soft, fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            val last = messages.lastOrNull { it.role == "assistant" }
            if (last != null) {
                Glass {
                    Text("AURELIS", color = Pal.Cyan.copy(alpha = 0.7f), fontSize = 9.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(last.text, color = Pal.Soft, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
            } else if (missing > 0 || (!speechReady && speechDl < 0) || mode == BrainMode.LIMITED) {
                Glass(accent = Pal.Amber, onClick = if (missing > 0) openSetup else openSettings) {
                    Text("FINISH SETUP", color = Pal.Amber, fontSize = 10.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(4.dp))
                    if (missing > 0) Text("$missing permission${if (missing > 1) "s" else ""} still off. Tap to switch them on.", color = Pal.Soft, fontSize = 13.sp)
                    if (mode == BrainMode.LIMITED) Text("Add your Gemini key in Settings for conversations.", color = Pal.Soft, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = toggleWake,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (wanted) Pal.Cyan else Color.White.copy(alpha = 0.07f),
                        contentColor = if (wanted) Pal.Midnight else Pal.Soft
                    )
                ) {
                    Icon(if (wanted) Icons.Default.Hearing else Icons.Default.HearingDisabled, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (wanted) "Hands-free on" else "Hands-free off", fontSize = 13.sp)
                }
                Button(
                    onClick = talk,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.07f), contentColor = Pal.Cyan)
                ) {
                    Icon(if (state == CoreState.SPEAKING) Icons.Default.Stop else Icons.Default.Mic, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state == CoreState.SPEAKING) "Stop" else "Talk now", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}
