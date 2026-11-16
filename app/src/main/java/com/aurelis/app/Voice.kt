package com.aurelis.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/** Offline text-to-speech using the phone's installed voices. */
object Voice {
    private var tts: TextToSpeech? = null
    @Volatile var ready = false
        private set
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    val voiceNames = MutableStateFlow<List<String>>(emptyList())
    val speaking = MutableStateFlow(false)

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking.value = true
                    }

                    override fun onDone(utteranceId: String?) = finish(utteranceId)

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finish(utteranceId)

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        speaking.value = false
                        if (utteranceId != null) callbacks.remove(utteranceId)
                    }
                })
                ready = true
                applySettings(ctx)
            }
        }
    }

    private fun finish(id: String?) {
        speaking.value = false
        val cb = if (id != null) callbacks.remove(id) else null
        if (cb != null) main.post { cb() }
    }

    fun applySettings(ctx: Context) {
        val engine = tts ?: return
        if (!ready) return
        val prefs = Prefs(ctx)
        try {
            val all = engine.voices?.filter { it.locale.language == "en" }?.sortedBy { it.name } ?: emptyList()
            voiceNames.value = all.map { it.name }
            val chosen = all.firstOrNull { it.name == prefs.voiceName }
            if (chosen != null) {
                engine.voice = chosen
            } else {
                val r = engine.setLanguage(Locale.UK)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.US)
                }
            }
        } catch (e: Exception) {
            engine.setLanguage(Locale.getDefault())
        }
        engine.setSpeechRate(prefs.speechRate)
        engine.setPitch(1.0f)
    }

    /** Speaks [text]; [onDone] runs on the main thread when finished (not if interrupted). */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val engine = tts
        val clean = text.replace(Regex("[*#_`>]"), "").trim()
        if (engine == null || !ready || clean.isEmpty()) {
            if (onDone != null) main.postDelayed({ onDone() }, 200)
            return
        }
        val id = UUID.randomUUID().toString()
        if (onDone != null) callbacks[id] = onDone
        speaking.value = true
        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        callbacks.clear()
        tts?.stop()
        speaking.value = false
    }
}

/** Matches "Hey AURELIS" (and the ways a speech engine tends to hear it). */
object WakeWord {
    val DEFAULTS = listOf(
        "hey aurelis", "hey aurelius", "hey aurelia", "hey or ellis", "hey are ellis",
        "hey our ellis", "hey oralis", "hey a really's", "hey really is", "hey arealis",
        "hey aurelio", "hey or alice", "hey aurelis", "okay aurelius", "hi aurelius", "hey ralis"
    )
    private val HELLO = Regex("\\bhello\\b")

    /** Words the standby recogniser is limited to, which makes wake-word detection far more reliable. */
    fun grammar(prefs: Prefs): String {
        val words = (DEFAULTS.joinToString(" ") + " " + prefs.wakeExtra + " hello hi okay our a")
            .lowercase(Locale.ROOT).split(Regex("[^a-z']+")).filter { it.isNotBlank() }.distinct()
        return JSONArray(words + "[unk]").toString()
    }
    private val GREETINGS = setOf("hey", "hay", "hi", "okay", "ok", "hello")

    fun variants(prefs: Prefs): List<String> =
        DEFAULTS + prefs.wakeExtra.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun norm(s: String) = s.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z' ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun detect(text: String, variants: List<String>): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return false
        // A plain "hello" also wakes AURELIS, but only from standby: while it is speaking, its own
        // voice could say "hello" and interrupt itself.
        if (AppState.core.value == CoreState.STANDBY && HELLO.containsMatchIn(t)) return true
        if (variants.any { v -> norm(v).let { it.isNotEmpty() && t.contains(it) } }) return true
        // Fuzzy: a greeting followed by 1-3 words that together sound like "aurelis".
        val words = t.split(" ")
        for (i in words.indices) {
            if (words[i] !in GREETINGS) continue
            for (n in 1..3) {
                if (i + n >= words.size + 1) break
                val end = minOf(words.size, i + 1 + n)
                if (end <= i + 1) break
                val joined = words.subList(i + 1, end).joinToString("").replace("'", "")
                if (lev(joined, "aurelis") <= 2 || lev(joined, "aurelius") <= 2) return true
            }
        }
        return false
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

/** HTTPS file downloader with progress. Interrupted downloads resume instead of starting again. */
object Downloader {
    fun download(url: String, dest: File, progress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val have = if (tmp.exists()) tmp.length() else 0L
        var current = url
        var conn: HttpURLConnection? = null
        // Follow redirects manually (Hugging Face redirects to its CDN).
        for (hop in 0 until 6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "AURELIS-Android")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                current = URL(URL(current), loc).toString()
                c.disconnect()
                continue
            }
            if (code == 416) {
                tmp.delete()
                throw IOException("Download had to restart. Please try again.")
            }
            if (code !in 200..299) throw IOException("Download failed (HTTP $code)")
            conn = c
            break
        }
        val c = conn ?: throw IOException("Too many redirects")
        val resumed = c.responseCode == 206
        val length = c.contentLengthLong
        val total = if (length > 0) length + (if (resumed) have else 0L) else -1L
        var done = if (resumed) have else 0L
        var lastPct = -1
        c.inputStream.use { input ->
            FileOutputStream(tmp, resumed).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            progress(pct)
                        }
                    }
                }
            }
        }
        c.disconnect()
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("Could not save file")
    }

    fun unzip(zip: File, targetDir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(targetDir, entry.name)
                if (!out.canonicalPath.startsWith(targetDir.canonicalPath)) continue // zip-slip guard
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
            }
        }
    }
}

