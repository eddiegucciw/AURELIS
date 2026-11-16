package com.aurelis.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The always-ready listener.
 * STANDBY: offline Vosk listens locally for "Hey AURELIS" (audio never leaves the phone).
 * ACTIVATION: brings AURELIS forward, greets, then listens for the instruction.
 * CONVERSATION: answers and keeps listening for follow-ups until silence or "goodbye".
 */
class AurelisService : Service() {

    companion object {
        const val ACTION_START = "com.aurelis.app.START"
        const val ACTION_TALK = "com.aurelis.app.TALK"
        const val ACTION_STOP = "com.aurelis.app.STOP"
        const val ACTION_ANNOUNCE = "com.aurelis.app.ANNOUNCE"
        const val ACTION_HUSH = "com.aurelis.app.HUSH"
        const val GREETING = "AURELIS online. How may I assist you?"
        private const val NOTIF_ID = 11
        private val WAKE_PREFIX = Regex(
            "^\\s*(?:hey|hi|okay|ok|hello)\\s+(?:aur\\w*|oralis|arealis|ralis|(?:or|are|our)\\s+(?:ellis|alice)|a\\s+really's|really\\s+is)\\s+"
        )

        @Volatile
        var running = false
            private set
    }

    private enum class Phase { IDLE, STANDBY, GREETING, LISTENING, THINKING, SPEAKING }

    private var phase = Phase.IDLE
    private var wakeEnabled = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var google: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val silence = Runnable { if (phase == Phase.LISTENING) endConversation() }
    private lateinit var prefs: Prefs

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Voice.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START // null = restarted by Android

        if (action == ACTION_STOP) {
            prefs.listeningWanted = false
            shutdown()
            return START_NOT_STICKY
        }

        if (!running) {
            if (!goForeground()) {
                Reminders.notify(this, 4001, "AURELIS is paused", "Tap to resume listening for \"Hey AURELIS\".")
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
            AppState.serviceRunning.value = true
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AURELIS:listening").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        when (action) {
            ACTION_START -> {
                prefs.listeningWanted = true
                wakeEnabled = true
                loadEars { if (phase == Phase.IDLE || phase == Phase.STANDBY) rest() }
            }
            ACTION_TALK -> {
                wakeEnabled = prefs.listeningWanted
                loadEars {}
                activate(fromWake = false)
            }
            ACTION_ANNOUNCE -> {
                val text = intent?.getStringExtra("text") ?: ""
                if (text.isNotBlank() && (phase == Phase.STANDBY || phase == Phase.IDLE)) {
                    Ears.stop()
                    phase = Phase.SPEAKING
                    AppState.core.value = CoreState.SPEAKING
                    AppState.add("assistant", text)
                    Voice.speak(text) { rest() }
                } else if (phase == Phase.IDLE) {
                    rest()
                }
            }
            ACTION_HUSH -> {
                Voice.stop()
                stopInterruptListener()
                if (phase == Phase.SPEAKING || phase == Phase.GREETING) rest()
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ foreground + notification

    private fun goForeground(): Boolean = try {
        val n = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val talk = PendingIntent.getService(
            this, 1, Intent(this, AurelisService::class.java).setAction(ACTION_TALK), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, AurelisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, AurelisApp.CH_STANDBY)
            .setSmallIcon(R.drawable.ic_stat_core)
            .setContentTitle("AURELIS")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null as Icon?, "Stop listening", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        if (!running) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text.take(120)))
        } catch (e: SecurityException) {
        }
    }

    // ------------------------------------------------------------------ state machine

