@file:OptIn(ExperimentalMaterial3Api::class)

package com.aurelis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** AURELIS design palette. */
object Pal {
    val Midnight = Color(0xFF050A14)
    val Navy = Color(0xFF0C1525)
    val Cyan = Color(0xFF00D9FF)
    val Blue = Color(0xFF167BFF)
    val Amber = Color(0xFFFFB52E)
    val Silver = Color(0xFFC7D2DC)
    val Soft = Color(0xFFD5E8F5)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hands-free is on by default; it only stays off if the user switched it off.
        val sp = getSharedPreferences("aurelis", Context.MODE_PRIVATE)
        if (!sp.contains("listening_wanted")) Prefs(this).listeningWanted = true
        setContent { AurelisRoot() }
    }

    override fun onResume() {
        super.onResume()
        AppState.uiVisible.value = true
        Control.ensureListening(this)
        Updater.check()
    }

    override fun onPause() {
        AppState.uiVisible.value = false
        super.onPause()
    }
}

/** Starting/stopping the listener from the UI. */
object Control {
    fun hasMic(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun send(ctx: Context, action: String) {
        val i = Intent(ctx, AurelisService::class.java).setAction(action)
        if (AurelisService.running) ctx.startService(i) else ctx.startForegroundService(i)
    }

    fun talk(ctx: Context) = send(ctx, AurelisService.ACTION_TALK)

    fun startStandby(ctx: Context) {
        Prefs(ctx).listeningWanted = true
        send(ctx, AurelisService.ACTION_START)
    }

    @Volatile
    private var preparing = false

    /**
     * Always-listening by default: starts "Hey AURELIS" standby without any button press,
     * downloading the offline speech pack first if it is missing.
     */
    fun ensureListening(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (!prefs.listeningWanted || !hasMic(app) || AurelisService.running) return
        if (Ears.isInstalled(app)) {
            if (AppState.uiVisible.value) startStandby(app)
            return
        }
        if (preparing || !AppState.online.value || AppState.speechDownload.value >= 0) return
        preparing = true
        Bg.scope.launch {
            try {
                Ears.install(app)
                withContext(Dispatchers.Main) { ensureListening(app) }
            } catch (e: Exception) {
                AppState.status.value = "Couldn't download the speech pack. Check your internet, then reopen AURELIS."
            } finally {
                preparing = false
            }
        }
    }

    fun stopListening(ctx: Context) {
        Prefs(ctx).listeningWanted = false
        if (AurelisService.running) {
            ctx.startService(Intent(ctx, AurelisService::class.java).setAction(AurelisService.ACTION_STOP))
        }
    }

    fun hush(ctx: Context) {
        Voice.stop()
        if (AurelisService.running) {
            ctx.startService(Intent(ctx, AurelisService::class.java).setAction(AurelisService.ACTION_HUSH))
        }
    }
}

@Composable
fun AurelisRoot() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Control.ensureListening(ctx)
    }
    val speechProgress by AppState.speechDownload.collectAsState()
    // When the speech pack finishes downloading (from anywhere), start listening straight away.
    LaunchedEffect(speechProgress) { if (speechProgress < 0) Control.ensureListening(ctx) }
    val requestPerms: () -> Unit = {
        val need = mutableListOf<String>()
        if (!Control.hasMic(ctx)) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) launcher.launch(need.toTypedArray())
    }
    LaunchedEffect(Unit) { requestPerms() }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Pal.Midnight,
            surface = Pal.Navy,
            primary = Pal.Cyan,
            onPrimary = Pal.Midnight,
            secondary = Pal.Amber,
            onBackground = Pal.Soft,
            onSurface = Pal.Soft
        )
    ) {
        val names = listOf("Home", "Intelligence", "Memory", "Command", "Settings")
        val icons = listOf(Icons.Default.Home, Icons.Default.AutoAwesome, Icons.Default.Psychology, Icons.Default.Bolt, Icons.Default.Settings)
        Scaffold(
            containerColor = Pal.Midnight,
            bottomBar = {
                NavigationBar(containerColor = Pal.Navy) {
                    names.forEachIndexed { i, name ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icons[i], contentDescription = name) },
                            label = { Text(name, fontSize = 9.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Pal.Cyan,
                                selectedTextColor = Pal.Cyan,
                                indicatorColor = Pal.Blue.copy(alpha = 0.18f),
                                unselectedIconColor = Pal.Silver.copy(alpha = 0.6f),
                                unselectedTextColor = Pal.Silver.copy(alpha = 0.6f)
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(Pal.Midnight)) {
                when (tab) {
                    0 -> HomeScreen(requestPerms) { tab = 4 }
                    1 -> IntelligenceScreen(requestPerms)
                    2 -> MemoryScreen()
                    3 -> CommandScreen()
                    else -> SettingsScreen(requestPerms)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- AI core

@Composable
fun AiCore(state: CoreState, online: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val tr = rememberInfiniteTransition(label = "core")
    val slow by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "slow")
    val fast by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "fast")
    val breath by tr.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath"
    )
    val beat by tr.animateFloat(
        0f, 1f, infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "beat"
    )
    val phase by tr.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "wave")

    val dim = !online && (state == CoreState.IDLE || state == CoreState.STANDBY)
    val glow = when {
        state == CoreState.ERROR -> Pal.Amber
        state == CoreState.THINKING -> Pal.Blue
        dim -> Pal.Blue
        else -> Pal.Cyan
    }
    val intensity = (when (state) {
        CoreState.WAKE -> 1f
        CoreState.SPEAKING -> 0.6f + 0.4f * beat
        CoreState.LISTENING -> 0.9f
        CoreState.THINKING -> 0.75f
        CoreState.STANDBY -> 0.35f + 0.4f * breath
        CoreState.ERROR -> 0.5f + 0.4f * beat
        CoreState.OFFLINE -> 0.3f
        CoreState.IDLE -> 0.35f + 0.2f * breath
    }) * (if (dim) 0.6f else 1f)

    Box(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.aspectRatio(1f)) {
            val c = center
            val r = size.minDimension * 0.36f

            // Outer illumination
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.30f * intensity), Pal.Blue.copy(alpha = 0.08f * intensity), Color.Transparent),
                    center = c, radius = r * 1.9f
                ),
                radius = r * 1.9f, center = c
            )
            drawCircle(Pal.Blue.copy(alpha = 0.3f + 0.5f * intensity), r * (0.98f + 0.04f * breath), c, style = Stroke(2.dp.toPx()))

            // Futuristic markings
            rotate(slow * 0.3f, c) {
                for (i in 0 until 72) {
                    val a = Math.toRadians(i * 5.0)
                    val long = i % 6 == 0
                    val r1 = r * 1.06f
                    val r2 = r * (if (long) 1.13f else 1.09f)
                    drawLine(
                        Pal.Silver.copy(alpha = if (long) 0.5f else 0.2f),
                        Offset(c.x + (cos(a) * r1).toFloat(), c.y + (sin(a) * r1).toFloat()),
                        Offset(c.x + (cos(a) * r2).toFloat(), c.y + (sin(a) * r2).toFloat()),
                        strokeWidth = 1.2.dp.toPx()
                    )
                }
            }

            // Cyan light channel
            drawCircle(glow.copy(alpha = 0.45f + 0.5f * intensity), r * 0.91f, c, style = Stroke(3.dp.toPx()))

            // Titanium-silver metallic segments
            rotate(-slow, c) {
                for (i in 0 until 12) {
                    drawArc(
                        Pal.Silver.copy(alpha = 0.75f), i * 30f + 4f, 22f, false,
                        topLeft = Offset(c.x - r * 0.79f, c.y - r * 0.79f), size = Size(r * 1.58f, r * 1.58f),
                        style = Stroke(7.dp.toPx())
                    )
                }
            }

            // Amber orbit accents
            drawArc(
                Pal.Amber.copy(alpha = if (dim) 0.4f else 0.9f), slow * 1.5f, 55f, false,
                topLeft = Offset(c.x - r * 0.69f, c.y - r * 0.69f), size = Size(r * 1.38f, r * 1.38f),
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
            )
            val oa = Math.toRadians((slow * 2f).toDouble())
            drawCircle(
                Pal.Amber, 4.dp.toPx(),
                Offset(c.x + (cos(oa) * r * 1.0f).toFloat(), c.y + (sin(oa) * r * 1.0f).toFloat())
            )

            // Inner arcs: rotating blue segments while thinking, cyan sweep otherwise
            val ir = r * 0.64f
            if (state == CoreState.THINKING) {
                for (i in 0 until 3) {
                    drawArc(
                        Pal.Blue, fast + i * 120f, 70f, false,
                        topLeft = Offset(c.x - ir, c.y - ir), size = Size(ir * 2, ir * 2),
                        style = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            } else {
                drawArc(
                    glow, -slow, 115f, false,
                    topLeft = Offset(c.x - ir, c.y - ir), size = Size(ir * 2, ir * 2),
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Speaking: cyan and amber pulses
            if (state == CoreState.SPEAKING) {
                drawCircle(Pal.Amber.copy(alpha = 0.55f * (1f - beat)), r * (0.6f + 0.45f * beat), c, style = Stroke(2.dp.toPx()))
                drawCircle(Pal.Cyan.copy(alpha = 0.5f * beat), r * (0.7f + 0.3f * (1f - beat)), c, style = Stroke(2.dp.toPx()))
            }
            // Wake: bright activation pulse
            if (state == CoreState.WAKE) {
                drawCircle(Pal.Cyan.copy(alpha = 0.9f), r * (1.0f + 0.1f * beat), c, style = Stroke(6.dp.toPx()))
            }

            // Dark digital orb
            val orb = r * 0.56f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF203C59), Color(0xFF0A192C), Pal.Midnight), center = c, radius = orb
                ),
                radius = orb, center = c
            )
            drawCircle(glow.copy(alpha = 0.8f), orb, c, style = Stroke(2.dp.toPx()))

            // Listening: animated cyan waveform across the orb
            if (state == CoreState.LISTENING) {
                val path = Path()
                val steps = 48
                for (i in 0..steps) {
                    val fx = i / steps.toFloat()
                    val x = c.x - orb * 0.85f + fx * orb * 1.7f
                    val env = sin(fx * PI).toFloat()
                    val y = c.y + orb * 0.55f + sin(fx * 14f + phase) * orb * 0.16f * env * (0.6f + 0.4f * abs(sin(phase * 0.5f)))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Pal.Cyan, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Text("A", color = Pal.Silver.copy(alpha = if (dim) 0.6f else 1f), fontSize = 64.sp, fontWeight = FontWeight.ExtraLight)
    }
}