/**
 * Vosk: fully offline speech recognition. Used for the "Hey AURELIS" wake word
 * (standby audio never leaves the phone) and for offline commands.
 */
object Ears {
    const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val MODEL_MB = 40

    private var model: Model? = null
    private var thread: Thread? = null
    @Volatile private var runId = 0
    private val main = Handler(Looper.getMainLooper())

    fun modelDir(ctx: Context) = File(ctx.filesDir, MODEL_NAME)

    fun isInstalled(ctx: Context) = File(modelDir(ctx), "conf").exists() && File(modelDir(ctx), "am").exists()

    fun isLoaded() = model != null

    /** Downloads (~40 MB) and unpacks the offline English speech model. Blocking. */
    fun install(ctx: Context) {
        val zip = File(ctx.cacheDir, "$MODEL_NAME.zip")
        AppState.speechDownload.value = 0
        try {
            Downloader.download(MODEL_URL, zip) { AppState.speechDownload.value = it }
            Downloader.unzip(zip, ctx.filesDir)
        } finally {
            zip.delete()
            AppState.speechDownload.value = -1
        }
    }

    fun uninstall(ctx: Context) {
        stop()
        model?.close()
        model = null
        modelDir(ctx).deleteRecursively()
    }

    /** Loads the model into memory. Blocking (a second or two). */
    @Synchronized
    fun load(ctx: Context): Boolean {
        if (model != null) return true
        if (!isInstalled(ctx)) return false
        return try {
            model = Model(modelDir(ctx).absolutePath)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Starts continuous offline recognition on our own microphone loop.
     * Callbacks arrive on the main thread. [onClap] fires on a double clap.
     */
    @SuppressLint("MissingPermission")
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onFail: (String) -> Unit,
        onClap: (() -> Unit)? = null,
        grammar: String? = null
    ): Boolean {
        stop()
        val m = model ?: return false
        val id = ++runId
        // A grammar limits the recogniser to the wake words: far fewer misses than open dictation.
        val rec = try {
            if (grammar != null) {
                try {
                    Recognizer(m, 16000.0f, grammar)
                } catch (e: Throwable) {
                    Recognizer(m, 16000.0f)
                }
            } else {
                Recognizer(m, 16000.0f)
            }
        } catch (e: Throwable) {
            onFail(e.message ?: "Could not start offline listening")
            return false
        }
        val audio = try {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400)
            )
        } catch (e: Throwable) {
            rec.close()
            onFail(e.message ?: "Could not open the microphone")
            return false
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            rec.close()
            onFail("The microphone is busy or not allowed")
            return false
        }
        val t = Thread {
            val buf = ShortArray(800) // 50 ms of audio
            val claps = ClapDetector()
            var lastPartial = ""
            try {
                audio.startRecording()
                while (runId == id) {
                    val got = audio.read(buf, 0, buf.size)
                    if (got < 0) throw IOException("Microphone stopped")
                    if (got == 0) continue
                    if (onClap != null && claps.feed(buf, got)) main.post { if (runId == id) onClap() }
                    if (rec.acceptWaveForm(buf, got)) {
                        val text = parse(rec.result, "text")
                        lastPartial = ""
                        main.post { if (runId == id) onFinal(text) }
                    } else {
                        val part = parse(rec.partialResult, "partial")
                        if (part.isNotEmpty() && part != lastPartial) {
                            lastPartial = part
                            main.post { if (runId == id) onPartial(part) }
                        }
                    }
                }
            } catch (e: Throwable) {
                main.post { if (runId == id) onFail(e.message ?: "Microphone error") }
            } finally {
                try {
                    audio.stop()
                } catch (e: Throwable) {
                }
                audio.release()
                rec.close()
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

    private fun parse(json: String?, field: String): String =
        try {
            JSONObject(json ?: "{}").optString(field, "").trim()
        } catch (e: Exception) {
            ""
        }
}

/**
 * Hears a double clap: two very short, very loud bursts 0.15 to 0.8 seconds apart.
 * Speech is ignored because its loud stretches last much longer than a clap.
 */
class ClapDetector {
    private var frame = 0L          // 10 ms frames seen so far
    private var runLength = 0       // consecutive loud frames
    private var runPeak = 0
    private var lastClapFrame = -1000L

    fun feed(buf: ShortArray, len: Int): Boolean {
        var fired = false
        var i = 0
        while (i + 160 <= len) {
            var peak = 0
            for (j in i until i + 160) {
                val v = buf[j].toInt()
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
            }
            frame++
            if (peak > 9000) {
                runLength++
                if (peak > runPeak) runPeak = peak
            } else if (runLength > 0) {
                // a loud burst just ended: short and sharp means a clap
                if (runLength <= 12 && runPeak > 14000) {
                    val gap = frame - lastClapFrame
                    if (gap in 15L..80L) {
                        fired = true
                        lastClapFrame = -1000L
                    } else {
                        lastClapFrame = frame
                    }
                }
                runLength = 0
                runPeak = 0
            }
            i += 160
        }
        return fired
    }
}
