package com.aurelis.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One thing AURELIS needs Android's permission for. */
class Need(
    val title: String,
    val why: String,
    val icon: ImageVector,
    val granted: Boolean,
    val essential: Boolean,
    val grant: () -> Unit
)

object Needs {
    private fun open(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    private fun pkg(ctx: Context) = Uri.parse("package:" + ctx.packageName)

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** The runtime permissions Android can ask for with a single pop-up. */
    fun runtime(ctx: Context): Array<String> {
        val list = arrayListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.POST_NOTIFICATIONS
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
            list += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return list.filter { !has(ctx, it) }.toTypedArray()
    }

    fun list(ctx: Context, askRuntime: () -> Unit): List<Need> {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val out = ArrayList<Need>()
        out += Need(
            "Microphone", "So AURELIS can hear you. Standby audio stays on this phone.",
            Icons.Default.Mic, has(ctx, Manifest.permission.RECORD_AUDIO), true, askRuntime
        )
        if (Build.VERSION.SDK_INT >= 33) {
            out += Need(
                "Notifications", "Reminders, alarms and the listening indicator.",
                Icons.Default.Notifications, has(ctx, Manifest.permission.POST_NOTIFICATIONS), true, askRuntime
            )
        }
        out += Need(
            "Screen control", "Scroll, tap, type, read the screen, lock and take screenshots by voice.",
            Icons.Default.TouchApp, AurelisAccess.enabled(ctx), true
        ) { open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        out += Need(
            "Display over other apps", "Lets AURELIS open apps and come forward when you call it.",
            Icons.Default.Layers, Settings.canDrawOverlays(ctx), true
        ) { open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg(ctx))) }
        out += Need(
            "Unrestricted battery", "Keeps hands-free listening alive when the screen is off.",
            Icons.Default.BatteryChargingFull, pm.isIgnoringBatteryOptimizations(ctx.packageName), true
        ) { open(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(ctx))) }
        out += Need(
            "Contacts", "Call, text and WhatsApp people by name.",
            Icons.Default.Contacts, has(ctx, Manifest.permission.READ_CONTACTS), false, askRuntime
        )
        val files = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)
        out += Need(
            "Files", "Find, open, summarise, rename, move and share your files.",
            Icons.Default.Folder, files, false
        ) {
            if (Build.VERSION.SDK_INT >= 30) {
                open(ctx, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg(ctx))) ||
                    open(ctx, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } else {
                askRuntime()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            out += Need(
                "Photos and media", "Find your latest photo, video or song.",
                Icons.Default.PhotoLibrary, has(ctx, Manifest.permission.READ_MEDIA_IMAGES), false, askRuntime
            )
        }
        out += Need(
            "Notification access", "Read and clear your notifications when you ask.",
            Icons.Default.NotificationsActive, NotificationReader.enabled(ctx), false
        ) { open(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        out += Need(
            "Modify system settings", "Change brightness, screen timeout, rotation and text size.",
            Icons.Default.BrightnessMedium, Settings.System.canWrite(ctx), false
        ) { open(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkg(ctx))) }
        out += Need(
            "Do Not Disturb", "Switch silent mode and Do Not Disturb by voice.",
            Icons.Default.DoNotDisturbOn, nm.isNotificationPolicyAccessGranted, false
        ) { open(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        if (Build.VERSION.SDK_INT in 31..32) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            out += Need(
                "Exact reminders", "Reminders ring at exactly the right minute.",
                Icons.Default.Alarm, am.canScheduleExactAlarms(), false
            ) { open(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(ctx))) }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            out += Need(
                "Full-screen alerts", "Lets AURELIS appear over the lock screen when you call it.",
                Icons.Default.Fullscreen, nm.canUseFullScreenIntent(), false
            ) { open(ctx, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg(ctx))) }
        }
        return out
    }

    fun missing(ctx: Context): Int = list(ctx) {}.count { !it.granted }

    fun appInfo(ctx: Context) {
        open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(ctx)))
    }
}

/** Full-screen setup: every permission AURELIS uses, what it's for, and one tap to grant it. */
@Composable
fun SetupScreen(askRuntime: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val visible by AppState.uiVisible.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            tick++
        }
    }
    val needs = remember(visible, tick) { Needs.list(ctx, askRuntime) }
    val ready = needs.count { it.granted }
    val progress = if (needs.isEmpty()) 1f else ready / needs.size.toFloat()

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A1830), Pal.Midnight, Pal.Midnight)))
    ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 28.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("SET UP", color = Pal.Cyan, fontSize = 11.sp, letterSpacing = 4.sp)
                Spacer(Modifier.height(4.dp))
                Text("Give AURELIS its powers", color = Pal.Soft, fontSize = 26.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Each switch unlocks a group of voice commands. You can change any of them later in Android settings.",
                    color = Pal.Silver.copy(alpha = 0.7f), fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Pal.Cyan, trackColor = Pal.Navy
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("$ready / ${needs.size}", color = Pal.Soft, fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))
                if (Needs.runtime(ctx).isNotEmpty()) {
                    Button(
                        onClick = askRuntime,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Pal.Cyan, contentColor = Pal.Midnight)
                    ) { Text("Allow the quick permissions", fontWeight = FontWeight.Medium) }
                    Spacer(Modifier.height(6.dp))
                }
            }
            items(needs, key = { it.title }) { need -> NeedRow(need) }
            if (Build.VERSION.SDK_INT >= 33) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Pal.Amber.copy(alpha = 0.08f))
                            .border(1.dp, Pal.Amber.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                            .clickable { Needs.appInfo(ctx) }
                            .padding(16.dp)
                    ) {
                        Text("Android says \"Restricted setting\"?", color = Pal.Amber, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Apps installed outside the Play Store need one extra step for Screen control and Notification access: " +
                                "tap here to open AURELIS app info, tap the three dots at the top right, choose Allow restricted settings, then come back.",
                            color = Pal.Soft.copy(alpha = 0.85f), fontSize = 12.sp
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Pal.Midnight, Pal.Midnight)))
                .padding(20.dp)
        ) {
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (ready == needs.size) Pal.Cyan else Pal.Navy, contentColor = if (ready == needs.size) Pal.Midnight else Pal.Soft)
            ) { Text(if (ready == needs.size) "All set" else "Continue", fontSize = 15.sp) }
        }
    }
}

@Composable
private fun NeedRow(need: Need) {
    val tint = if (need.granted) Pal.Cyan else if (need.essential) Pal.Amber else Pal.Silver
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, (if (need.granted) Pal.Cyan else Color.White).copy(alpha = if (need.granted) 0.25f else 0.07f), RoundedCornerShape(18.dp))
            .clickable(enabled = !need.granted) { need.grant() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { Icon(need.icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(need.title, color = Pal.Soft, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (need.essential && !need.granted) {
                    Spacer(Modifier.width(8.dp))
                    Text("ESSENTIAL", color = Pal.Amber, fontSize = 8.sp, letterSpacing = 1.sp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(need.why, color = Pal.Silver.copy(alpha = 0.65f), fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        if (need.granted) {
            Icon(Icons.Default.CheckCircle, "Granted", tint = Pal.Cyan)
        } else {
            Text(
                "ALLOW",
                color = Pal.Midnight, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(tint).padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}