// ---------------------------------------------------------------------- Home

@Composable
fun HomeScreen(requestPerms: () -> Unit, openSettings: () -> Unit) {
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
            !speechReady -> {
                AppState.status.value = "Download the offline speech pack in Settings first (about 40 MB)."
            }
            else -> {
                Control.startStandby(ctx)
                wanted = true
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))
        Text("A U R E L I S", color = Pal.Soft, fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 5.sp)
        Text("YOUR INTELLIGENCE. ALWAYS.", color = Pal.Cyan.copy(alpha = 0.75f), fontSize = 9.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(if (online) "ONLINE" else "OFFLINE", if (online) Pal.Cyan else Pal.Amber)
            StatusChip(
                when (mode) {
                    BrainMode.ONLINE -> "CLOUD AI"
                    BrainMode.OFFLINE -> "LOCAL AI"
                    BrainMode.LIMITED -> "LIMITED"
                },
                if (mode == BrainMode.LIMITED) Pal.Amber else Pal.Blue
            )
            StatusChip(if (running && wake) "LISTENING" else "MIC OFF", if (running && wake) Pal.Cyan else Pal.Silver)
        }

        AiCore(state, online, Modifier.fillMaxWidth().weight(1f), onTap = talk)

        Text(
            when {
                state == CoreState.IDLE && !online -> "Offline mode"
                else -> state.label
            }.uppercase(),
            color = if (state == CoreState.ERROR) Pal.Amber else Pal.Soft,
            fontSize = 13.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center
        )
        if (heard.isNotBlank() && state != CoreState.STANDBY) {
            Text("“$heard”", color = Pal.Cyan.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (status.isNotBlank()) {
            Text(status, color = Pal.Amber, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
        if (speechDl >= 0) {
            Text("Preparing hands-free listening… $speechDl%", color = Pal.Cyan, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))

        if (updateUrl != null) {
            Card(
                Modifier.fillMaxWidth().clickable { Updater.install(ctx) },
                colors = CardDefaults.cardColors(containerColor = Pal.Blue.copy(alpha = 0.25f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    if (updateDl >= 0) "Downloading update… $updateDl%" else "UPDATE AVAILABLE · Tap to update AURELIS",
                    color = Pal.Soft, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(14.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        val last = messages.lastOrNull { it.role == "assistant" }
        if (last != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Pal.Navy),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(last.text, color = Pal.Soft, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(14.dp))
            }
            Spacer(Modifier.height(10.dp))
        }

        if ((!speechReady && speechDl < 0) || mode == BrainMode.LIMITED) {
            Card(
                Modifier.fillMaxWidth().clickable { openSettings() },
                colors = CardDefaults.cardColors(containerColor = Pal.Navy),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("FINISH SETUP", color = Pal.Amber, fontSize = 11.sp, letterSpacing = 2.sp)
                    if (!speechReady) Text("• Download the offline speech pack for \"Hey AURELIS\"", color = Pal.Soft, fontSize = 12.sp)
                    if (mode == BrainMode.LIMITED) Text("• Add a cloud key or an offline brain", color = Pal.Soft, fontSize = 12.sp)
                    Text("Tap to open Settings", color = Pal.Cyan, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = toggleWake,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (wanted) Pal.Cyan else Pal.Navy,
                    contentColor = if (wanted) Pal.Midnight else Pal.Cyan
                )
            ) { Text(if (wanted) "Hey AURELIS: ON" else "Hey AURELIS: OFF", fontSize = 12.sp) }
            OutlinedButton(onClick = talk, modifier = Modifier.weight(1f)) {
                Icon(if (state == CoreState.SPEAKING) Icons.Default.Stop else Icons.Default.Mic, null, tint = Pal.Cyan)
                Spacer(Modifier.width(6.dp))
                Text(if (state == CoreState.SPEAKING) "Stop" else "Talk", color = Pal.Cyan, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun StatusChip(text: String, color: Color) {
    Row(
        Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(title, color = Pal.Soft, fontSize = 20.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Light)
        Text(subtitle, color = Pal.Silver.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = Pal.Navy),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Pal.Cyan, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
