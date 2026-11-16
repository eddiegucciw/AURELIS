package com.aurelis.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException

/*
 * The new offline "ears" of AURELIS.
 *
 *  - Vosk still listens for "Hey AURELIS" in standby (small, always-on, grammar-limited).
 *  - Commands and conversation offline are captured with Silero VAD (waits for you to finish,
 *    tolerates short pauses) and transcribed by Whisper tiny.en through sherpa-onnx.
 *  - Online, Google's recogniser is still used when the user prefers it.
 *  - While AURELIS speaks, a VAD-based barge-in detector with Android's echo canceller lets the
 *    user interrupt simply by talking.
 *
 * Only one microphone user runs at a time: Vosk standby, the command capture, or barge-in.
 */

/** Listening preferences (kept in the same settings file as everything else). */
object VoicePrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("aurelis", Context.MODE_PRIVATE)

    /** Continuous Conversation (true): keep listening for follow-ups. Single-Command (false): back to standby. */
    fun continuous(ctx: Context) = p(ctx).getBoolean("conversation_mode", true)
    fun setContinuous(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("conversation_mode", on).apply()

    /** Interrupt AURELIS by simply talking while it speaks. */
    fun bargeIn(ctx: Context) = p(ctx).getBoolean("barge_in", true)
    fun setBargeIn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("barge_in", on).apply()

    /** Use Whisper for offline commands when its pack is installed (otherwise Vosk). */
    fun whisper(ctx: Context) = p(ctx).getBoolean("use_whisper", true)
    fun setWhisper(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("use_whisper", on).apply()

    /** How long a pause (seconds) ends what you are saying. */
    fun pause(ctx: Context) = p(ctx).getFloat("pause_secs", 1.2f).coerceIn(0.6f, 3f)
    fun setPause(ctx: Context, s: Float) = p(ctx).edit().putFloat("pause_secs", s.coerceIn(0.6f, 3f)).apply()
}

/** Live status of the speech side, shown in Settings → System status. */
object EarState {
    /** Recogniser in use for the current/last command: "Google", "Whisper", "Vosk" or "". */
    val recognizer = MutableStateFlow("")
    val whisperLoaded = MutableStateFlow(false)
    val whisperDownload = MutableStateFlow(-1) // -1 idle, 0..100
    val lastError = MutableStateFlow("")
}

/** Downloads and checks the Whisper tiny.en pack (int8, about 104 MB). Never inside the APK. */
object SpeechModels {
    private const val DIR = "whisper-tiny-en"
    private const val HF = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main/"
    const val ENCODER = "tiny.en-encoder.int8.onnx"
    const val DECODER = "tiny.en-decoder.int8.onnx"
    const val TOKENS = "tiny.en-tokens.txt"
    const val TOTAL_MB = 104
    const val LICENSE = "Whisper (OpenAI) MIT · sherpa-onnx export Apache-2.0"

    private class Part(val name: String, val minBytes: Long, val approxBytes: Long)

    // Minimum sizes catch truncated or error-page downloads.
    private val PARTS = listOf(
        Part(ENCODER, 10_000_000L, 12_900_000L),
        Part(DECODER, 80_000_000L, 89_900_000L),
        Part(TOKENS, 500_000L, 836_000L)
    )

    fun dir(ctx: Context) = File(ctx.filesDir, DIR)
    fun file(ctx: Context, name: String) = File(dir(ctx), name)

    private fun ok(ctx: Context, p: Part) = file(ctx, p.name).let { it.isFile && it.length() >= p.minBytes }

    fun installed(ctx: Context) = PARTS.all { ok(ctx, it) }

    fun sizeBytes(ctx: Context): Long =
        dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Blocking. Resumes interrupted downloads; verifies every file. Throws IOException on failure. */
    @Synchronized
    fun install(ctx: Context) {
        val total = PARTS.sumOf { it.approxBytes }.toDouble()
        var before = 0L
        EarState.whisperDownload.value = 0
        try {
            for (p in PARTS) {
                if (!ok(ctx, p)) {
                    val f = file(ctx, p.name)
                    f.delete()
                    Downloader.download(HF + p.name, f) { pct ->
                        EarState.whisperDownload.value =
                            ((before + p.approxBytes * pct / 100.0) * 100 / total).toInt().coerceIn(0, 99)
                    }
                    if (!ok(ctx, p)) {
                        f.delete()
                        throw IOException("The file ${p.name} arrived incomplete. Please try again.")
                    }
                }
                before += p.approxBytes
            }
        } finally {
            EarState.whisperDownload.value = -1
        }
    }

    fun uninstall(ctx: Context) {
        Whisper.release()
        dir(ctx).deleteRecursively()
    }
}

/** Silero voice-activity model, built into the app (tiny). */
object VadKit {
    const val ASSET = "silero_vad.onnx"

    fun available(ctx: Context): Boolean = try {
        ctx.assets.open(ASSET).close()
        true
    } catch (e: Exception) {
        false
    }

    /** A fresh detector, or null when the model or native library is unavailable. */
    fun create(ctx: Context, threshold: Float, minSilence: Float, minSpeech: Float, maxSpeech: Float): Vad? {
        if (!available(ctx)) return null
        return try {
            Vad(
                assetManager = ctx.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = ASSET,
                        threshold = threshold,
                        minSilenceDuration = minSilence,
                        minSpeechDuration = minSpeech,
                        windowSize = 512,
                        maxSpeechDuration = maxSpeech
                    ),
                    sampleRate = 16000,
                    numThreads = 1,
                    provider = "cpu"
                )
            )
        } catch (e: Throwable) {
            EarState.lastError.value = "Voice detector unavailable: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }
}

/** Whisper tiny.en through sherpa-onnx. Loaded on first use and released when idle. */
object Whisper {
    private var rec: OfflineRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private const val IDLE_MS = 3 * 60_000L
    private val idle = Runnable { Thread { release() }.start() }

    /** Set when loading failed this session, so we don't retry on every command. */
    @Volatile var broken = false
        private set

    fun ready(ctx: Context) = !broken && VoicePrefs.whisper(ctx) && SpeechModels.installed(ctx)

    @Synchronized
    fun load(ctx: Context): Boolean {
        if (rec != null) return true
        if (!SpeechModels.installed(ctx)) return false
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = SpeechModels.file(ctx, SpeechModels.ENCODER).absolutePath,
                        decoder = SpeechModels.file(ctx, SpeechModels.DECODER).absolutePath,
                        language = "en",
                        task = "transcribe"
                    ),
                    tokens = SpeechModels.file(ctx, SpeechModels.TOKENS).absolutePath,
                    modelType = "whisper",
                    numThreads = 2,
                    provider = "cpu"
                )
            )
            rec = OfflineRecognizer(config = config)
            EarState.whisperLoaded.value = true
            true
        } catch (e: Throwable) {
            broken = true
            rec = null
            EarState.lastError.value = "Whisper couldn't start: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /**
     * Blocking. Returns the text (possibly empty when nothing was said), or null when
     * transcription itself failed.
     */
    fun transcribe(ctx: Context, samples: FloatArray): String? {
        if (!load(ctx)) return null
        main.removeCallbacks(idle)
        val out = synchronized(this) {
            val r = rec ?: return null
            try {
                val s = r.createStream()
                try {
                    s.acceptWaveform(samples, 16000)
                    r.decode(s)
                    r.getResult(s).text
                } finally {
                    s.release()
                }
            } catch (e: Throwable) {
                EarState.lastError.value = "Whisper failed: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }
        main.postDelayed(idle, IDLE_MS)
        return out?.let { clean(it) }
    }

    @Synchronized
    fun release() {
        main.removeCallbacks(idle)
        try {
            rec?.release()
        } catch (e: Throwable) {
        }
        rec = null
        EarState.whisperLoaded.value = false
    }

    // Whisper "hears" these in silence or noise. They are never treated as a command.
    // (Real words like "okay", "thank you" or "bye" are kept: the voice detector already confirmed speech.)
    private val HALLUCINATIONS = setOf(
        "", "you", "thanks for watching", "thank you for watching", "the end", "music", "applause",
        "silence", "blank audio", "um", "uh", "hmm"
    )

    /** Removes "[BLANK_AUDIO]", "(music)" and similar, and known silence hallucinations. */
    fun clean(raw: String): String {
        val t = raw.replace(Regex("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val key = t.lowercase().replace(Regex("[^a-z ]"), "").trim()
        return if (key in HALLUCINATIONS) "" else t
    }
}

/**
 * Captures one spoken command or message: waits for speech to start, keeps recording through
 * short pauses, and stops after a real pause (or at the maximum length).
 */
object Capture {
    private const val RATE = 16000
    private const val WINDOW = 512
    private const val MAX_SECONDS = 25
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var runId = 0
    private var thread: Thread? = null

    /**
     * [preRoll] is audio already heard (e.g. the start of a barge-in) that belongs to this utterance.
     * [onDone] gets the samples, or null if nobody spoke before [waitMs]. Callbacks run on the main thread.
     */
    @SuppressLint("MissingPermission")
    fun start(
        ctx: Context,
        preRoll: FloatArray?,
        waitMs: Long,
        onSpeech: () -> Unit,
        onDone: (FloatArray?) -> Unit,
        onFail: (String) -> Unit
    ): Boolean {
        stop()
        val id = ++runId
        val pause = VoicePrefs.pause(ctx)
        val vad = VadKit.create(ctx, threshold = 0.5f, minSilence = pause, minSpeech = 0.2f, maxSpeech = MAX_SECONDS.toFloat())
        val audio = openMic(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (audio == null) {
            vad?.release()
            onFail("The microphone is busy or not allowed")
            return false
        }
        val t = Thread {
            val buf = ShortArray(WINDOW)
            val chunk = FloatArray(WINDOW)
            val all = FloatArrayBuilder() // everything since speech began (energy fallback)
            val segments = FloatArrayBuilder()
            var started = false
            var startedAt = 0L
            var lastSegmentAt = 0L
            var lastTalkAt = 0L
            var quietMs = 0L
            var noise = 0.004
            val began = System.currentTimeMillis()
            var result: FloatArray? = null
            try {
                audio.startRecording()
                if (preRoll != null && preRoll.isNotEmpty()) {
                    started = true
                    startedAt = System.currentTimeMillis()
                    all.add(preRoll, preRoll.size)
                    vad?.acceptWaveform(preRoll)
                    main.post { if (runId == id) onSpeech() }
                }
                while (runId == id) {
                    val got = audio.read(buf, 0, WINDOW)
                    if (got < 0) throw IOException("Microphone stopped")
                    if (got == 0) continue
                    var sum = 0.0
                    for (k in 0 until got) {
                        val f = buf[k] / 32768f
                        chunk[k] = f
                        sum += f * f
                    }
                    val rms = Math.sqrt(sum / got)
                    Levels.mic.value = (rms / 0.08).toFloat().coerceIn(0f, 1f)
                    val now = System.currentTimeMillis()
                    val piece = if (got == WINDOW) chunk else chunk.copyOf(got)

                    if (vad != null) {
                        vad.acceptWaveform(piece)
                        val talking = vad.isSpeechDetected()
                        if (talking) lastTalkAt = now
                        if (talking && !started) {
                            started = true
                            startedAt = now
                            main.post { if (runId == id) onSpeech() }
                        }
                        while (!vad.empty()) {
                            val seg = vad.front().samples
                            vad.pop()
                            segments.add(seg, seg.size)
                            lastSegmentAt = now
                        }
                        // A finished segment and no new speech for a short grace period: done.
                        if (segments.size > 0 && !talking && now - lastSegmentAt > 350) {
                            result = segments.toArray()
                            break
                        }
                        // A blip that never became real speech: keep waiting as if nothing was said.
                        if (started && segments.size == 0 && !talking && preRoll == null && now - lastTalkAt > 2500) {
                            started = false
                        }
                    } else {
                        // Energy fallback (no voice model): adaptive noise floor.
                        val speech = rms > maxOf(noise * 3.0, 0.012)
                        if (!speech) noise = noise * 0.95 + rms * 0.05
                        if (speech && !started) {
                            started = true
                            startedAt = now
                            main.post { if (runId == id) onSpeech() }
                        }
                        if (started) {
                            all.add(piece, piece.size)
                            quietMs = if (speech) 0L else quietMs + got * 1000L / RATE
                            if (quietMs >= (pause * 1000).toLong()) {
                                result = all.toArray()
                                break
                            }
                        }
                    }
                    if (!started && now - began > waitMs) break // nobody spoke
                    if (started && now - startedAt > MAX_SECONDS * 1000L) {
                        vad?.flush()
                        while (vad != null && !vad.empty()) {
                            val seg = vad.front().samples
                            vad.pop()
                            segments.add(seg, seg.size)
                        }
                        result = if (vad != null) segments.toArray() else all.toArray()
                        break
                    }
                }
                if (runId == id) {
                    val r = result?.takeIf { it.size > RATE / 5 } // ignore blips under 0.2 s
                    main.post { if (runId == id) onDone(r) }
                }
            } catch (e: Throwable) {
                main.post { if (runId == id) onFail(e.message ?: "Microphone error") }
            } finally {
                Levels.mic.value = 0f
                try {
                    audio.stop()
                } catch (e: Throwable) {
                }
                audio.release()
                vad?.release()
            }
        }
        thread = t
        t.start()
        return true
    }

    fun stop() {
        runId++
        val t = thread ?: return
        thread = null
        try {
            t.join(500)
        } catch (e: InterruptedException) {
        }
    }

    @SuppressLint("MissingPermission")
    internal fun openMic(source: Int): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val a = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        if (a.state == AudioRecord.STATE_INITIALIZED) a else {
            a.release()
            null
        }
    } catch (e: Throwable) {
        null
    }
}

/**
 * Lets the user interrupt AURELIS by talking over it. Uses the voice-call microphone path with
 * Android's echo canceller and noise suppressor, then requires sustained, confident speech that is
 * clearly louder than the leftover echo of AURELIS's own voice.
 */
object BargeIn {
    private const val RATE = 16000
    private const val WINDOW = 512 // 32 ms
    private const val NEED_WINDOWS = 9 // ~0.3 s of sustained speech
    private const val SETTLE_MS = 600L // ignore the very start of playback
    private const val PRE_ROLL = RATE // keep the last second for the transcript
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var runId = 0
    private var thread: Thread? = null

    fun available(ctx: Context) = VoicePrefs.bargeIn(ctx) && VadKit.available(ctx)

    /** [onInterrupt] runs on the main thread with the speech heard so far. */
    fun start(ctx: Context, onInterrupt: (FloatArray) -> Unit): Boolean {
        stop()
        if (!available(ctx)) return false
        val vad = VadKit.create(ctx, threshold = 0.6f, minSilence = 0.3f, minSpeech = 0.25f, maxSpeech = 20f) ?: return false
        val audio = Capture.openMic(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            ?: Capture.openMic(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        if (audio == null) {
            vad.release()
            return false
        }
        val session = audio.audioSessionId
        val aec = try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.apply { enabled = true } else null
        } catch (e: Throwable) {
            null
        }
        val ns = try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.apply { enabled = true } else null
        } catch (e: Throwable) {
            null
        }
        val id = ++runId
        val t = Thread {
            val buf = ShortArray(WINDOW)
            val chunk = FloatArray(WINDOW)
            val ring = FloatArray(PRE_ROLL)
            var ringPos = 0
            var ringFull = false
            var echo = 0.0 // typical loudness of AURELIS's own voice reaching the mic
            var echoN = 0
            var run = 0
            val began = System.currentTimeMillis()
            try {
                audio.startRecording()
                while (runId == id) {
                    val got = audio.read(buf, 0, WINDOW)
                    if (got < 0) break
                    if (got < WINDOW) continue
                    var sum = 0.0
                    for (k in 0 until WINDOW) {
                        val f = buf[k] / 32768f
                        chunk[k] = f
                        sum += f * f
                        ring[ringPos] = f
                        ringPos = (ringPos + 1) % PRE_ROLL
                        if (ringPos == 0) ringFull = true
                    }
                    val rms = Math.sqrt(sum / WINDOW)
                    val prob = vad.compute(chunk)
                    val age = System.currentTimeMillis() - began
                    if (age < SETTLE_MS) {
                        echo = maxOf(echo, rms)
                        echoN++
                        continue
                    }
                    // Learn the echo level slowly from non-speech windows.
                    if (prob < 0.3f) echo = echo * 0.97 + rms * 0.03
                    val loudEnough = rms > maxOf(echo * 2.2, 0.015)
                    run = if (prob > 0.7f && loudEnough && Voice.speaking.value) run + 1 else 0
                    if (run >= NEED_WINDOWS) {
                        val heard = if (ringFull) {
                            FloatArray(PRE_ROLL) { ring[(ringPos + it) % PRE_ROLL] }
                        } else {
                            ring.copyOf(ringPos)
                        }
                        // Keep only the recent speech (about the last 0.6 s) so AURELIS's own words are left out.
                        val keep = minOf(heard.size, RATE * 6 / 10)
                        val tail = heard.copyOfRange(heard.size - keep, heard.size)
                        main.post { if (runId == id) onInterrupt(tail) }
                        break
                    }
                }
            } catch (e: Throwable) {
                EarState.lastError.value = "Voice interrupt stopped: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                try {
                    audio.stop()
                } catch (e: Throwable) {
                }
                audio.release()
                try {
                    aec?.release()
                    ns?.release()
                } catch (e: Throwable) {
                }
                vad.release()
            }
        }
        thread = t
        t.start()
        return true
    }

    fun stop() {
        runId++
        val t = thread ?: return
        thread = null
        try {
            t.join(400)
        } catch (e: InterruptedException) {
        }
    }
}

/** A growable float buffer. */
class FloatArrayBuilder {
    private var data = FloatArray(16000)
    var size = 0
        private set

    fun add(src: FloatArray, n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        System.arraycopy(src, 0, data, size, n)
        size += n
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * When the cloud brain is unreachable, stop trying for a while instead of making the user wait
 * on a timeout for every request.
 */
object CloudGate {
    @Volatile private var blockedUntil = 0L

    fun blocked() = System.currentTimeMillis() < blockedUntil

    fun failed() {
        blockedUntil = System.currentTimeMillis() + 90_000L
    }

    fun reset() {
        blockedUntil = 0L
    }
}

/** Voice commands for listening modes, the voice interrupt, speech status and the offline library. */
object VoiceModes {
    fun handle(ctx: Context, text: String): Reply? {
        val n = text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

        if (Regex("^(switch to |use |turn on |enable |go to )?(single|one)[ -]?(command|shot|question) mode( on)?$|^(stop|don't keep|do not keep) (listening|asking) (after|between) (each|every) (answer|command|reply)$").matches(n)) {
            VoicePrefs.setContinuous(ctx, false)
            return Reply("Single-command mode is on. After each answer I'll go back to standby.", end = true)
        }
        if (Regex("^(switch to |use |turn on |enable |go to )?(continuous |conversation |continuous conversation )(mode)( on)?$|^keep (listening|the conversation going)( after (each|every) (answer|reply))?$").matches(n)) {
            VoicePrefs.setContinuous(ctx, true)
            return Reply("Continuous conversation is on. I'll keep listening after each answer until you're done.")
        }
        if (Regex("^(which|what) (listening |conversation )?mode (are you in|is on|am i in)$|^(listening|conversation) mode$").matches(n)) {
            return Reply(
                if (VoicePrefs.continuous(ctx)) "Continuous conversation mode: I keep listening after each answer."
                else "Single-command mode: I answer once, then go back to standby."
            )
        }
        if (Regex("^(turn off|disable|stop) (voice interrupt(ion)?s?|barge in|interrupting by voice|talk over)$").matches(n)) {
            VoicePrefs.setBargeIn(ctx, false)
            return Reply("Done. Talking over me won't interrupt me now; say \"Hey AURELIS\" to interrupt instead.")
        }
        if (Regex("^(turn on|enable|allow) (voice interrupt(ion)?s?|barge in|interrupting by voice|talk over)$").matches(n)) {
            VoicePrefs.setBargeIn(ctx, true)
            return Reply("Done. You can interrupt me just by talking while I speak.")
        }
        if (Regex("^(voice|speech|listening|engine|engines) status$|^which (speech )?(recogni[sz]er|engine) are you using$|^(what|which) (ai |brain )?engine are you using$").matches(n)) {
            return Reply(EngineStatus.spoken(ctx))
        }
        if (Regex("^(what'?s|what is) in (your|the|my) (offline )?(library|knowledge( library)?)$|^(offline )?library status$").matches(n)) {
            return Reply(Knowledge.summary(ctx))
        }
        Regex("^(?:search|look in|check) (?:your|the|my) (?:offline )?(?:library|knowledge(?: library)?) (?:for|about) (.+)$|^look up (.+) offline$")
            .find(n)?.let { m ->
                val q = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return null
                return Reply(Knowledge.answer(ctx, q))
            }
        return null
    }
}

/** One place that knows what every engine is doing (for the status screen and "voice status"). */
object EngineStatus {
    fun recognizer(ctx: Context): String = when {
        AppState.online.value && Prefs(ctx).googleSpeechOnline -> "Google (online)"
        Whisper.ready(ctx) -> "Whisper tiny.en (offline)"
        Ears.isInstalled(ctx) -> "Vosk (offline)"
        else -> "Google (offline, if the phone has its language pack)"
    }

    fun brain(ctx: Context): String = when (Assistant.mode(ctx)) {
        BrainMode.ONLINE -> "Gemini (online)"
        BrainMode.OFFLINE -> "Qwen on this phone (offline)"
        BrainMode.LIMITED -> "Built-in commands only"
    }

    fun spoken(ctx: Context): String {
        val wake = if (Ears.isInstalled(ctx)) "Vosk listens for the wake word." else "The wake-word pack isn't installed."
        val whisper = when {
            Whisper.broken -> "Whisper failed to start, so I'm using Vosk offline."
            SpeechModels.installed(ctx) -> "Whisper is installed for offline commands."
            else -> "Whisper isn't downloaded; offline commands use Vosk."
        }
        val mode = if (VoicePrefs.continuous(ctx)) "continuous conversation" else "single-command"
        return "Commands are heard by ${recognizer(ctx)}. $wake $whisper My brain right now is ${brain(ctx)}. I'm in $mode mode."
    }
}