    private fun loadEars(then: () -> Unit) {
        if (Ears.isLoaded()) {
            then()
            return
        }
        if (!Ears.isInstalled(this)) {
            if (wakeEnabled) AppState.status.value = "Download the offline speech pack in Settings to enable \"Hey AURELIS\"."
            then()
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Ears.load(this@AurelisService) }
            if (!ok) AppState.status.value = "The offline speech pack couldn't be loaded. Try re-downloading it in Settings."
            then()
        }
    }

    /** Nothing to do: wait for the wake word, or shut down if continuous listening is off. */
    private fun rest() {
        handler.removeCallbacks(silence)
        stopInterruptListener()
        google?.cancel()
        AppState.heard.value = ""
        if (wakeEnabled && Ears.isLoaded()) {
            enterStandby()
        } else {
            phase = Phase.IDLE
            AppState.core.value = CoreState.IDLE
            AppState.wakeActive.value = false
            if (!wakeEnabled || !Ears.isInstalled(this)) shutdown()
        }
    }

    private fun enterStandby() {
        phase = Phase.STANDBY
        AppState.core.value = CoreState.STANDBY
        AppState.wakeActive.value = true
        AppState.status.value = ""
        // Open listening: answer whatever is said. Otherwise wait for "Hey AURELIS", "hello" or a double clap.
        val open = Opt.openListening(this)
        updateNotification(if (open) "Listening · just speak to me" else "Standby · say \"Hey AURELIS\" or clap twice")
        val variants = WakeWord.variants(prefs)
        val onClap: (() -> Unit)? = if (Opt.clap(this)) ({ if (phase == Phase.STANDBY) onWake() }) else null
        val started = Ears.start(
            onPartial = { t ->
                AppState.heard.value = t
                if (!open && phase == Phase.STANDBY && WakeWord.detect(t, variants)) onWake()
            },
            onFinal = { t ->
                if (t.isNotEmpty()) AppState.heard.value = t
                if (phase == Phase.STANDBY) {
                    val words = WakeWord.norm(t).split(" ").count { it.isNotEmpty() }
                    if (WakeWord.detect(t, variants) && (!open || words <= 3)) {
                        onWake()
                    } else if (open && Speech.meaningful(t)) {
                        Ears.stop()
                        AppState.wakeActive.value = false
                        // Drop a leading "Hey AURELIS" so the command itself is what gets understood.
                        process(
                            if (WakeWord.detect(t, variants)) t.replaceFirst(WAKE_PREFIX, "") else t
                        )
                    }
                }
            },
            onFail = { msg ->
                AppState.status.value = "Microphone: $msg"
                Ears.stop()
                handler.postDelayed({ if (phase == Phase.STANDBY) enterStandby() }, 3000)
            },
            onClap = onClap,
            grammar = if (open) null else WakeWord.grammar(prefs)
        )
        if (!started) {
            AppState.core.value = CoreState.ERROR
            handler.postDelayed({ if (phase == Phase.STANDBY) enterStandby() }, 5000)
        }
    }

    private fun onWake() {
        Ears.stop()
        activate(fromWake = true)
    }

    private fun activate(fromWake: Boolean) {
        handler.removeCallbacks(silence)
        Ears.stop()
        google?.cancel()
        Voice.stop()
        phase = Phase.GREETING
        AppState.core.value = CoreState.WAKE
        AppState.wakeActive.value = false
        updateNotification("Activated")
        if (fromWake) bringToFront()
        handler.postDelayed({
            if (phase != Phase.GREETING) return@postDelayed
            AppState.core.value = CoreState.SPEAKING
            Voice.speak(if (fromWake) GREETING else "I'm listening.") {
                if (phase == Phase.GREETING) listenForCommand()
            }
        }, 450)
    }

    private fun listenForCommand() {
        phase = Phase.LISTENING
        AppState.core.value = CoreState.LISTENING
        AppState.heard.value = ""
        updateNotification("Listening…")
        val googleOk = SpeechRecognizer.isRecognitionAvailable(this)
        when {
            AppState.online.value && prefs.googleSpeechOnline && googleOk -> listenGoogle()
            Ears.isLoaded() -> listenOffline()
            googleOk -> listenGoogle()
            else -> {
                phase = Phase.SPEAKING
                Voice.speak("I can't listen yet. Please download the offline speech pack in Settings.") { rest() }
            }
        }
    }

    private fun listenOffline() {
        handler.removeCallbacks(silence)
        handler.postDelayed(silence, 9000)
        val ok = Ears.start(
            onPartial = { t ->
                if (phase != Phase.LISTENING) return@start
                AppState.heard.value = t
                handler.removeCallbacks(silence)
                handler.postDelayed(silence, 6000)
            },
            onFinal = { t ->
                if (phase != Phase.LISTENING || t.isBlank()) return@start
                Ears.stop()
                process(t)
            },
            onFail = {
                Ears.stop()
                if (phase == Phase.LISTENING) endConversation()
            }
        )
        if (!ok) endConversation()
    }

    private fun listenGoogle() {
        handler.removeCallbacks(silence)
        val rec = google ?: SpeechRecognizer.createSpeechRecognizer(this).also { google = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank()) AppState.heard.value = p
            }

            override fun onResults(results: Bundle?) {
                if (phase != Phase.LISTENING) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (t.isNullOrBlank()) endConversation() else process(t)
            }

            override fun onError(error: Int) {
                if (phase != Phase.LISTENING) return
                when (error) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        if (Ears.isLoaded()) listenOffline() else endConversation()
                    else -> endConversation()
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            if (Ears.isLoaded()) listenOffline() else endConversation()
        }
    }

    private fun process(text: String) {
        handler.removeCallbacks(silence)
        phase = Phase.THINKING
        AppState.core.value = CoreState.THINKING
        AppState.heard.value = text
        updateNotification("Thinking…")
        scope.launch {
            val reply = Assistant.respond(this@AurelisService, text)
            if (reply.stopListening) {
                wakeEnabled = false
                prefs.listeningWanted = false
            }
            speakReply(reply)
        }
    }

    private fun speakReply(reply: Reply) {
        phase = Phase.SPEAKING
        AppState.core.value = CoreState.SPEAKING
        updateNotification(reply.text)
        if (!reply.leftApp) startInterruptListener()
        Voice.speak(reply.text) {
            stopInterruptListener()
            if (phase != Phase.SPEAKING) return@speak
            if (reply.end) rest() else listenForCommand()
        }
    }

    /** While speaking, "Hey AURELIS" interrupts and AURELIS listens again. */
    private fun startInterruptListener() {
        if (!Ears.isLoaded()) return
        val variants = WakeWord.variants(prefs)
        Ears.start(
            onPartial = { t ->
                if (phase == Phase.SPEAKING && WakeWord.detect(t, variants)) {
                    Voice.stop()
                    Ears.stop()
                    listenForCommand()
                }
            },
            onFinal = {},
            onFail = { Ears.stop() },
            grammar = WakeWord.grammar(prefs)
        )
    }

    private fun stopInterruptListener() {
        if (phase != Phase.STANDBY && phase != Phase.LISTENING) Ears.stop()
    }

    private fun endConversation() {
        handler.removeCallbacks(silence)
        Ears.stop()
        rest()
    }

    /** Bring the AURELIS screen forward when Android allows it; otherwise show a tap-to-open alert. */
    private fun bringToFront() {
        if (!prefs.autoOpen || AppState.uiVisible.value) return
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("from_wake", true)
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // fall back to the notification below
            }
        }
        val pi = PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, AurelisApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_core)
            .setContentTitle("AURELIS activated")
            .setContentText("I'm listening. Tap to open AURELIS.")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(4002, n)
        } catch (e: SecurityException) {
        }
    }

    private fun shutdown() {
        val wasRunning = running
        running = false
        phase = Phase.IDLE
        AppState.serviceRunning.value = false
        AppState.wakeActive.value = false
        AppState.core.value = CoreState.IDLE
        handler.removeCallbacksAndMessages(null)
        Ears.stop()
        google?.destroy()
        google = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (wasRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (running) shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
