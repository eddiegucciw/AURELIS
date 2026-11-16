@file:OptIn(ExperimentalMaterial3Api::class)

package com.aurelis.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
        askToStayAwake()
    }

    /**
     * Android has no "always use the microphone" permission. What keeps listening alive is the
     * listening notification plus being exempt from battery optimisation, so ask for that once.
     */
    private fun askToStayAwake() {
        val sp = getSharedPreferences("aurelis", Context.MODE_PRIVATE)
        if (sp.getBoolean("asked_battery", false) || !Control.hasMic(this)) return
        if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) return
        sp.edit().putBoolean("asked_battery", true).apply()
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            // Some phones don't offer this screen; Settings > Permissions has the manual route.
        }
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
        if (preparing || AppState.speechDownload.value >= 0) return
        preparing = true
        Bg.scope.launch {
            try {
                // The speech pack is built into the app; only very old builds need to download it.
                if (!Ears.installFromApp(app)) {
                    if (!AppState.online.value) return@launch
                    Ears.install(app)
                }
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
        val need = Needs.runtime(ctx)
        if (need.isNotEmpty()) launcher.launch(need)
    }
    LaunchedEffect(Unit) { requestPerms() }
    val sp = remember { ctx.getSharedPreferences("aurelis", Context.MODE_PRIVATE) }
    var showSetup by rememberSaveable { mutableStateOf(!sp.getBoolean("setup_seen", false)) }

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
        if (showSetup) {
            SetupScreen(requestPerms) {
                sp.edit().putBoolean("setup_seen", true).apply()
                showSetup = false
            }
        } else {
        Scaffold(
            containerColor = Pal.Midnight,
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF070F1D), tonalElevation = 0.dp) {
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
                    0 -> HomeScreen(requestPerms, { tab = 4 }, { showSetup = true })
                    1 -> IntelligenceScreen(requestPerms)
                    2 -> MemoryScreen()
                    3 -> CommandScreen()
                    else -> SettingsScreen(requestPerms)
                }
            }
        }
        }
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
