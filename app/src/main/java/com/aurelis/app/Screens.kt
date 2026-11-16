@file:OptIn(ExperimentalMaterial3Api::class)

package com.aurelis.app

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AURELIS", text))
    toast(ctx, "Copied")
}

private fun share(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "AURELIS export")
        .putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Export AURELIS data"))
}

private fun fmt(t: Long, pattern: String = "EEE d MMM, h:mm a") = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))

private fun openSettings(ctx: Context, action: String, withPackage: Boolean) {
    try {
        val i = Intent(action)
        if (withPackage) i.data = Uri.parse("package:" + ctx.packageName)
        ctx.startActivity(i)
    } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
    }
}

suspend fun exportAll(): String = withContext(Dispatchers.IO) {
    val db = AurelisApp.instance.db
    JSONObject()
        .put("app", "AURELIS")
        .put("exportedAt", fmt(System.currentTimeMillis(), "yyyy-MM-dd HH:mm"))
        .put("memories", JSONArray(db.memories().list().map {
            JSONObject().put("category", it.category).put("content", it.content).put("updated", fmt(it.updatedAt, "yyyy-MM-dd HH:mm"))
        }))
        .put("tasks", JSONArray(db.tasks().list().map { JSONObject().put("title", it.title).put("done", it.done) }))
        .put("reminders", JSONArray(db.reminders().pending().map {
            JSONObject().put("text", it.text).put("at", fmt(it.triggerAt, "yyyy-MM-dd HH:mm"))
        }))
        .toString(2)
}

// ---------------------------------------------------------------------- Intelligence

@Composable
fun IntelligenceScreen(requestPerms: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val messages by AppState.messages.collectAsState()
    val speaking by Voice.speaking.collectAsState()
    val online by AppState.online.collectAsState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }

    val send: () -> Unit = send@{
        val text = input.trim()
        if (text.isEmpty() || busy) return@send
        input = ""
        busy = true
        val before = AppState.core.value
        AppState.core.value = CoreState.THINKING
        scope.launch {
            val r = Assistant.respond(ctx, text)
            busy = false
            if (prefs.speakTyped) {
                AppState.core.value = CoreState.SPEAKING
                Voice.speak(r.text) {
                    if (AppState.core.value == CoreState.SPEAKING) {
                        AppState.core.value = if (AppState.wakeActive.value) CoreState.STANDBY else CoreState.IDLE
                    }
                }
            } else {
                AppState.core.value = if (before == CoreState.STANDBY) CoreState.STANDBY else CoreState.IDLE
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("INTELLIGENCE", "${Assistant.mode(ctx).label} · ${if (online) "online" else "offline"}")
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "Ask anything, or try: \"remember that my favourite colour is blue\", \"remind me to call mum in 20 minutes\", \"add task buy milk\", \"what's on my to do list\".",
                        color = Pal.Silver.copy(alpha = 0.6f), fontSize = 13.sp
                    )
                }
            }
            items(messages) { m -> Bubble(m) }
            if (busy) item { Text("Thinking…", color = Pal.Blue, fontSize = 12.sp) }
        }
        if (speaking) {
            TextButton(onClick = { Control.hush(ctx) }) {
                Icon(Icons.Default.Stop, null, tint = Pal.Amber)
                Text(" Stop speaking", color = Pal.Amber)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type to AURELIS…") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            IconButton(onClick = send) { Icon(Icons.Default.Send, "Send", tint = Pal.Cyan) }
            IconButton(onClick = { if (Control.hasMic(ctx)) Control.talk(ctx) else requestPerms() }) {
                Icon(Icons.Default.Mic, "Speak", tint = Pal.Cyan)
            }
        }
    }
}

