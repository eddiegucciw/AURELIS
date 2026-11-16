package com.aurelis.app

import android.content.Context
import android.os.StatFs
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun say(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

private fun mb(bytes: Long) = if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000L} MB"

/** Settings sections added by the voice upgrade: live status, listening modes, Whisper, offline library. */
@Composable
fun VoiceUpgradeSections() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val core by AppState.core.collectAsState()
    val online by AppState.online.collectAsState()
    val speaking by Voice.speaking.collectAsState()
    val recognizer by EarState.recognizer.collectAsState()
    val whisperLoaded by EarState.whisperLoaded.collectAsState()
    val whisperDl by EarState.whisperDownload.collectAsState()
    val speechDl by AppState.speechDownload.collectAsState()
    val modelDl by AppState.modelDownload.collectAsState()
    val lastError by EarState.lastError.collectAsState()
    var tick by remember { mutableIntStateOf(0) }

    // Refresh the numbers every few seconds while Settings is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            tick++
        }
    }

    var continuous by remember { mutableStateOf(VoicePrefs.continuous(ctx)) }
    var bargeIn by remember { mutableStateOf(VoicePrefs.bargeIn(ctx)) }
    var useWhisper by remember { mutableStateOf(VoicePrefs.whisper(ctx)) }
    var pause by remember { mutableFloatStateOf(VoicePrefs.pause(ctx)) }

    val whisperInstalled = remember(tick, whisperDl) { SpeechModels.installed(ctx) }
    val whisperBytes = remember(tick, whisperDl) { SpeechModels.sizeBytes(ctx) }
    val vadOk = remember { VadKit.available(ctx) }
    val packs = remember(tick) { Knowledge.packs(ctx) }
    val libBytes = remember(tick) { Knowledge.sizeBytes(ctx) }
    val brain = remember(tick, online) { EngineStatus.brain(ctx) }
    val qwen = remember(tick, modelDl) {
        val m = LocalBrain.active(ctx)
        when {
            m == null -> "Not installed"
            LocalBrain.loaded() -> "${m.name} · loaded"
            else -> "${m.name} · installed (loads when needed)"
        }
    }
    val voskBytes = remember(tick, speechDl) { Ears.modelDir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    val qwenBytes = remember(tick, modelDl) { LocalBrain.installed(ctx).sumOf { LocalBrain.file(ctx, it).length() } }
    val free = remember(tick) {
        try {
            StatFs(ctx.filesDir.path).availableBytes
        } catch (e: Exception) {
            -1L
        }
    }

    Section("SYSTEM STATUS") {
        StatusLine("Internet", if (online) "Online" else "Offline")
        StatusLine("AI engine", brain + if (CloudGate.blocked()) " (cloud paused after a connection failure)" else "")
        StatusLine("Qwen (offline brain)", qwen)
        StatusLine("Speech for commands", EngineStatus.recognizer(ctx) + if (recognizer.isNotEmpty()) " · last used: $recognizer" else "")
        StatusLine("Wake word", if (Ears.isInstalled(ctx)) "Vosk installed" else "Vosk pack not installed")
        StatusLine("Listening", core.label)
        StatusLine("Speaking", if (speaking) "Yes" else "No")
        StatusLine("Mode", if (continuous) "Continuous conversation" else "Single command")
        StatusLine("Voice interrupt", if (!bargeIn) "Off (say \"Hey AURELIS\" to interrupt)" else if (vadOk) "On" else "Unavailable on this build")
        StatusLine(
            "Whisper pack",
            when {
                whisperDl >= 0 -> "Downloading $whisperDl%"
                Whisper.broken -> "Failed to start (using Vosk)"
                whisperInstalled -> "Installed · ${mb(whisperBytes)}" + if (whisperLoaded) " · loaded" else ""
                else -> "Not downloaded"
            }
        )
        StatusLine("Offline library", "${packs.size} packs · ${packs.sumOf { it.entries }} entries · ${mb(libBytes)}")
        StatusLine("Model storage", "Vosk ${mb(voskBytes)} · Whisper ${mb(whisperBytes)} · Qwen ${mb(qwenBytes)}")
        if (free >= 0) StatusLine("Free space", mb(free))
        if (lastError.isNotBlank()) Text("Last speech problem: $lastError", color = Pal.Amber, fontSize = 11.sp)
    }

    Section("CONVERSATION") {
        ToggleRow("Continuous conversation (off = single command, then standby)", continuous) {
            continuous = it
            VoicePrefs.setContinuous(ctx, it)
        }
        ToggleRow("Interrupt AURELIS by talking over it", bargeIn) {
            bargeIn = it
            VoicePrefs.setBargeIn(ctx, it)
        }
        Text(
            "You can also say \"single command mode\", \"continuous conversation mode\" or \"voice status\".",
            color = Pal.Silver, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Text("Pause that ends what you say: ${"%.1f".format(pause)} s", color = Pal.Soft, fontSize = 13.sp)
        Slider(
            value = pause, onValueChange = { pause = it }, valueRange = 0.6f..3f,
            onValueChangeFinished = { VoicePrefs.setPause(ctx, pause) }
        )
        Text("Longer gives you more time to think mid-sentence; shorter answers faster.", color = Pal.Silver, fontSize = 11.sp)
    }

    Section("OFFLINE COMMAND RECOGNITION (WHISPER)") {
        Text(
            "Whisper tiny.en understands offline commands and conversation better than the wake-word pack. " +
                "About ${SpeechModels.TOTAL_MB} MB, downloaded once into app storage (not part of the app). ${SpeechModels.LICENSE}.",
            color = Pal.Silver, fontSize = 12.sp
        )
        ToggleRow("Use Whisper for offline commands", useWhisper) {
            useWhisper = it
            VoicePrefs.setWhisper(ctx, it)
        }
        when {
            whisperDl >= 0 -> {
                LinearProgressIndicator(progress = { whisperDl / 100f }, modifier = Modifier.fillMaxWidth())
                Text("Downloading… $whisperDl%", color = Pal.Cyan, fontSize = 12.sp)
            }
            whisperInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ Installed", color = Pal.Cyan, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    SpeechModels.uninstall(ctx)
                    tick++
                }) { Text("Delete") }
            }
            else -> Button(onClick = {
                if (!AppState.online.value) say(ctx, "Connect to the internet (Wi-Fi is best) to download it.")
                else Bg.scope.launch {
                    try {
                        SpeechModels.install(ctx)
                        withContext(Dispatchers.Main) {
                            tick++
                            say(ctx, "Whisper is ready for offline commands.")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { say(ctx, "Download stopped: ${e.message}. Tap again to resume.") }
                    }
                }
            }) { Text("Download Whisper (${SpeechModels.TOTAL_MB} MB)") }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val msg = withContext(Dispatchers.IO) { Knowledge.import(ctx, uri) }
                tick++
                say(ctx, msg)
            }
        }
    }

    Section("OFFLINE LIBRARY") {
        Text(
            "Reference packs AURELIS can answer from without internet, always naming the source. " +
                "Kept separate from your personal memory. A pack must state its source and licence.",
            color = Pal.Silver, fontSize = 12.sp
        )
        packs.forEach { p ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(p.title, color = Pal.Soft, fontSize = 14.sp)
                    Text("${p.entries} entries · ${p.license} · ${p.source}", color = Pal.Silver.copy(alpha = 0.7f), fontSize = 11.sp)
                }
                if (!p.builtin) TextButton(onClick = {
                    Knowledge.remove(ctx, p.id)
                    tick++
                }) { Text("Remove") }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            try {
                picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            } catch (e: Exception) {
                say(ctx, "No file picker is available.")
            }
        }) { Text("Import a knowledge pack (.json)") }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Pal.Silver, fontSize = 12.sp, modifier = Modifier.weight(0.42f))
        Text(value, color = Pal.Soft, fontSize = 12.sp, modifier = Modifier.weight(0.58f))
    }
}