@Composable
fun Bubble(m: Msg) {
    val ctx = LocalContext.current
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(0.85f)
                .background(if (mine) Pal.Blue.copy(alpha = 0.22f) else Pal.Navy, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text(m.text, color = Pal.Soft, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(m.time, "h:mm a"), color = Pal.Silver.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.weight(1f))
                if (!mine) {
                    IconButton(onClick = { Voice.speak(m.text) }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.PlayArrow, "Play", tint = Pal.Cyan, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = { copy(ctx, m.text) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.ContentCopy, "Copy", tint = Pal.Silver, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- Memory

@Composable
fun MemoryScreen() {
    val ctx = LocalContext.current
    val db = AurelisApp.instance.db
    val memories by db.memories().observe().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val shown = memories.filter { (cat == null || it.category == cat) && (query.isBlank() || it.content.contains(query, true)) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("MEMORY", "${memories.size} item(s), stored only on this phone")
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search memories") }, singleLine = true
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("All") })
            MemoryCategory.ALL.forEach { c ->
                FilterChip(selected = cat == c, onClick = { cat = if (cat == c) null else c }, label = { Text(c) })
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.isEmpty()) {
                item {
                    Text(
                        "Nothing here yet. Say \"remember that…\" or tap Add.",
                        color = Pal.Silver.copy(alpha = 0.6f), fontSize = 13.sp
                    )
                }
            }
            items(shown, key = { it.id }) { m ->
                Card(colors = CardDefaults.cardColors(containerColor = Pal.Navy), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.category.uppercase(), color = Pal.Amber, fontSize = 9.sp, letterSpacing = 1.sp)
                            Text(m.content, color = Pal.Soft, fontSize = 14.sp)
                            Text(fmt(m.updatedAt), color = Pal.Silver.copy(alpha = 0.5f), fontSize = 10.sp)
                        }
                        IconButton(onClick = { editing = m }) { Icon(Icons.Default.Edit, "Edit", tint = Pal.Cyan) }
                        IconButton(onClick = { scope.launch { db.memories().delete(m) } }) {
                            Icon(Icons.Default.Delete, "Delete", tint = Pal.Amber)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }, modifier = Modifier.weight(1f)) { Text("Add") }
            OutlinedButton(onClick = { scope.launch { share(ctx, exportAll()) } }, modifier = Modifier.weight(1f)) { Text("Export") }
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f)) { Text("Clear", color = Pal.Amber) }
        }
    }

    if (adding || editing != null) {
        MemoryDialog(
            initial = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { category, content ->
                val e = editing
                scope.launch {
                    if (e == null) db.memories().insert(Memory(category = category, content = content))
                    else db.memories().update(e.copy(category = category, content = content, updatedAt = System.currentTimeMillis()))
                }
                adding = false
                editing = null
            }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all memories?") },
            text = { Text("This permanently deletes everything AURELIS remembers. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { db.memories().clear() }
                    confirmClear = false
                }) { Text("Delete all", color = Pal.Amber) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun MemoryDialog(initial: Memory?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: MemoryCategory.IMPORTANT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New memory" else "Edit memory") },
        text = {
            Column {
                OutlinedTextField(value = content, onValueChange = { content = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MemoryCategory.ALL.forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (content.isNotBlank()) onSave(category, content.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------------- Command centre

@Composable
fun CommandScreen() {
    val ctx = LocalContext.current
    val db = AurelisApp.instance.db
    val reminders by db.reminders().observePending().collectAsState(initial = emptyList())
    val tasks by db.tasks().observe().collectAsState(initial = emptyList())
    val history by db.history().observe().collectAsState(initial = emptyList())
    var command by remember { mutableStateOf("") }
    var newTask by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { ScreenTitle("COMMAND CENTRE", "Reminders, tasks and automation") }
        item {
            Section("QUICK COMMAND") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = command, onValueChange = { command = it }, modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("e.g. remind me to pray at 6 pm", fontSize = 13.sp) }
                    )
                    IconButton(onClick = {
                        val c = command.trim()
                        if (c.isNotEmpty()) {
                            command = ""
                            scope.launch { result = Assistant.respond(ctx, c).text }
                        }
                    }) { Icon(Icons.Default.Send, "Run", tint = Pal.Cyan) }
                }
                if (result.isNotBlank()) Text(result, color = Pal.Soft, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { Text("REMINDERS (${reminders.size})", color = Pal.Cyan, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.padding(vertical = 6.dp)) }
        if (reminders.isEmpty()) item { Text("No upcoming reminders.", color = Pal.Silver.copy(alpha = 0.6f), fontSize = 13.sp) }
        items(reminders, key = { "r" + it.id }) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Alarm, null, tint = Pal.Amber)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(r.text, color = Pal.Soft, fontSize = 14.sp)
                    Text(fmt(r.triggerAt), color = Pal.Silver.copy(alpha = 0.6f), fontSize = 11.sp)
                }
                IconButton(onClick = {
                    Reminders.cancel(ctx, r)
                    scope.launch { db.reminders().delete(r) }
                }) { Icon(Icons.Default.Delete, "Delete", tint = Pal.Amber) }
            }
        }
        item { Text("TASKS (${tasks.count { !it.done }} open)", color = Pal.Cyan, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTask, onValueChange = { newTask = it }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("New task", fontSize = 13.sp) }
                )
                IconButton(onClick = {
                    val t = newTask.trim()
                    if (t.isNotEmpty()) {
                        newTask = ""
                        scope.launch { db.tasks().insert(TaskItem(title = t)) }
                    }
                }) { Icon(Icons.Default.Add, "Add", tint = Pal.Cyan) }
            }
        }
        items(tasks, key = { "t" + it.id }) { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = t.done, onCheckedChange = { v -> scope.launch { db.tasks().update(t.copy(done = v)) } })
                Text(
                    t.title, color = if (t.done) Pal.Silver.copy(alpha = 0.4f) else Pal.Soft, fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { scope.launch { db.tasks().delete(t) } }) {
                    Icon(Icons.Default.Close, "Delete", tint = Pal.Silver.copy(alpha = 0.6f))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AUTOMATION HISTORY", color = Pal.Cyan, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { db.history().clear() } }) { Text("Clear", fontSize = 11.sp) }
            }
        }
        if (history.isEmpty()) item { Text("Actions AURELIS performs will appear here.", color = Pal.Silver.copy(alpha = 0.6f), fontSize = 13.sp) }
        items(history, key = { "h" + it.id }) { h ->
            Text("${fmt(h.time, "d MMM HH:mm")}  ·  ${h.text}", color = Pal.Silver, fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}

// ---------------------------------------------------------------------- Settings & privacy

@Composable
fun SettingsScreen(requestPerms: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val visible by AppState.uiVisible.collectAsState()
    val speechDl by AppState.speechDownload.collectAsState()
    val modelDl by AppState.modelDownload.collectAsState()
    val heard by AppState.heard.collectAsState()
    val voices by Voice.voiceNames.collectAsState()
    var tick by remember { mutableIntStateOf(0) }

    var key by remember { mutableStateOf(SecureStore.get(ctx, "cloud_key")) }
    var model by remember { mutableStateOf(prefs.cloudModel) }
    var rate by remember { mutableFloatStateOf(prefs.speechRate) }
    var voiceName by remember { mutableStateOf(prefs.voiceName) }
    var voiceMenu by remember { mutableStateOf(false) }
    var wakeExtra by remember { mutableStateOf(prefs.wakeExtra) }
    var wanted by remember { mutableStateOf(prefs.listeningWanted) }
    var autoOpen by remember { mutableStateOf(prefs.autoOpen) }
    var googleSpeech by remember { mutableStateOf(prefs.googleSpeechOnline) }
    var saveChats by remember { mutableStateOf(prefs.saveConversations) }
    var useMemory by remember { mutableStateOf(prefs.useMemory) }
    var speakTyped by remember { mutableStateOf(prefs.speakTyped) }
    var confirmWipe by remember { mutableStateOf(false) }

    val speechInstalled = remember(tick, speechDl, visible) { Ears.isInstalled(ctx) }
    val installedModels = remember(tick, modelDl, visible) { LocalBrain.installed(ctx).map { it.id } }
    val activeModel = remember(tick, modelDl, visible) { LocalBrain.active(ctx)?.id }
    val ramGb = remember {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        Math.round(mi.totalMem / 1_000_000_000.0)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ScreenTitle("SETTINGS & PRIVACY", "Everything stays on your phone unless noted")

        Section("CLOUD INTELLIGENCE (ONLINE)") {
            Text("Free Gemini key from aistudio.google.com. Stored encrypted with Android Keystore.", color = Pal.Silver, fontSize = 12.sp)
            OutlinedTextField(
                value = key, onValueChange = { key = it }, label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                SecureStore.put(ctx, "cloud_key", key.trim())
                prefs.cloudModel = model
                model = prefs.cloudModel
                toast(ctx, "Saved")
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Save") }
        }

        Section("OFFLINE SPEECH PACK (\"HEY AURELIS\")") {
            Text(
                "Needed for the wake word and offline listening. English, about ${Ears.MODEL_MB} MB. Standby audio is processed only on this phone.",
                color = Pal.Silver, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            when {
                speechDl >= 0 -> {
                    LinearProgressIndicator(progress = { speechDl / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading… $speechDl%", color = Pal.Cyan, fontSize = 12.sp)
                }
                speechInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ Installed", color = Pal.Cyan, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        Control.stopListening(ctx)
                        wanted = false
                        Ears.uninstall(ctx)
                        tick++
                    }) { Text("Delete") }
                }
                else -> Button(onClick = {
                    Bg.scope.launch {
                        try {
                            Ears.install(ctx)
                            withContext(Dispatchers.Main) {
                                tick++
                                toast(ctx, "Speech pack ready. You can now turn on \"Hey AURELIS\".")
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { toast(ctx, "Download failed: ${e.message}") }
                        }
                    }
                }) { Text("Download speech pack") }
            }
        }

        Section("OFFLINE BRAIN (LOCAL AI)") {
            Text(
                "Runs a small AI model on your phone so AURELIS can chat without internet. Your phone has about $ramGb GB RAM. Download over Wi-Fi.",
                color = Pal.Silver, fontSize = 12.sp
            )
            LocalBrain.MODELS.forEach { m ->
                Spacer(Modifier.height(10.dp))
                Text(m.name, color = Pal.Soft, fontSize = 14.sp)
                Text("${m.sizeMb} MB · ${m.note}", color = Pal.Silver.copy(alpha = 0.7f), fontSize = 11.sp)
                val dl = modelDl
                when {
                    dl != null && dl.first == m.id -> {
                        LinearProgressIndicator(progress = { dl.second / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${dl.second}%", color = Pal.Cyan, fontSize = 12.sp)
                    }
                    m.id in installedModels -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeModel == m.id) {
                            Text("✓ Active", color = Pal.Cyan, modifier = Modifier.weight(1f))
                        } else {
                            TextButton(onClick = {
                                prefs.localModelId = m.id
                                LocalBrain.close()
                                tick++
                            }, modifier = Modifier.weight(1f)) { Text("Use this one") }
                        }
                        OutlinedButton(onClick = {
                            LocalBrain.uninstall(ctx, m)
                            tick++
                        }) { Text("Delete") }
                    }
                    else -> OutlinedButton(enabled = dl == null, onClick = {
                        Bg.scope.launch {
                            try {
                                LocalBrain.install(ctx, m)
                                withContext(Dispatchers.Main) {
                                    tick++
                                    toast(ctx, "${m.name} installed.")
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { toast(ctx, "Download failed: ${e.message}") }
                            }
                        }
                    }) { Text("Download") }
                }
            }
        }

        Section("VOICE") {
            Text("Speech speed: ${"%.1f".format(rate)}×", color = Pal.Soft, fontSize = 13.sp)
            Slider(
                value = rate, onValueChange = { rate = it }, valueRange = 0.6f..1.6f,
                onValueChangeFinished = {
                    prefs.speechRate = rate
                    Voice.applySettings(ctx)
                }
            )
            Box {
                OutlinedButton(onClick = { voiceMenu = true }) {
                    Text(if (voiceName.isBlank()) "Voice: default (British)" else "Voice: $voiceName", maxLines = 1)
                }
                DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                    DropdownMenuItem(text = { Text("Default (British)") }, onClick = {
                        voiceName = ""
                        prefs.voiceName = ""
                        Voice.applySettings(ctx)
                        voiceMenu = false
                    })
                    voices.forEach { v ->
                        DropdownMenuItem(text = { Text(v, fontSize = 12.sp) }, onClick = {
                            voiceName = v
                            prefs.voiceName = v
                            Voice.applySettings(ctx)
                            voiceMenu = false
                        })
                    }
                }
            }
            TextButton(onClick = { Voice.speak(AurelisService.GREETING) }) { Text("Test voice") }
            ToggleRow("Speak replies to typed messages", speakTyped) {
                speakTyped = it
                prefs.speakTyped = it
            }
        }

        Section("WAKE WORD & LISTENING") {
            ToggleRow("Continuous listening for \"Hey AURELIS\"", wanted) { on ->
                if (!on) {
                    Control.stopListening(ctx)
                    wanted = false
                } else if (!Control.hasMic(ctx)) {
                    requestPerms()
                } else if (!speechInstalled) {
                    toast(ctx, "Download the offline speech pack first.")
                } else {
                    Control.startStandby(ctx)
                    wanted = true
                }
            }
            ToggleRow("Open AURELIS automatically when activated", autoOpen) {
                autoOpen = it
                prefs.autoOpen = it
            }
            ToggleRow("Use Google speech for commands when online (more accurate)", googleSpeech) {
                googleSpeech = it
                prefs.googleSpeechOnline = it
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "If AURELIS misses the wake word, look at how it heard you below and add that phrase (one per line).",
                color = Pal.Silver, fontSize = 12.sp
            )
            if (heard.isNotBlank()) Text("Last heard: “$heard”", color = Pal.Cyan, fontSize = 12.sp)
            OutlinedTextField(
                value = wakeExtra, onValueChange = { wakeExtra = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Extra wake phrases") }, minLines = 2
            )
            TextButton(onClick = {
                prefs.wakeExtra = wakeExtra
                toast(ctx, "Saved. Restart listening to apply.")
            }) { Text("Save phrases") }
        }

        Section("PERMISSIONS") {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            PermRow("Microphone", remember(visible) { Control.hasMic(ctx) }) { requestPerms() }
            PermRow(
                "Notifications",
                remember(visible) { Build.VERSION.SDK_INT < 33 || nm.areNotificationsEnabled() }
            ) { requestPerms() }
            PermRow("Display over other apps (open on \"Hey AURELIS\")", remember(visible) { Settings.canDrawOverlays(ctx) }) {
                openSettings(ctx, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                PermRow("Full-screen alerts (locked screen)", remember(visible) { nm.canUseFullScreenIntent() }) {
                    openSettings(ctx, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, true)
                }
            }
            PermRow("Notification access (read notifications)", remember(visible) { NotificationReader.enabled(ctx) }) {
                openSettings(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, false)
            }
            PermRow("Unrestricted battery (keeps listening alive)", remember(visible) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }) {
                openSettings(ctx, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true)
            }
        }

        Section("PRIVACY & DATA") {
            ToggleRow("Save conversation history", saveChats) {
                saveChats = it
                prefs.saveConversations = it
            }
            ToggleRow("Use my memories when answering", useMemory) {
                useMemory = it
                prefs.useMemory = it
            }
            Text(
                "Online answers send your question (and relevant memories) to Google Gemini. Offline answers never leave the phone.",
                color = Pal.Silver, fontSize = 12.sp
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { AurelisApp.instance.db.chat().clear() }
                        AppState.messages.value = emptyList()
                        toast(ctx, "Conversation history cleared")
                    }
                }) { Text("Clear chats", fontSize = 12.sp) }
                OutlinedButton(onClick = { scope.launch { share(ctx, exportAll()) } }) { Text("Export data", fontSize = 12.sp) }
            }
            OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Delete all AURELIS data", color = Pal.Amber, fontSize = 12.sp)
            }
        }

        Section("ANDROID LIMITATIONS") {
            listOf(
                "Android may block apps from opening themselves from the background. If that happens, AURELIS shows a tap-to-open alert instead.",
                "On a locked screen, AURELIS can listen and answer by voice, but you must unlock to see the app.",
                "Android 14+ does not allow the microphone to start automatically after a restart. Tap the notification to resume.",
                "Some phone brands (Tecno, Infinix, Xiaomi, Samsung) close background apps. Set battery to Unrestricted above.",
                "While another app is using the microphone (calls, voice notes), the wake word pauses."
            ).forEach { Text("• $it", color = Pal.Silver, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp)) }
        }
        Text(
            "AURELIS · LISTEN • THINK • ASSIST • EVOLVE",
            color = Pal.Amber.copy(alpha = 0.7f), fontSize = 10.sp, letterSpacing = 2.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp)
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all data?") },
            text = { Text("Deletes memories, tasks, reminders, conversations, history and your saved API key. Downloaded models are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val db = AurelisApp.instance.db
                            db.reminders().pending().forEach { Reminders.cancel(ctx, it) }
                            db.clearAllTables()
                        }
                        SecureStore.clear(ctx)
                        key = ""
                        AppState.messages.value = emptyList()
                        toast(ctx, "All data deleted")
                    }
                }) { Text("Delete everything", color = Pal.Amber) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pal.Soft, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PermRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pal.Soft, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (granted) Text("✓", color = Pal.Cyan, fontSize = 16.sp)
        else TextButton(onClick = onGrant) { Text("Grant", color = Pal.Amber) }
    }
}
