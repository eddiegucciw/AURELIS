package com.aurelis.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

/**
 * Android's Accessibility service for AURELIS. It does nothing on its own: it only scrolls, taps,
 * types, reads or takes a screenshot when the user gives a spoken command.
 */
class AurelisAccess : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AurelisAccess? = null
            private set

        /** The last app (other than AURELIS) that was on screen. */
        @Volatile
        var lastPackage = ""
            private set

        fun enabled(ctx: Context): Boolean {
            if (instance != null) return true
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val c = ComponentName(ctx, AurelisAccess::class.java)
            return flat.split(":").any { it.equals(c.flattenToString(), true) || it.equals(c.flattenToShortString(), true) }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = event.packageName?.toString() ?: return
        if (p != packageName && p != "com.android.systemui") lastPackage = p
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

/** Wake-word sensitivity: how sure AURELIS must be before it answers speech it overheard. */
object Tune {
    private fun p(ctx: Context) = ctx.getSharedPreferences("aurelis", Context.MODE_PRIVATE)

    /** 0 = low (fewest false starts), 1 = normal, 2 = high (answers more easily). */
    fun sensitivity(ctx: Context) = p(ctx).getInt("wake_sensitivity", 1)
    fun setSensitivity(ctx: Context, v: Int) = p(ctx).edit().putInt("wake_sensitivity", v.coerceIn(0, 2)).apply()

    /** Minimum recogniser confidence for a command heard without the wake word. */
    fun threshold(ctx: Context) = when (sensitivity(ctx)) {
        0 -> 0.92f
        2 -> 0.70f
        else -> 0.84f
    }
}

/**
 * The second half of the command library: screen and touch control, reading the screen,
 * typing, files, apps, the camera, workflows and self-tests. Returns null when the words
 * are not one of these commands.
 */
object Actions {
    private const val REPO = "eddiegucciw/AURELIS"

    /** First words of commands that can be acted on without the wake word. */
    val VERBS = setOf(
        "scroll", "swipe", "tap", "click", "press", "type", "read", "describe", "summarise", "summarize",
        "explain", "translate", "find", "search", "select", "copy", "cut", "paste", "clear", "delete",
        "lock", "take", "zoom", "refresh", "reload", "close", "switch", "list", "open", "show", "go",
        "move", "focus", "activate", "rename", "share", "create", "check", "test", "run", "stop", "start",
        "pause", "resume", "snooze", "cancel", "set", "turn", "call", "text", "play", "next", "previous",
        "volume", "brightness", "mute", "unmute", "what", "what's", "remind", "add", "navigate", "how"
    )

    private val ORD = mapOf(
        "first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3, "fourth" to 4,
        "4th" to 4, "fifth" to 5, "5th" to 5, "sixth" to 6, "6th" to 6, "seventh" to 7, "eighth" to 8,
        "ninth" to 9, "tenth" to 10, "last" to -1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5
    )
    private val YES = setOf("yes", "yeah", "yep", "sure", "do it", "go ahead", "confirm", "ok", "okay", "yes please", "correct")

    // Conversation state for follow-ups
    private var pending: (suspend () -> Reply)? = null
    private var pendingAt = 0L
    private var echoNext = false
    private var dictateNext = false
    private var chunks: List<String> = emptyList()
    private var chunkIndex = 0
    private var snapshot: Set<String> = emptySet()
    private var lastShot: String? = null
    private var focusIndex = -1
    private var headingIndex = -1
    private var results: List<File> = emptyList()
    private var lastAction = ""

    // ------------------------------------------------------------------ basics

    private fun svc() = AurelisAccess.instance

    private fun launch(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    private fun gone(ok: Boolean, text: String): Reply =
        if (ok) Reply(text, end = true, leftApp = true)
        else Reply("I couldn't open that on this phone.")

    /** A finished phone action. The conversation stays open for a follow-up unless [finish]. */
    private fun done(text: String, keep: Boolean = true, finish: Boolean = false): Reply {
        lastAction = text
        return Reply(text, end = finish || !keep, leftApp = true)
    }

    /** True when the user is working in another app, so AURELIS shouldn't jump in front of it. */
    fun busyInApp(ctx: Context): Boolean {
        val pkg = AurelisAccess.instance?.rootInActiveWindow?.packageName?.toString() ?: return false
        if (pkg == ctx.packageName || pkg == "com.android.systemui") return false
        val home = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return pkg != home
    }

    private fun askAccess(ctx: Context): Reply {
        launch(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return Reply(
            "To control the screen I need Accessibility. I've opened it: choose AURELIS, then turn it on. " +
                "If Android says restricted setting, open AURELIS app info, tap the three dots at the top, choose allow restricted settings, and try again.",
            end = true, leftApp = true
        )
    }

    private fun need(ctx: Context): Reply? = if (svc() == null) askAccess(ctx) else null

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun digits(s: String): String = s.split(" ").joinToString(" ") { w ->
        when (w) {
            "one" -> "1"; "two" -> "2"; "three" -> "3"; "four" -> "4"; "five" -> "5"; "six" -> "6"
            "seven" -> "7"; "eight" -> "8"; "nine" -> "9"; "ten" -> "10"; "twenty" -> "20"; "fifty" -> "50"
            "hundred" -> "100"
            else -> w
        }
    }

    private fun ordinal(word: String): Int? = ORD[word] ?: word.toIntOrNull()

    /** When AURELIS itself is on screen, step back so commands reach the app behind it. */
    private suspend fun away() {
        if (AppState.uiVisible.value) {
            svc()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(900)
        }
    }

    private fun global(action: Int): Boolean = svc()?.performGlobalAction(action) ?: false

    // ------------------------------------------------------------------ gestures

    private suspend fun gesture(strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val s = svc() ?: return false
        val b = GestureDescription.Builder()
        strokes.forEach { b.addStroke(it) }
        val g = b.build()
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val ok = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
                if (!ok && cont.isActive) cont.resume(false)
            }
        }
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, start: Long, dur: Long): GestureDescription.StrokeDescription {
        val p = Path()
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
        return GestureDescription.StrokeDescription(p, start, dur)
    }

    private fun size(): Pair<Float, Float> {
        val m = (svc()?.resources ?: AurelisApp.instance.resources).displayMetrics
        return m.widthPixels.toFloat() to m.heightPixels.toFloat()
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 300) =
        gesture(listOf(line(x1, y1, x2, y2, 0, dur)))

    private suspend fun tapAt(x: Float, y: Float, hold: Long = 60) = gesture(listOf(line(x, y, x, y, 0, hold)))

    private suspend fun pinch(zoomIn: Boolean): Boolean {
        val (w, h) = size()
        val cx = w / 2
        val cy = h / 2
        val near = w * 0.06f
        val far = w * 0.32f
        val a = if (zoomIn) near else far
        val b = if (zoomIn) far else near
        return gesture(
            listOf(
                line(cx - a, cy - a, cx - b, cy - b, 0, 400),
                line(cx + a, cy + a, cx + b, cy + b, 0, 400)
            )
        )
    }

    // ------------------------------------------------------------------ the screen's contents

    private fun root(): AccessibilityNodeInfo? = svc()?.rootInActiveWindow

    /** Every node on screen, in reading order. */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val r = root() ?: return out
        val stack = ArrayList<AccessibilityNodeInfo>()
        stack.add(r)
        while (stack.isNotEmpty() && out.size < 2000) {
            val n = stack.removeAt(stack.size - 1)
            out.add(n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    private fun label(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        return n.contentDescription?.toString()?.trim().orEmpty()
    }

    private fun bounds(n: AccessibilityNodeInfo): Rect {
        val r = Rect()
        n.getBoundsInScreen(r)
        return r
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && depth < 8) {
            if (c.isClickable) return c
            c = c.parent
            depth++
        }
        return null
    }

    private suspend fun click(n: AccessibilityNodeInfo, long: Boolean = false): Boolean {
        val target = clickable(n)
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if (target != null && target.performAction(action)) return true
        val r = bounds(n)
        if (r.isEmpty) return false
        return tapAt(r.exactCenterX(), r.exactCenterY(), if (long) 800 else 60)
    }

    private fun visibleTexts(): List<String> {
        val seen = LinkedHashSet<String>()
        for (n in nodes()) {
            if (!n.isVisibleToUser) continue
            val t = label(n).replace(Regex("\\s+"), " ")
            if (t.length >= 2) seen.add(t)
        }
        return seen.toList()
    }

    private fun find(query: String): AccessibilityNodeInfo? {
        val q = Intents.norm(query)
        if (q.isEmpty()) return null
        val all = nodes().filter { it.isVisibleToUser }
        val exact = all.firstOrNull { Intents.norm(label(it)) == q }
        if (exact != null) return exact
        val ids = all.firstOrNull { (it.viewIdResourceName ?: "").substringAfter("/").replace("_", " ").lowercase(Locale.ROOT) == q }
        if (ids != null) return ids
        return all.filter { Intents.norm(label(it)).contains(q) }.minByOrNull { label(it).length }
    }

    /** Clickable items with text, top to bottom: "the third result". */
    private fun items(): List<AccessibilityNodeInfo> {
        val (_, h) = size()
        val seen = HashSet<String>()
        val out = ArrayList<AccessibilityNodeInfo>()
        for (n in nodes()) {
            if (!n.isVisibleToUser || n.isEditable) continue
            val t = label(n)
            if (t.length < 3) continue
            val c = clickable(n) ?: continue
            val r = bounds(c)
            if (r.top < h * 0.1f || r.height() > h * 0.6f || r.width() < 60) continue
            val key = r.flattenToString()
            if (seen.add(key)) out.add(n)
        }
        return out.sortedWith(compareBy({ bounds(it).top }, { bounds(it).left }))
    }

    private fun editable(): AccessibilityNodeInfo? {
        val r = root() ?: return null
        val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (f != null && f.isEditable) return f
        return nodes().firstOrNull { it.isEditable && it.isVisibleToUser }
    }

    private fun fieldText(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        return n.text?.toString().orEmpty()
    }

    private fun setText(n: AccessibilityNodeInfo, t: String): Boolean {
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        if (ok) select(n, t.length, t.length)
        return ok
    }

    private fun select(n: AccessibilityNodeInfo, start: Int, end: Int): Boolean {
        val b = Bundle()
        b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, b)
    }

    private fun appName(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    // ------------------------------------------------------------------ seeing the screen

    private fun jpeg(bmp: Bitmap, max: Int = 1024): String {
        val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        val b = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 72, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** A screenshot as base64 JPEG, for the online brain to look at. Needs Android 11 or newer. */
    private suspend fun screenshot(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val s = svc() ?: return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { cont ->
                try {
                    s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val hb = result.hardwareBuffer
                            val shot = try {
                                Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)?.let { jpeg(it) }
                            } catch (e: Exception) {
                                null
                            }
                            hb.close()
                            if (cont.isActive) cont.resume(shot)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun online(ctx: Context) =
        AppState.online.value && !Opt2.forceOffline(ctx) && SecureStore.get(ctx, "cloud_key").isNotBlank()

    /** Asks Gemini about pictures (screenshots, photos, PDF pages). Returns null if that isn't possible. */
    private fun look(ctx: Context, task: String, images: List<String>, text: String = ""): String? {
        if (!online(ctx)) return null
        val key = SecureStore.get(ctx, "cloud_key")
        val parts = JSONArray()
        images.forEach { parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", it))) }
        parts.put(JSONObject().put("text", task + (if (text.isNotBlank()) "\n\nText found on the screen:\n" + text.take(6000) else "")))
        val body = JSONObject()
            .put(
                "system_instruction", JSONObject().put(
                    "parts", JSONArray().put(
                        JSONObject().put(
                            "text",
                            "You are AURELIS, a voice assistant on the user's Android phone. Your answer is spoken aloud: " +
                                "plain sentences only, no markdown, lists or symbols. Be clear and helpful. Keep it under 120 words unless asked to read everything."
                        )
                    )
                )
            )
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        return try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/${AurelisApp.instance.prefs.cloudModel}:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) {
                c.disconnect()
                return null
            }
            val json = JSONObject(c.inputStream.bufferedReader().readText())
            c.disconnect()
            val p = json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until p.length()) sb.append(p.getJSONObject(i).optString("text"))
            sb.toString().replace(Regex("[*#_`]"), "").trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Text-only rewrite of a piece of text by Gemini. */
    private fun rewrite(ctx: Context, how: String, text: String): String? {
        if (!online(ctx)) return null
        return try {
            CloudBrain.ask(
                SecureStore.get(ctx, "cloud_key"), AurelisApp.instance.prefs.cloudModel,
                "You rewrite text. Reply with ONLY the rewritten text, nothing before or after it.",
                emptyList(), "$how:\n\n$text"
            ).trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Looks at the screen (with a screenshot when possible) and answers [task] about it. */
    private suspend fun aboutScreen(ctx: Context, task: String, wantShot: Boolean = true): Reply {
        need(ctx)?.let { return it }
        away()
        val texts = visibleTexts()
        val shot = if (wantShot) screenshot() else null
        val answer = withContext(Dispatchers.IO) {
            look(ctx, task, listOfNotNull(shot), texts.joinToString("\n"))
        }
        if (shot != null) lastShot = shot
        snapshot = texts.toSet()
        if (answer != null) return done(answer)
        if (texts.isEmpty()) return done("I can't find any readable text on this screen.")
        chunks = texts.joinToString(". ").chunked(500)
        chunkIndex = 0
        return done(
            (if (!online(ctx)) "I'm offline, so I'll read what's on the screen instead. " else "") + chunks[0] +
                (if (chunks.size > 1) " Say read more to continue." else "")
        )
    }

    // ------------------------------------------------------------------ files

    private fun filesAllowed(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun askFiles(ctx: Context): Reply {
        val ok = if (Build.VERSION.SDK_INT >= 30) {
            launch(ctx, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))) ||
                launch(ctx, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } else {
            launch(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }
        return Reply(
            if (ok) "To work with your files I need file access. I've opened the switch: turn on allow access to manage all files, then ask me again."
            else "Please allow file access for AURELIS in Settings, then ask me again.",
            end = true, leftApp = ok
        )
    }

    private fun storage(): File = Environment.getExternalStorageDirectory()

    private fun folder(name: String): File {
        val s = storage()
        return when {
            name.startsWith("download") -> File(s, Environment.DIRECTORY_DOWNLOADS)
            name.startsWith("document") -> File(s, Environment.DIRECTORY_DOCUMENTS)
            name.startsWith("picture") || name.startsWith("photo") || name.startsWith("image") -> File(s, Environment.DIRECTORY_PICTURES)
            name.startsWith("music") || name.startsWith("audio") -> File(s, Environment.DIRECTORY_MUSIC)
            name.startsWith("movie") || name.startsWith("video") -> File(s, Environment.DIRECTORY_MOVIES)
            name.startsWith("camera") || name.startsWith("dcim") -> File(s, Environment.DIRECTORY_DCIM)
            else -> File(File(s, Environment.DIRECTORY_DOCUMENTS), name.trim().replace(Regex("[^A-Za-z0-9 _-]"), ""))
        }
    }

    private val TYPES = mapOf(
        "pdf" to setOf("pdf"),
        "word" to setOf("doc", "docx", "odt", "rtf"),
        "document" to setOf("pdf", "doc", "docx", "odt", "rtf", "txt", "xls", "xlsx", "ppt", "pptx"),
        "spreadsheet" to setOf("xls", "xlsx", "csv", "ods"),
        "presentation" to setOf("ppt", "pptx", "odp"),
        "image" to setOf("jpg", "jpeg", "png", "webp", "heic", "gif"),
        "photo" to setOf("jpg", "jpeg", "png", "webp", "heic"),
        "picture" to setOf("jpg", "jpeg", "png", "webp", "heic", "gif"),
        "video" to setOf("mp4", "mkv", "3gp", "webm", "mov"),
        "song" to setOf("mp3", "m4a", "aac", "ogg", "wav", "flac"),
        "audio" to setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus"),
        "music" to setOf("mp3", "m4a", "aac", "ogg", "wav", "flac"),
        "text" to setOf("txt", "md"),
        "archive" to setOf("zip", "rar", "7z", "tar", "gz"),
        "apk" to setOf("apk")
    )

    private fun typeOf(word: String): Set<String>? = TYPES[word.removeSuffix("s")] ?: TYPES[word]

    private fun scan(nameQuery: String?, exts: Set<String>?, limit: Int = 40): List<File> {
        val q = nameQuery?.lowercase(Locale.ROOT)?.trim()?.takeIf { it.isNotEmpty() }
        return storage().walkTopDown()
            .onEnter { it.name != "Android" && !it.name.startsWith(".") }
            .maxDepth(7)
            .filter { f ->
                f.isFile && (exts == null || f.extension.lowercase(Locale.ROOT) in exts) &&
                    (q == null || f.name.lowercase(Locale.ROOT).contains(q))
            }
            .take(4000)
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .toList()
    }

    private fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    private fun mime(f: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase(Locale.ROOT)) ?: "*/*"

    private fun openFile(ctx: Context, f: File): Boolean = launch(
        ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri(ctx, f), mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )

    private fun shareFiles(ctx: Context, files: List<File>): Boolean {
        val uris = ArrayList(files.map { uri(ctx, it) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(mime(files[0])).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        return launch(ctx, Intent.createChooser(i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
    }

    private fun sizeText(bytes: Long): String = when {
        bytes > 1_000_000_000 -> String.format(Locale.ROOT, "%.1f gigabytes", bytes / 1e9)
        bytes > 1_000_000 -> String.format(Locale.ROOT, "%.1f megabytes", bytes / 1e6)
        bytes > 1000 -> "${bytes / 1000} kilobytes"
        else -> "$bytes bytes"
    }

    private fun spoken(list: List<File>): String =
        list.take(5).mapIndexed { i, f -> "${i + 1}, ${f.nameWithoutExtension}" }.joinToString("; ")

    /** The file the user means: "the second file", a name, or the latest result. */
    private fun pick(ref: String): File? {
        val r = ref.trim()
        ORD.entries.firstOrNull { r.startsWith(it.key) }?.let { e ->
            val i = if (e.value == -1) results.size - 1 else e.value - 1
            return results.getOrNull(i)
        }
        if (r.isEmpty() || r in setOf("it", "this", "that", "this file", "that file", "the file")) return results.firstOrNull()
        return results.firstOrNull { it.name.lowercase(Locale.ROOT).contains(r) } ?: scan(r, null, 1).firstOrNull()
    }

    private fun pdfPages(f: File, max: Int = 4): List<String> {
        val out = ArrayList<String>()
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                for (i in 0 until minOf(max, r.pageCount)) {
                    r.openPage(i).use { page ->
                        val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.add(jpeg(bmp, 1400))
                    }
                }
            }
        }
        return out
    }

    private fun zip(files: List<File>, dest: File) {
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream().buffered()).use { z ->
            for (f in files) {
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    private fun unzip(src: File, dest: File): Int {
        var count = 0
        dest.mkdirs()
        ZipInputStream(src.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(dest.canonicalPath)) continue
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                    count++
                }
            }
        }
        return count
    }

    private fun hash(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ apps

    private fun apps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
    }

    private fun appPackage(ctx: Context, name: String): String? {
        val q = name.lowercase(Locale.ROOT).trim()
        val all = apps(ctx)
        return (all.firstOrNull { it.first.lowercase(Locale.ROOT) == q } ?: all.firstOrNull { it.first.lowercase(Locale.ROOT).contains(q) })?.second
    }

    // ------------------------------------------------------------------ web helpers

    private fun getJson(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "AURELIS-Android")
        val body = if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        c.disconnect()
        body
    } catch (e: Exception) {
        null
    }

    private val CURRENCY = mapOf(
        "dollar" to "USD", "dollars" to "USD", "usd" to "USD", "shilling" to "KES", "shillings" to "KES", "kes" to "KES",
        "bob" to "KES", "euro" to "EUR", "euros" to "EUR", "pound" to "GBP", "pounds" to "GBP", "sterling" to "GBP",
        "rand" to "ZAR", "naira" to "NGN", "yen" to "JPY", "yuan" to "CNY", "rupee" to "INR", "rupees" to "INR",
        "dirham" to "AED", "dirhams" to "AED", "ugandan shillings" to "UGX", "tanzanian shillings" to "TZS",
        "franc" to "CHF", "francs" to "CHF", "riyal" to "SAR", "canadian dollars" to "CAD", "australian dollars" to "AUD"
    )

    // ------------------------------------------------------------------ the commands

    suspend fun handle(ctx: Context, raw: String): Reply? {
        val n = Intents.norm(raw)
        if (n.isEmpty()) return null
        val d = digits(n)
        fun m(p: String) = Regex(p).find(n)
        fun md(p: String) = Regex(p).find(d)
        fun has(p: String) = Regex(p).containsMatchIn(n)
        fun rawAfter(p: String): String? = Regex(p, RegexOption.IGNORE_CASE).find(raw.trim())?.groupValues?.get(1)?.trim()

        // ---- follow-ups
        pending?.let { act ->
            pending = null
            if (System.currentTimeMillis() - pendingAt < 60000) {
                if (n in YES) return act()
                if (n in setOf("no", "nope", "cancel", "stop", "don't")) return Reply("Cancelled. Nothing was changed.")
            }
        }
        if (echoNext) {
            echoNext = false
            return Reply("I heard: $raw. Speech recognition is working.")
        }
        if (dictateNext) {
            dictateNext = false
            need(ctx)?.let { return it }
            away()
            val f = editable() ?: return Reply("I couldn't find a text box on the screen.")
            setText(f, (fieldText(f) + " " + raw.trim()).trim())
            return done("Typed.")
        }
        if (has("^(read )?(more|continue reading|keep reading|go on|next part)$") && chunks.isNotEmpty()) {
            chunkIndex++
            if (chunkIndex >= chunks.size) return Reply("That's the end of the page.", end = true)
            return Reply(chunks[chunkIndex] + if (chunkIndex < chunks.size - 1) " Say read more to continue." else "")
        }

        // ---- activation and the voice itself
        if (has("^(wake up|listen|are you there|you there)$")) return Reply("I'm listening.")
        if (has("^(stop everything|stop all|stop talking|be quiet|shut up|quiet|silence|stop speaking|stop reading)$")) {
            Voice.stop()
            pending = null
            chunks = emptyList()
            return Reply("Stopped.", end = true)
        }
        if (has("^continue$")) return Reply("Go ahead, I'm listening.")
        if (has("explain what you('re| are) doing|what are you doing|what did you (just )?do|explain the last (action|automation)")) {
            return Reply(if (lastAction.isEmpty()) "I'm waiting for your next instruction." else "The last thing I did: $lastAction")
        }
        md("(?:set |change )?(?:the )?(?:wake word |wake |listening |activation )?sensitivity (?:to )?(low|normal|medium|high)")?.let {
            val v = when (it.groupValues[1]) { "low" -> 0; "high" -> 2; else -> 1 }
            Tune.setSensitivity(ctx, v)
            return Reply(
                when (v) {
                    0 -> "Sensitivity is low. I'll only answer clear commands, so there will be fewer false starts."
                    2 -> "Sensitivity is high. I'll answer more easily."
                    else -> "Sensitivity is normal."
                }
            )
        }

        // ---- self-tests
        if (has("test (the )?(text to speech|tts|your voice|speech output)")) return Reply("This is my voice test. If you can hear me clearly, text to speech is working.")
        if (has("test (the )?(speech recognition|microphone|mic|listening)")) {
            echoNext = true
            return Reply("Say any sentence and I'll repeat what I heard.")
        }
        if (has("test (the )?(wake word|wake up|activation)")) return Reply("After I stop talking, say Hey AURELIS. If I answer, the wake word is working.", end = true)
        if (has("test (the )?offline( mode)?")) {
            val ears = Ears.isInstalled(ctx)
            val brain = LocalBrain.active(ctx) != null
            return Reply(
                "Offline check. Speech recognition: ${if (ears) "ready" else "not installed"}. Offline brain: ${if (brain) "ready" else "not downloaded, so offline I can run commands but not chat"}. " +
                    "Screen control: ${if (svc() != null) "on" else "off"}. Every phone command works without internet."
            )
        }
        if (has("(run a |run the )?capability test|check feature availability|what features (are|do you have)|show active permissions")) {
            val ok = ArrayList<String>()
            val off = ArrayList<String>()
            fun add(name: String, on: Boolean) = if (on) ok.add(name) else off.add(name)
            add("microphone", granted(ctx, Manifest.permission.RECORD_AUDIO))
            add("offline speech", Ears.isInstalled(ctx))
            add("screen control", svc() != null)
            add("notification access", NotificationReader.enabled(ctx))
            add("file access", filesAllowed(ctx))
            add("contacts", granted(ctx, Manifest.permission.READ_CONTACTS))
            add("system settings", Settings.System.canWrite(ctx))
            add("display over apps", Settings.canDrawOverlays(ctx))
            add("online brain", online(ctx))
            return Reply(
                "Working: ${ok.joinToString(", ")}." + if (off.isNotEmpty()) " Not yet set up: ${off.joinToString(", ")}. Open the setup screen from Home to switch them on." else " Everything is ready."
            )
        }
        if (has("(show|list|what are) (the |your )?unsupported commands|what can't you do")) {
            return Reply(
                "Things Android doesn't allow me to do: undo or redo typing in other apps, record the screen silently, restart the phone by myself, " +
                    "pause timers inside the Clock app on every phone, or read Clock alarms. I always ask before sending messages, calling or deleting files."
            )
        }

        // ---- global navigation (screen control)
        if (has("^(go |press |push )?(the )?(back|previous screen)( button)?$|^go back( one step)?$|^return to the previous screen$|^press escape$|^back$")) {
            need(ctx)?.let { return it }
            global(AccessibilityService.GLOBAL_ACTION_BACK)
            return done("Back.")
        }
        if (has("^(press |go )?(the )?home( button| screen)?$|^go home$|^press home$")) {
            need(ctx)?.let { return null }
            global(AccessibilityService.GLOBAL_ACTION_HOME)
            return done("Home.")
        }
        if (has("^(open |show )?(the )?recent apps$|^(open|show) (the )?recents$|^switch apps$|^app switcher$|^overview$")) {
            need(ctx)?.let { return it }
            global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            return done("Recent apps.")
        }
        if (has("^(open |go to |switch to )?(the )?(last|previous) app$|^switch back$")) {
            need(ctx)?.let { return it }
            global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            delay(450)
            global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            return done("Switched to the last app.")
        }
        if (has("^(open |show |pull down )?(the )?(notification shade|notification panel|notifications? (bar|drawer))$")) {
            need(ctx)?.let { return it }
            global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            return done("Notifications open.")
        }
        if (has("(open|show) (the )?quick settings")) {
            need(ctx)?.let { return it }
            global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            return done("Quick settings open.")
        }
        if (has("(open|show) (the )?power (menu|options|dialog)|restart (my |the )?phone|reboot|power off|shut down (my |the )?phone|switch off (my |the )?phone")) {
            need(ctx)?.let { return it }
            global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            return done("Here's the power menu. Android lets only you choose restart or power off, so tap the one you want.")
        }
        if (has("^lock (the |my )?(screen|phone|device)$|^lock it$|^lock$")) {
            need(ctx)?.let { return it }
            if (Build.VERSION.SDK_INT < 28) return Reply("Locking by voice needs Android 9 or newer.")
            Voice.stop()
            global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            return done("Locked.", finish = true)
        }
        if (has("(take|capture|grab) a? ?screen ?shot|^screen ?shot$")) {
            need(ctx)?.let { return it }
            if (Build.VERSION.SDK_INT < 28) return Reply("Screenshots by voice need Android 9 or newer.")
            away()
            global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            return done("Screenshot taken. It's in your gallery.")
        }
        if (has("split screen")) {
            need(ctx)?.let { return it }
            global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            return done("Split screen.")
        }
        if (has("record (the |my )?screen")) {
            return Reply("Android only allows screen recording with your own tap. Pull down quick settings and tap screen record. I can open quick settings for you.")
        }
        if (has("^(close|exit|quit|kill) (this|the|current) app$|^close (it|this)$")) {
            need(ctx)?.let { return it }
            global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            delay(900)
            val (w, h) = size()
            swipe(w / 2, h * 0.55f, w / 2, h * 0.08f, 220)
            delay(300)
            global(AccessibilityService.GLOBAL_ACTION_HOME)
            return done("Closed.", finish = true)
        }
        if (has("^go forward$")) {
            need(ctx)?.let { return it }
            away()
            val f = find("forward")
            if (f != null && click(f)) return done("Forward.")
            return Reply("This app has no forward button I can press. I can go back instead.")
        }

        // ---- scrolling and swiping
        m("^(?:scroll|move|page)(?: the page| the screen)? (up|down|left|right)(?: (slowly|slow|faster|fast|quickly|a bit|a little|one page|half a page|a page))?$|^scroll (slowly|slow|faster|fast|one page|half a page)(?: (up|down))?$|^page (up|down)$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val g = it.groupValues
            val dir = g[1].ifEmpty { g[4].ifEmpty { g[5].ifEmpty { "down" } } }
            val how = g[2].ifEmpty { g[3] }
            val (w, h) = size()
            val dist = when {
                how.contains("half") || how.contains("bit") || how.contains("little") -> 0.25f
                how.contains("page") -> 0.6f
                else -> 0.45f
            }
            val dur = when {
                how.startsWith("slow") -> 900L
                how.startsWith("fast") || how.startsWith("quick") -> 150L
                else -> 350L
            }
            val ok = when (dir) {
                "down" -> swipe(w / 2, h * (0.5f + dist / 2), w / 2, h * (0.5f - dist / 2), dur)
                "up" -> swipe(w / 2, h * (0.5f - dist / 2), w / 2, h * (0.5f + dist / 2), dur)
                "left" -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2, dur)
                else -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2, dur)
            }
            return if (ok) done("Scrolled $dir.") else Reply("This screen didn't let me scroll.")
        }
        m("^(?:scroll |go |jump )?(?:to )?(?:the )?(top|bottom|end|beginning|start)(?: of (?:the )?(?:page|screen|list))?$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val toTop = it.groupValues[1] in setOf("top", "beginning", "start")
            val (w, h) = size()
            repeat(12) {
                if (toTop) swipe(w / 2, h * 0.25f, w / 2, h * 0.85f, 120) else swipe(w / 2, h * 0.85f, w / 2, h * 0.25f, 120)
                delay(120)
            }
            return done(if (toTop) "At the top." else "At the bottom.")
        }
        m("^swipe (up|down|left|right)(?: from (?:the )?(left|right|top|bottom)?(?: ?edge)?)?$|^swipe from (?:the )?(left|right|top|bottom)? ?edge$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val g = it.groupValues
            val (w, h) = size()
            val edge = g[2].ifEmpty { g[3] }
            val ok = when {
                edge == "left" -> swipe(2f, h / 2, w * 0.6f, h / 2, 250)
                edge == "right" -> swipe(w - 2f, h / 2, w * 0.4f, h / 2, 250)
                edge == "top" -> swipe(w / 2, 2f, w / 2, h * 0.6f, 300)
                edge == "bottom" -> swipe(w / 2, h - 2f, w / 2, h * 0.4f, 300)
                g[1] == "up" -> swipe(w / 2, h * 0.75f, w / 2, h * 0.25f, 250)
                g[1] == "down" -> swipe(w / 2, h * 0.25f, w / 2, h * 0.75f, 250)
                g[1] == "left" -> swipe(w * 0.85f, h / 2, w * 0.15f, h / 2, 250)
                else -> swipe(w * 0.15f, h / 2, w * 0.85f, h / 2, 250)
            }
            return if (ok) done("Swiped.") else Reply("The swipe didn't go through.")
        }
        if (has("^(refresh|reload)( (this|the) (page|screen|feed|app))?$|^pull to refresh$")) {
            need(ctx)?.let { return it }
            away()
            val (w, h) = size()
            swipe(w / 2, h * 0.22f, w / 2, h * 0.75f, 600)
            return done("Refreshing.")
        }
        if (has("^zoom (in|out)$|^(magnify|enlarge) (the )?screen$|^make it bigger$|^make it smaller$")) {
            need(ctx)?.let { return it }
            away()
            val zin = has("in$|magnify|enlarge|bigger")
            pinch(zin)
            return done(if (zin) "Zoomed in." else "Zoomed out.")
        }

        // ---- tapping
        m("^(?:tap|click|press|select|choose|open|play)(?: on)? (?:the )?(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|last|1st|2nd|3rd|4th|5th|\\d+)(?:st|nd|rd|th)? (?:result|link|item|option|video|one|entry|song|message|chat|email|post|button|thing)s?$|^(?:use|take) the (first|second|third|last) option$|^open the (first|second|third|next) link$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val word = it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            val list = items()
            if (list.isEmpty()) return Reply("I can't find anything to tap on this screen.")
            val k = ordinal(if (word == "next") "first" else word) ?: 1
            val target = if (k == -1) list.last() else list.getOrNull(k - 1) ?: return Reply("There are only ${list.size} items I can see.")
            click(target)
            return done("Opening ${label(target).take(60)}.")
        }
        if (has("^(tap|click|press|touch|select)( (here|there|it|this|that))?$|^double (tap|click)( here| it| this)?$")) {
            need(ctx)?.let { return it }
            away()
            val (w, h) = size()
            val f = root()?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            val r = f?.let { bounds(it) }
            val x = r?.exactCenterX() ?: (w / 2)
            val y = r?.exactCenterY() ?: (h / 2)
            tapAt(x, y)
            if (n.startsWith("double")) {
                delay(90)
                tapAt(x, y)
            }
            return done("Tapped.")
        }
        m("^(?:long press|press and hold|hold|long tap|long click)(?: on)?(?: the)? ?(.*)$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val what = it.groupValues[1].trim()
            if (what.isEmpty() || what in setOf("this", "here", "it", "that")) {
                val (w, h) = size()
                tapAt(w / 2, h / 2, 900)
                return done("Long pressed.")
            }
            val node = find(what) ?: return Reply("I can't see $what on the screen.")
            click(node, long = true)
            return done("Long pressed $what.")
        }
        m("^(?:drag|move) (?:this|it|that) (up|down|left|right)$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val (w, h) = size()
            val cx = w / 2
            val cy = h / 2
            val p = Path()
            p.moveTo(cx, cy)
            when (it.groupValues[1]) {
                "up" -> p.lineTo(cx, cy - h * 0.25f)
                "down" -> p.lineTo(cx, cy + h * 0.25f)
                "left" -> p.lineTo(cx - w * 0.3f, cy)
                else -> p.lineTo(cx + w * 0.3f, cy)
            }
            gesture(listOf(GestureDescription.StrokeDescription(p, 0, 1400)))
            return done("Moved.")
        }
        if (has("^release$")) return Reply("Released.")

        // ---- text and keyboard
        rawAfter("^(?:type|enter|write in|key in|input|fill in)(?: this| the text| this text| in)?[:,]?\\s+(.+)$")?.let { text ->
            need(ctx)?.let { r -> return r }
            away()
            val f = editable() ?: return Reply("I couldn't find a text box on the screen. Tap one first, or ask me to tap it.")
            val cur = fieldText(f)
            setText(f, if (cur.isEmpty()) text else "$cur $text")
            return done("Typed.", keep = true)
        }
        if (has("^(dictate|start dictation|dictate a (paragraph|message|note))$")) {
            dictateNext = true
            return Reply("Go ahead. I'll type what you say next.")
        }
        if (has("^(clear|empty|erase|wipe)( (this|the|that))? (field|text|box|text box|search box|input)$|^delete (all )?(the )?text$")) {
            need(ctx)?.let { return it }
            away()
            val f = editable() ?: return Reply("I couldn't find a text box.")
            setText(f, "")
            return done("Cleared.", keep = true)
        }
        if (has("^(delete|remove|erase) (the )?last (word|sentence|line|letter|character)$|^backspace$")) {
            need(ctx)?.let { return it }
            away()
            val f = editable() ?: return Reply("I couldn't find a text box.")
            val cur = fieldText(f).trimEnd()
            val next = when {
                has("sentence") -> cur.replace(Regex("[^.!?]*[.!?]?\\s*$"), "").trimEnd()
                has("line") -> cur.substringBeforeLast("\n", "")
                has("letter|character|backspace") -> cur.dropLast(1)
                else -> cur.replace(Regex("\\S+$"), "").trimEnd()
            }
            setText(f, next)
            return done("Deleted.", keep = true)
        }
        if (has("^(select all|select everything|highlight all|select all (the )?text)$")) {
            need(ctx)?.let { return it }
            away()
            val f = editable()
            if (f != null) {
                select(f, 0, fieldText(f).length)
                return done("Selected all.", keep = true)
            }
            return Reply("There's no text box to select in. I can read the screen instead.")
        }
        if (has("^(deselect|unselect|clear (the )?selection)$")) {
            need(ctx)?.let { return it }
            val f = editable() ?: return Reply("Nothing is selected.")
            val len = fieldText(f).length
            select(f, len, len)
            return done("Deselected.", keep = true)
        }
        m("^(copy|cut|paste)( (this|that|it|the text|text|here))?$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val f = editable() ?: root()?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?: return Reply("Select or tap the text first.")
            val action = when (it.groupValues[1]) {
                "copy" -> AccessibilityNodeInfo.ACTION_COPY
                "cut" -> AccessibilityNodeInfo.ACTION_CUT
                else -> AccessibilityNodeInfo.ACTION_PASTE
            }
            val ok = f.performAction(action)
            return if (ok) done(it.groupValues[1].replaceFirstChar { c -> c.uppercase() } + ".", keep = true)
            else Reply("That text box didn't accept ${it.groupValues[1]}.")
        }
        if (has("^(undo|redo)( (typing|that|it))?$")) {
            return Reply("Android doesn't give apps an undo button for other apps' text. I can delete the last word or clear the field instead.")
        }
        m("^(?:move|put) (?:the )?cursor (?:to the )?(left|right|start|beginning|end)(?: of (?:the )?(?:text|field))?$|^(?:move|go) to the (beginning|end)$")?.let {
            need(ctx)?.let { r -> return r }
            val f = editable() ?: return Reply("I couldn't find a text box.")
            val len = fieldText(f).length
            val cur = f.textSelectionEnd.coerceIn(0, len)
            val pos = when (it.groupValues[1].ifEmpty { it.groupValues[2] }) {
                "left" -> (cur - 1).coerceAtLeast(0)
                "right" -> (cur + 1).coerceAtMost(len)
                "start", "beginning" -> 0
                else -> len
            }
            select(f, pos, pos)
            return done("Cursor moved.", keep = true)
        }
        m("^(?:insert a |add a |new )(new line|line break|space|tab)$|^press (space|tab|new line)$|^new line$")?.let {
            need(ctx)?.let { r -> return r }
            val f = editable() ?: return Reply("I couldn't find a text box.")
            val add = when {
                has("space") -> " "
                has("tab") -> "\t"
                else -> "\n"
            }
            setText(f, fieldText(f) + add)
            return done("Done.", keep = true)
        }
        if (has("^(press|hit) (the )?(enter|search|go|done|submit|ok)( key| button)?$|^enter$")) {
            need(ctx)?.let { return it }
            away()
            val f = editable()
            if (f != null && Build.VERSION.SDK_INT >= 30 &&
                f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            ) return done("Entered.")
            val btn = listOf("search", "go", "done", "submit", "ok").firstNotNullOfOrNull { find(it) }
            if (btn != null && click(btn)) return done("Pressed ${label(btn)}.")
            return Reply("I couldn't find an enter or send button here.")
        }
        m("^replace (?:the word )?(.+?) with (.+)$")?.let {
            need(ctx)?.let { r -> return r }
            val f = editable() ?: return Reply("I couldn't find a text box.")
            val cur = fieldText(f)
            if (!cur.contains(it.groupValues[1], ignoreCase = true)) return Reply("I can't find ${it.groupValues[1]} in the text.")
            setText(f, cur.replace(it.groupValues[1], it.groupValues[2], ignoreCase = true))
            return done("Replaced.", keep = true)
        }
        m("^make (?:this|it|the text) (uppercase|upper case|capitals|lowercase|lower case|title case)$")?.let {
            need(ctx)?.let { r -> return r }
            val f = editable() ?: return Reply("I couldn't find a text box.")
            val cur = fieldText(f)
            val out = when {
                it.groupValues[1].startsWith("upper") || it.groupValues[1] == "capitals" -> cur.uppercase()
                it.groupValues[1].startsWith("lower") -> cur.lowercase()
                else -> cur.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { c -> c.uppercase() } }
            }
            setText(f, out)
            return done("Done.", keep = true)
        }
        m("^(correct (?:the )?(?:spelling|grammar|punctuation)|fix (?:the )?(?:spelling|grammar|typos)|make (?:this|it) (?:more )?(?:formal|friendly|polite|professional|casual|shorter|longer)|shorten (?:this|it)|expand (?:this|it)|format (?:this|the) text|translate (?:this|the) text(?: (?:to|into) [a-z]+)?|rewrite (?:this|it))$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val f = editable()
            val cur = f?.let { fieldText(it) }.orEmpty()
            if (f == null || cur.isBlank()) {
                return aboutScreen(ctx, "Do this to the main text on the screen and read me the result: ${it.groupValues[1]}.")
            }
            val out = withContext(Dispatchers.IO) { rewrite(ctx, it.groupValues[1].replaceFirstChar { c -> c.uppercase() }, cur) }
                ?: return Reply("I need the internet and the online brain for that.")
            setText(f, out)
            return done("Done. I've rewritten the text in the box.", keep = true)
        }
        if (has("^(summari[sz]e|read) (the )?selected text$")) {
            need(ctx)?.let { return it }
            val f = editable()
            val cur = f?.let { fieldText(it) }.orEmpty()
            val sel = if (f != null && f.textSelectionStart in 0 until f.textSelectionEnd && f.textSelectionEnd <= cur.length)
                cur.substring(f.textSelectionStart, f.textSelectionEnd) else cur
            if (sel.isBlank()) return aboutScreen(ctx, "Summarise the selected or highlighted text on the screen.")
            if (n.startsWith("read")) return Reply(sel.take(1500))
            val s = withContext(Dispatchers.IO) { rewrite(ctx, "Summarise this in two spoken sentences", sel) }
            return Reply(s ?: sel.take(600))
        }

        // ---- tap anything by its name: "tap settings", "click sign in"
        m("^(?:tap|click|press|hit|touch|select|choose)(?: on)?(?: the)? (.+?)(?: button| link| tab| icon| option| menu| item)?$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val what = it.groupValues[1]
            val node = find(what) ?: return Reply("I can't see $what on the screen.")
            val name = label(node).take(60).ifEmpty { what }
            if (Regex("\\b(send|call|pay|buy|purchase|order|delete|remove|post|submit|transfer|confirm|install|uninstall|publish|share)\\b")
                    .containsMatchIn((name + " " + what).lowercase(Locale.ROOT))
            ) {
                pending = {
                    click(node)
                    done("Pressed $name.")
                }
                pendingAt = System.currentTimeMillis()
                return Reply("Press $name? Say yes to confirm.")
            }
            click(node)
            return done("Tapped $name.")
        }

        // ---- reading and understanding the screen
        if (has("^(read|read out|read aloud) (my |the |this )?(screen|page|whole page|entire page|everything)( aloud)?$|^read (it|this)( aloud)?$|^read text aloud$")) {
            need(ctx)?.let { return it }
            away()
            val texts = visibleTexts()
            snapshot = texts.toSet()
            if (texts.isEmpty()) return Reply("I can't find readable text on this screen.")
            chunks = texts.joinToString(". ").chunked(if (has("whole|entire|everything")) 1500 else 500)
            chunkIndex = 0
            return done(chunks[0] + if (chunks.size > 1) " Say read more to continue." else "", keep = true)
        }
        if (has("^read (the )?(next|previous) (paragraph|part|section)$") && chunks.isNotEmpty()) {
            chunkIndex = if (has("next")) (chunkIndex + 1).coerceAtMost(chunks.size - 1) else (chunkIndex - 1).coerceAtLeast(0)
            return Reply(chunks[chunkIndex])
        }
        if (has("what('s| is) on (my |the |this )?screen|describe (this|the|my) (screen|page|app)|what am i looking at|what do you see|explain this (page|screen)|what is this (page|screen|app)")) {
            return aboutScreen(ctx, "Describe what is on this phone screen: the app, the main content and what the user can do here.")
        }
        m("^(summari[sz]e|explain|translate|review|debug|analy[sz]e|fact check|simplify|check)(?: me)? (?:this|the|my) (page|screen|article|website|site|web page|email|message|messages|chat|conversation|error|code|document|post|news|text|paragraph|diagram|chart|graph|table|form|screenshot|notification|warning|problem|question|answer|essay|homework|exercise|result|results)(?: (?:to|into|in) ([a-z]+))?$")?.let {
            val verb = it.groupValues[1]
            val thing = it.groupValues[2]
            val lang = it.groupValues[3]
            val task = when {
                verb.startsWith("translate") -> "Translate the main text of this $thing into ${lang.ifEmpty { "English" }} and read it to me."
                verb.startsWith("summar") -> "Summarise this $thing in a few spoken sentences, with the key points."
                verb == "debug" || thing == "error" || thing == "warning" || thing == "problem" -> "Explain this $thing simply and tell me how to fix it."
                verb == "review" -> "Review this $thing and give me honest, useful feedback."
                thing == "question" || thing == "exercise" || thing == "homework" -> "Help me with this $thing: explain how to solve it step by step, like a patient teacher."
                else -> "${verb.replaceFirstChar { c -> c.uppercase() }} this $thing for me."
            }
            return aboutScreen(ctx, task)
        }
        m("^(?:describe|explain|what is in|what's in|identify (?:the )?(?:objects|colours|colors) in|read (?:the )?text (?:in|on|from)|extract (?:the )?text (?:in|on|from)) (?:this|the|that) (image|photo|picture|screenshot|diagram|chart|graph|drawing|document)$|^(?:extract|read) (?:the )?text$|^identify visible objects$|^identify (?:the )?colou?rs$|^inspect (?:this|the) chart$")?.let {
            val task = when {
                has("extract|read (the )?text") -> "Read out exactly the text visible in this image."
                has("objects") -> "List the objects you can see."
                has("colou?rs") -> "Describe the main colours you can see."
                else -> "Describe and explain this ${it.groupValues[1].ifEmpty { "image" }} clearly."
            }
            return aboutScreen(ctx, task)
        }
        if (has("compare (this|the) screen with the previous( one)?|compare (with|to) (the )?(last|previous) screen")) {
            need(ctx)?.let { return it }
            val before = lastShot ?: return Reply("I haven't looked at a previous screen yet. Ask me what's on the screen first.")
            away()
            val now = screenshot() ?: return Reply("I couldn't take a picture of the screen.")
            lastShot = now
            val a = withContext(Dispatchers.IO) { look(ctx, "The first image is the earlier screen, the second is now. Tell me what changed.", listOf(before, now)) }
            return done(a ?: "I need the online brain to compare screens.")
        }
        if (has("what changed( on (the )?screen)?$|tell me what changed")) {
            need(ctx)?.let { return it }
            away()
            val now = visibleTexts().toSet()
            if (snapshot.isEmpty()) {
                snapshot = now
                return Reply("I've noted this screen. Ask me again after it changes.")
            }
            val added = (now - snapshot).take(6)
            val removed = (snapshot - now).size
            snapshot = now
            return done(
                if (added.isEmpty() && removed == 0) "Nothing has changed."
                else "New on screen: ${added.joinToString("; ").ifEmpty { "nothing new" }}." + if (removed > 0) " $removed items disappeared." else ""
            )
        }
        if (has("^(list|read|what are) (the |all )?(buttons|options|controls|actions|links|tabs|menu items)( on (the|this) screen)?$|identify (the )?available actions|describe (the )?interface controls")) {
            need(ctx)?.let { return it }
            away()
            val links = has("links")
            val list = nodes().filter { node ->
                node.isVisibleToUser && label(node).length in 2..40 &&
                    (if (links) node.isClickable || clickable(node) != null else (node.isClickable || (node.className?.toString() ?: "").contains("Button")))
            }.map { label(it) }.distinct().take(12)
            if (list.isEmpty()) return Reply("I can't find any ${if (links) "links" else "buttons"} here.")
            return done("I can see: ${list.joinToString(", ")}.", keep = true)
        }
        if (has("(identify|find|where is) (the )?search (field|box|bar)")) {
            need(ctx)?.let { return it }
            away()
            val f = nodes().firstOrNull { it.isEditable && it.isVisibleToUser }
                ?: find("search") ?: return Reply("I can't see a search box here.")
            click(f)
            return done("Found the search box and tapped it. Say type, then your words.", keep = true)
        }
        if (has("(identify|what is|what's) (the )?(selected|focused|highlighted) (item|element|thing)|identify (the )?focused element")) {
            need(ctx)?.let { return it }
            away()
            val r = root()
            val f = r?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: r?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: nodes().firstOrNull { it.isSelected || it.isChecked }
            return Reply(if (f == null) "Nothing is selected right now." else "The selected item is ${label(f).ifEmpty { "unlabelled" }}.")
        }
        if (has("(read|what is|what's) (the )?(page|screen|window) title")) {
            need(ctx)?.let { return it }
            away()
            val title = svc()?.windows?.firstOrNull { it.isActive }?.title?.toString()
                ?: visibleTexts().firstOrNull() ?: ""
            return Reply(if (title.isBlank()) "This screen has no title I can read." else "The title is $title.")
        }
        if (has("(identify|which|what) (the )?app( is this| am i (in|using)| is open)?$|identify the app")) {
            need(ctx)?.let { return it }
            away()
            val pkg = root()?.packageName?.toString() ?: AurelisAccess.lastPackage
            return Reply(if (pkg.isBlank()) "I can't tell which app is open." else "This is ${appName(ctx, pkg)}.")
        }
        m("^(?:find|search for|look for|where is|locate)(?: the)? (?:word |text |phrase )(.+?)(?: on (?:the |this )?(?:screen|page))?$|^find (.+) on (?:the |this )?(?:screen|page)$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val q = it.groupValues[1].ifEmpty { it.groupValues[2] }
            repeat(4) { attempt ->
                val node = find(q)
                if (node != null) {
                    node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                    val (_, h) = size()
                    val y = bounds(node).exactCenterY()
                    val where = when {
                        y < h / 3 -> "near the top"
                        y < h * 2 / 3 -> "in the middle"
                        else -> "near the bottom"
                    }
                    return done("Found $q $where of the screen: ${label(node).take(120)}.", keep = true)
                }
                if (attempt < 3) {
                    val (w, h) = size()
                    swipe(w / 2, h * 0.75f, w / 2, h * 0.3f, 300)
                    delay(500)
                }
            }
            return Reply("I couldn't find $q on this page.")
        }
        m("^(?:find|go to|read) (?:the )?(next|previous|first) heading$|^navigate by headings$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            if (Build.VERSION.SDK_INT < 28) return Reply("Headings need Android 9 or newer.")
            val heads = nodes().filter { node -> node.isVisibleToUser && node.isHeading && label(node).isNotBlank() }
            if (heads.isEmpty()) return Reply("This page doesn't mark any headings.")
            headingIndex = when (it.groupValues[1]) {
                "previous" -> (headingIndex - 1).coerceAtLeast(0)
                "first", "" -> 0
                else -> (headingIndex + 1).coerceAtMost(heads.size - 1)
            }
            val h = heads[headingIndex]
            h.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            return done("Heading: ${label(h)}.", keep = true)
        }
        m("^(?:move )?focus (forward|next|backward|back|previous)$|^(next|previous) (?:item|element)$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val list = nodes().filter { node -> node.isVisibleToUser && (node.isFocusable || node.isClickable) && label(node).isNotBlank() }
            if (list.isEmpty()) return Reply("There's nothing to move to.")
            val fwd = it.groupValues[1].ifEmpty { it.groupValues[2] } in setOf("forward", "next")
            focusIndex = if (fwd) (focusIndex + 1) % list.size else if (focusIndex <= 0) list.size - 1 else focusIndex - 1
            val f = list[focusIndex]
            f.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            return done(label(f).take(120), keep = true)
        }
        if (has("^activate (the )?(focused|selected) (element|item)$|^activate (it|this)$")) {
            need(ctx)?.let { return it }
            val f = root()?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: return Reply("Nothing is focused. Say focus next first.")
            click(f)
            return done("Activated.")
        }

        // ---- notifications
        if (has("(read|check|what are|any) (my |the )?(latest |new )?notifications?|read the notification")) {
            if (!NotificationReader.enabled(ctx)) {
                launch(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return Reply("I need notification access first. I've opened the page: choose AURELIS and allow it. If Android says restricted setting, allow restricted settings in AURELIS app info first.", end = true, leftApp = true)
            }
            return Reply(NotificationReader.summary(ctx))
        }
        if (has("(clear|dismiss|delete|remove) (all )?(my |the )?notifications?")) {
            if (NotificationReader.clearAll()) return done("Notifications cleared.")
            need(ctx)?.let { return it }
            global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            delay(900)
            val btn = find("clear all") ?: find("clear") ?: find("dismiss all")
            if (btn != null && click(btn)) return done("Notifications cleared.")
            return done("I've opened your notifications. Tap clear all.")
        }

        // ---- apps
        if (has("^(list|show|what are|read)( me)?( all)?( my| the)? (installed )?apps$|how many apps (do i have|are installed)")) {
            val all = apps(ctx)
            return Reply("You have ${all.size} apps, including ${all.take(15).joinToString(", ") { it.first }}.")
        }
        m("^search (?:my |the )?(?:installed )?apps (?:for )?(.+)$")?.let {
            val q = it.groupValues[1].lowercase(Locale.ROOT)
            val hits = apps(ctx).filter { a -> a.first.lowercase(Locale.ROOT).contains(q) }
            return Reply(if (hits.isEmpty()) "No installed app matches $q." else "Found: ${hits.take(8).joinToString(", ") { a -> a.first }}.")
        }
        m("^open (?:the )?(?:app |package )?((?:[a-z][a-z0-9_]* )+(?:[a-z][a-z0-9_]*)) package$|^open package (.+)$")?.let {
            val pkg = it.groupValues[1].ifEmpty { it.groupValues[2] }.replace(" dot ", ".").replace(" ", ".")
            val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return Reply("There's no app with the package $pkg.")
            return gone(launch(ctx, i), "Opening $pkg.")
        }
        m("^switch to (.+)$")?.let {
            val name = it.groupValues[1].removeSuffix(" app")
            if (!name.contains("camera") && appPackage(ctx, name) != null) return Reply(Device.openApp(ctx, name), end = true, leftApp = true)
        }
        m("^(?:show |open )?(?:the )?app (?:info|information|details|settings|permissions) (?:for|of) (.+)$|^(?:show|open) (.+?)(?:'s| s)? app (?:info|information|details|permissions)$|^clear (?:the )?(.+?)(?:'s| s)? cache$|^clear (?:the )?cache (?:of|for) (.+)$")?.let {
            val name = it.groupValues.drop(1).first { g -> g.isNotEmpty() }
            val pkg = appPackage(ctx, name) ?: return Reply("I couldn't find an app called $name.")
            val ok = launch(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            return gone(ok, if (has("cache")) "Here is ${appName(ctx, pkg)}'s info. Tap storage, then clear cache." else "Here is ${appName(ctx, pkg)}'s app info.")
        }
        m("^uninstall (?:the )?(.+?)(?: app)?$|^(?:remove|delete) (?:the )?(.+?) app$")?.let {
            val name = it.groupValues[1].ifEmpty { it.groupValues[2] }
            val pkg = appPackage(ctx, name) ?: return Reply("I couldn't find an app called $name.")
            if (pkg == ctx.packageName) return Reply("You can uninstall me from Android settings if you ever need to.")
            val ok = launch(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
            return gone(ok, "Android will ask you to confirm uninstalling ${appName(ctx, pkg)}.")
        }

        // ---- camera
        if (has("^(take|snap|capture) (a |the )?(photo|picture|pic|selfie)( now)?$|^take it$|^say cheese$")) {
            val selfie = has("selfie")
            val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            if (selfie) i.putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            if (!launch(ctx, i)) return Reply("I couldn't open the camera.")
            if (svc() == null) return done("Camera open. Tap the shutter button. Turn on screen control and I'll press it for you next time.")
            delay(2800)
            val shutter = nodes().firstOrNull { node ->
                val l = (node.contentDescription?.toString() ?: "").lowercase(Locale.ROOT) + " " + (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                node.isVisibleToUser && Regex("shutter|take (a )?(photo|picture)|capture|camera_button|photo_button").containsMatchIn(l)
            }
            if (shutter != null) click(shutter) else {
                val (w, h) = size()
                tapAt(w / 2, h * 0.87f)
            }
            return done("Photo taken.")
        }
        if (has("^(switch|flip|change|turn) (the )?camera( around)?$|^(use|switch to) (the )?(front|back|rear) camera$")) {
            need(ctx)?.let { return it }
            val node = nodes().firstOrNull { node ->
                val l = (node.contentDescription?.toString() ?: "").lowercase(Locale.ROOT) + " " + (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                node.isVisibleToUser && Regex("switch|flip|front|rear|toggle_camera|camera_switch").containsMatchIn(l)
            } ?: return Reply("Open the camera first, then ask me to switch it.")
            click(node)
            return done("Switched camera.")
        }
        if (has("scan (a |the |this )?(document|page|receipt)")) {
            if (!launch(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) return Reply("I couldn't open the camera.")
            return done("Camera open. Point it at the page, then say take a photo, and after that say read the text in this image.")
        }
        if (has("(find|show|open) (me )?(my |the )?(latest|last|newest|most recent) (photo|picture|image|screenshot|video)")) {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val video = has("video")
            val shotOnly = has("screenshot")
            val found = withContext(Dispatchers.IO) { scan(if (shotOnly) "screenshot" else null, typeOf(if (video) "video" else "image"), 1) }
            val f = found.firstOrNull() ?: return Reply("I couldn't find any.")
            results = found
            return gone(openFile(ctx, f), "Here's your latest ${if (video) "video" else if (shotOnly) "screenshot" else "photo"}.")
        }
        if (has("^(describe|explain|what is in|what's in) (my |the )?(latest|last) (photo|picture|screenshot)$")) {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val f = withContext(Dispatchers.IO) { scan(if (has("screenshot")) "screenshot" else null, typeOf("image"), 1) }.firstOrNull()
                ?: return Reply("I couldn't find a photo.")
            val img = withContext(Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })?.let { jpeg(it) }
            } ?: return Reply("I couldn't open that photo.")
            val a = withContext(Dispatchers.IO) { look(ctx, "Describe this photo clearly.", listOf(img)) }
            return Reply(a ?: "I need the internet and the online brain to look at photos.")
        }

        // ---- files and documents
        m("^(?:find|search for|look for|show|list|search)(?: me)?(?: my| the| a| an| all)? (pdfs?|word documents?|documents?|docs|spreadsheets?|presentations?|images?|photos?|pictures?|videos?|songs?|music|audio|text files?|archives?|zip files?|apks?)(?: files?)?(?: (?:called|named|about|with|containing) (.+))?$|^search (?:my |the )?files (?:for )?(.+)$|^find (?:the )?file (?:called |named )?(.+)$")?.let {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val kind = it.groupValues[1].replace("docs", "document").replace("zip files", "archive").replace("zip file", "archive")
                .replace(" documents", "").replace(" document", "").replace(" files", "").replace(" file", "").trim()
            val name = it.groupValues[2].ifEmpty { it.groupValues[3].ifEmpty { it.groupValues[4] } }.ifEmpty { null }
            val exts = if (kind.isEmpty()) null else typeOf(kind.split(" ").first())
            val found = withContext(Dispatchers.IO) { scan(name, exts) }
            results = found
            if (found.isEmpty()) return Reply("I couldn't find ${if (name != null) "anything called $name" else "any ${kind.ifEmpty { "files" }}"}.")
            return Reply("I found ${found.size}${if (found.size >= 40) " or more" else ""}, newest first: ${spoken(found)}. Say open the first file, share it, or summarise it.")
        }
        m("^open (?:the )?(first|second|third|fourth|fifth|last|latest|newest) (?:file|document|pdf|result|one)$|^open (?:the )?file (.+)$|^open (?:my |the )?(latest|last|newest|most recent) (?:file|download|document|pdf)$")?.let {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            if (it.groupValues[3].isNotEmpty() || (it.groupValues[1] in setOf("latest", "newest") && results.isEmpty())) {
                val docs = has("pdf") && !has("file|download|document")
                results = withContext(Dispatchers.IO) {
                    if (has("download")) File(storage(), Environment.DIRECTORY_DOWNLOADS).listFiles()?.filter { f -> f.isFile }?.sortedByDescending { f -> f.lastModified() } ?: emptyList()
                    else scan(null, if (docs) typeOf("pdf") else null, 5)
                }
            }
            val ref = if (it.groupValues[1] in setOf("latest", "newest") || it.groupValues[3].isNotEmpty()) "first" else it.groupValues[1].ifEmpty { it.groupValues[2] }
            val f = withContext(Dispatchers.IO) { pick(ref) } ?: return Reply("Find the file first: say, for example, find my PDFs.")
            return gone(openFile(ctx, f), "Opening ${f.nameWithoutExtension}.")
        }
        m("^(?:summari[sz]e|read|explain|extract (?:the )?(?:text|key points|important points) (?:from|of)|search inside|quiz me on|make (?:a )?(?:quiz|notes|flashcards) (?:from|on)) (?:the |this |that |my )?(first |second |third |latest |last )?(pdf|document|file|text file)(?: (?:called|named) (.+?))?(?: for (.+))?$")?.let {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val ref = it.groupValues[3].ifEmpty { it.groupValues[1].trim().ifEmpty { "first" } }
            val f = withContext(Dispatchers.IO) {
                if (results.isEmpty() && it.groupValues[3].isEmpty()) results = scan(null, if (it.groupValues[2] == "pdf") typeOf("pdf") else typeOf("document"), 5)
                pick(ref)
            } ?: return Reply("Find the document first: say, for example, find my PDFs.")
            val verb = n.substringBefore(" ")
            val task = when {
                n.startsWith("read") -> "Read this document aloud, word for word, as far as you can."
                n.startsWith("search inside") -> "Find where this document mentions ${it.groupValues[4]} and tell me what it says."
                n.startsWith("quiz") || has("quiz") -> "Make a five-question quiz from this document. Ask me the first question only and wait for my answer."
                has("notes|flashcards") -> "Make short revision notes from this document."
                has("key points|important points") -> "Give me the key points of this document."
                verb.startsWith("explain") -> "Explain this document simply."
                else -> "Summarise this document in a few spoken sentences."
            }
            val answer = withContext(Dispatchers.IO) {
                when (f.extension.lowercase(Locale.ROOT)) {
                    "pdf" -> look(ctx, task, pdfPages(f))
                    "txt", "md", "csv", "json", "xml", "html", "kt", "py", "js" -> {
                        val text = f.readText().take(20000)
                        if (online(ctx)) look(ctx, task, emptyList(), text) else text.take(1500)
                    }
                    else -> null
                }
            } ?: return Reply(
                if (!online(ctx)) "I need the internet to read documents for you." else "I can read PDFs and text files. For ${f.extension} files, open it and ask me to read the screen."
            )
            return Reply(answer)
        }
        m("^rename (?:the )?(.+?) to (.+)$")?.let {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val f = withContext(Dispatchers.IO) { pick(it.groupValues[1].removeSuffix(" file")) } ?: return Reply("I couldn't find ${it.groupValues[1]}.")
            val newName = it.groupValues[2].trim().let { s -> if (s.contains(".")) s else s + (if (f.extension.isNotEmpty()) "." + f.extension else "") }
            val dest = File(f.parentFile, newName)
            pending = {
                val ok = withContext(Dispatchers.IO) { f.renameTo(dest) }
                if (ok) results = listOf(dest)
                Reply(if (ok) "Renamed to $newName." else "I couldn't rename it.")
            }
            pendingAt = System.currentTimeMillis()
            return Reply("Rename ${f.name} to $newName? Say yes to confirm.")
        }
        m("^(?:create|make|new) (?:a )?(?:new )?folder (?:called |named )?(.+?)(?: in (downloads|documents|pictures|music|movies))?$")?.let {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val parent = if (it.groupValues[2].isNotEmpty()) folder(it.groupValues[2]) else File(storage(), Environment.DIRECTORY_DOCUMENTS)
            val dir = File(parent, it.groupValues[1].trim())
            val ok = withContext(Dispatchers.IO) { dir.exists() || dir.mkdirs() }
            return Reply(if (ok) "Folder ${dir.name} created in ${parent.name}." else "I couldn't create that folder.")
        }
        m("^(?:create|make|save) (?:a )?(?:new )?(?:text file|note file|txt file) (?:called |named )?(.+?)(?: (?:saying|with|that says|containing) (.+))?$")?.let {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val name = it.groupValues[1].trim().removeSuffix(".txt")
            val body = rawAfter("(?:saying|with|that says|containing)\\s+(.+)$") ?: ""
            val f = File(File(storage(), Environment.DIRECTORY_DOCUMENTS), "$name.txt")
            val ok = withContext(Dispatchers.IO) {
                try {
                    f.parentFile?.mkdirs()
                    f.writeText(body)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) results = listOf(f)
            return Reply(if (ok) "Saved $name in Documents." else "I couldn't save the file.")
        }
        m("^(move|copy) (?:the )?(.+?) to (?:the |my )?(downloads|documents|pictures|music|movies|videos|camera|[a-z0-9 ]+ folder)$")?.let {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val f = withContext(Dispatchers.IO) { pick(it.groupValues[2].removeSuffix(" file")) } ?: return Reply("I couldn't find ${it.groupValues[2]}.")
            val target = folder(it.groupValues[3].removeSuffix(" folder"))
            val move = it.groupValues[1] == "move"
            pending = {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        target.mkdirs()
                        val dest = File(target, f.name)
                        f.copyTo(dest, overwrite = false)
                        if (move) f.delete()
                        results = listOf(dest)
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                Reply(if (ok) "${if (move) "Moved" else "Copied"} to ${target.name}." else "I couldn't do that. A file with the same name may already be there.")
            }
            pendingAt = System.currentTimeMillis()
            return Reply("${if (move) "Move" else "Copy"} ${f.name} to ${target.name}? Say yes to confirm.")
        }
        m("^(?:delete|remove|erase) (?:the )?(first|second|third|last|latest|selected)? ?(?:file|document|photo|video|pdf)(?: called (.+))?$|^delete (?:the )?file (.+)$")?.let {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val ref = it.groupValues[2].ifEmpty { it.groupValues[3].ifEmpty { it.groupValues[1].ifEmpty { "first" }.replace("selected", "first").replace("latest", "first") } }
            val f = withContext(Dispatchers.IO) { pick(ref) } ?: return Reply("Find the file first, then ask me to delete it.")
            pending = {
                val ok = withContext(Dispatchers.IO) { f.delete() }
                if (ok) results = results - f
                Reply(if (ok) "Deleted ${f.name}." else "I couldn't delete it.")
            }
            pendingAt = System.currentTimeMillis()
            return Reply("Permanently delete ${f.name}? This can't be undone. Say yes to confirm.")
        }
        if (has("^share (it|this file|that file|the file|the first file|these files|them)$|^share (a |the )?(selected )?file$")) {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            if (results.isEmpty()) return Reply("Find the file first, then ask me to share it.")
            val list = if (has("these|them")) results.take(10) else listOf(results[0])
            return gone(shareFiles(ctx, list), "Choose where to share it.")
        }
        if (has("^(show |read |what are )?(the )?file (details|info)$|^inspect (the )?file$|how (big|large) is (the |that |this )?file")) {
            val f = results.firstOrNull() ?: return Reply("Find a file first.")
            return Reply(
                "${f.name}: ${sizeText(f.length())}, last changed ${SimpleDateFormat("d MMMM yyyy, h:mm a", Locale.getDefault()).format(Date(f.lastModified()))}, in ${f.parentFile?.name}."
            )
        }
        if (has("^(compress|zip)( the| these| selected| my)? (files|results|them)$")) {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            if (results.isEmpty()) return Reply("Find the files first.")
            val dest = File(File(storage(), Environment.DIRECTORY_DOCUMENTS), "AURELIS-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".zip")
            val list = results.take(30)
            val ok = withContext(Dispatchers.IO) {
                try {
                    zip(list, dest)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) results = listOf(dest)
            return Reply(if (ok) "Compressed ${list.size} files into ${dest.name} in Documents." else "I couldn't compress them.")
        }
        m("^(?:extract|unzip|open) (?:the )?(?:archive|zip|zip file)(?: (.+))?$")?.let {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val f = withContext(Dispatchers.IO) {
                if (it.groupValues[1].isNotEmpty()) pick(it.groupValues[1]) else results.firstOrNull { r -> r.extension == "zip" } ?: scan(null, setOf("zip"), 1).firstOrNull()
            } ?: return Reply("I couldn't find a zip file.")
            val dest = File(f.parentFile, f.nameWithoutExtension)
            val count = withContext(Dispatchers.IO) {
                try {
                    unzip(f, dest)
                } catch (e: Exception) {
                    -1
                }
            }
            return Reply(if (count >= 0) "Extracted $count files into ${dest.name}." else "I can only extract zip files.")
        }
        if (has("(sort|list|show) (my )?(files|downloads) by (date|name|size)|what('s| is) in (my )?downloads")) {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val list = withContext(Dispatchers.IO) { File(storage(), Environment.DIRECTORY_DOWNLOADS).listFiles()?.filter { it.isFile } ?: emptyList() }
            val sorted = when {
                has("name") -> list.sortedBy { it.name.lowercase(Locale.ROOT) }
                has("size") -> list.sortedByDescending { it.length() }
                else -> list.sortedByDescending { it.lastModified() }
            }
            results = sorted
            if (sorted.isEmpty()) return Reply("Your Downloads folder is empty.")
            return Reply("You have ${sorted.size} files in Downloads. ${spoken(sorted)}.")
        }
        if (has("(find|show|any) duplicate files|find duplicates")) {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val groups = withContext(Dispatchers.IO) {
                val files = File(storage(), Environment.DIRECTORY_DOWNLOADS).walkTopDown().maxDepth(3).filter { it.isFile && it.length() in 1..80_000_000 }.toList()
                files.groupBy { it.length() }.values.filter { it.size > 1 }
                    .flatMap { same -> same.groupBy { hash(it) }.values.filter { it.size > 1 } }
            }
            if (groups.isEmpty()) return Reply("No duplicate files in Downloads.")
            val extra = groups.flatMap { it.drop(1) }
            results = extra
            return Reply(
                "I found ${groups.size} files with copies, ${extra.size} spare copies in all, using ${sizeText(extra.sumOf { it.length() })}. " +
                    "For example ${groups[0][0].name}. Say delete duplicates to remove the spare copies."
            )
        }
        if (has("^delete (the )?duplicates$") && results.isNotEmpty()) {
            val list = results
            pending = {
                val count = withContext(Dispatchers.IO) { list.count { it.delete() } }
                results = emptyList()
                Reply("Deleted $count duplicate files.")
            }
            pendingAt = System.currentTimeMillis()
            return Reply("Permanently delete ${list.size} spare copies? Say yes to confirm.")
        }
        if (has("(organi[sz]e|tidy|clean up|sort out) (my )?downloads")) {
            if (!filesAllowed(ctx)) return askFiles(ctx)
            val dl = File(storage(), Environment.DIRECTORY_DOWNLOADS)
            val list = withContext(Dispatchers.IO) { dl.listFiles()?.filter { it.isFile } ?: emptyList() }
            if (list.isEmpty()) return Reply("Downloads is already empty.")
            pending = {
                val moved = withContext(Dispatchers.IO) {
                    var c = 0
                    for (f in list) {
                        val e = f.extension.lowercase(Locale.ROOT)
                        val sub = when {
                            e in typeOf("image")!! -> "Images"
                            e in typeOf("video")!! -> "Videos"
                            e in typeOf("audio")!! -> "Music"
                            e in typeOf("document")!! -> "Documents"
                            e in typeOf("archive")!! -> "Archives"
                            e == "apk" -> "Apps"
                            else -> "Other"
                        }
                        val dir = File(dl, sub)
                        dir.mkdirs()
                        if (f.renameTo(File(dir, f.name))) c++
                    }
                    c
                }
                Reply("Done. I sorted $moved files into folders by type inside Downloads.")
            }
            pendingAt = System.currentTimeMillis()
            return Reply("Sort ${list.size} files in Downloads into folders like Images, Documents and Videos? Say yes to confirm.")
        }
        if (has("^(open|show) (my )?(files|file manager|storage)$")) {
            val ok = (Build.VERSION.SDK_INT >= 29 && launch(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, "android.intent.category.APP_FILES"))) ||
                launch(ctx, Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
            return gone(ok, "Opening your files.")
        }

        // ---- contacts
        m("^(?:open|show|find|search for|look up)(?: the)? contact (?:for |called |named )?(.+)$|^(?:edit|update|change) (?:the )?contact (?:for |called )?(.+)$|^search (?:my )?contacts (?:for )?(.+)$")?.let {
            if (!granted(ctx, Manifest.permission.READ_CONTACTS)) return Reply("Please allow Contacts for AURELIS in the setup screen first.")
            val name = it.groupValues.drop(1).first { g -> g.isNotEmpty() }
            val found = withContext(Dispatchers.IO) {
                ctx.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME),
                    ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?", arrayOf("%$name%"), null
                )?.use { c -> if (c.moveToFirst()) Triple(c.getLong(0), c.getString(1), c.getString(2) ?: name) else null }
            } ?: return Reply("I couldn't find $name in your contacts.")
            val uri = ContactsContract.Contacts.getLookupUri(found.first, found.second)
            val edit = it.groupValues[2].isNotEmpty()
            return gone(launch(ctx, Intent(if (edit) Intent.ACTION_EDIT else Intent.ACTION_VIEW, uri)), if (edit) "Editing ${found.third}. Save when you're done." else "Here's ${found.third}.")
        }
        m("^(?:save|add) (?:this |the )?number ([0-9 +]{7,})(?: as (.+))?$")?.let {
            val i = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                .putExtra(ContactsContract.Intents.Insert.PHONE, it.groupValues[1].replace(" ", ""))
            if (it.groupValues[2].isNotEmpty()) i.putExtra(ContactsContract.Intents.Insert.NAME, it.groupValues[2])
            return gone(launch(ctx, i), "I've filled it in. Tap save.")
        }
        m("^search (?:my )?(?:email|emails|gmail|inbox|mail) (?:for )?(.+)$")?.let {
            val ok = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com/mail/u/0/#search/" + Uri.encode(it.groupValues[1]))))
            return gone(ok, "Searching your email for ${it.groupValues[1]}.")
        }
        m("^(reply|forward)(?: to)?(?: this| the)?(?: email| message)?$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val node = find(it.groupValues[1]) ?: return Reply("I can't see a ${it.groupValues[1]} button. Open the email first.")
            click(node)
            return done("${it.groupValues[1].replaceFirstChar { c -> c.uppercase() }} opened. Say type and your message, then I'll wait for you to press send.", keep = true)
        }
        m("^(?:search|find) (?:my )?(?:conversations|chats|messages) (?:for |with )?(.+)$")?.let {
            need(ctx)?.let { r -> return r }
            away()
            val s = find("search") ?: return Reply("Open WhatsApp or Messages first, then ask me again.")
            click(s)
            delay(700)
            val f = editable() ?: return Reply("I opened search. Say type and the name.")
            setText(f, it.groupValues[1])
            return done("Searching for ${it.groupValues[1]}.", keep = true)
        }

        // ---- timers that only the Clock app can pause
        m("^(pause|resume|restart|stop) (?:the |my )?timer$")?.let {
            launch(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS))
            if (svc() == null) return done("Here are your timers. Tap ${it.groupValues[1]}.")
            delay(1500)
            val word = when (it.groupValues[1]) { "resume" -> "start"; "restart" -> "reset"; else -> it.groupValues[1] }
            val node = find(word) ?: (if (word == "start") find("resume") else null)
            if (node != null && click(node)) {
                return done(
                    when (it.groupValues[1]) {
                        "pause" -> "Timer paused."
                        "resume" -> "Timer resumed."
                        "restart" -> "Timer reset."
                        else -> "Timer stopped."
                    }
                )
            }
            return done("Here are your timers. Tap ${it.groupValues[1]}.")
        }
        if (has("^(stop|end|exit|cancel) (the )?navigation$")) {
            need(ctx)?.let { return it }
            val node = find("exit navigation") ?: find("exit") ?: find("close navigation") ?: find("end")
            if (node != null && click(node)) return done("Navigation stopped.")
            global(AccessibilityService.GLOBAL_ACTION_BACK)
            return done("Navigation closed.")
        }

        // ---- money
        md("^(?:convert |how much is |what is |what's )?(\\d+(?:\\.\\d+)?) ([a-z]+(?: [a-z]+)?) (?:to|in|into) ([a-z]+(?: [a-z]+)?)$")?.let {
            val from = CURRENCY[it.groupValues[2]] ?: return@let
            val to = CURRENCY[it.groupValues[3]] ?: return@let
            if (!AppState.online.value) return Reply("I need the internet for current exchange rates.")
            val json = withContext(Dispatchers.IO) { getJson("https://open.er-api.com/v6/latest/$from") }
                ?: return Reply("I couldn't get exchange rates right now.")
            val rate = try {
                JSONObject(json).getJSONObject("rates").getDouble(to)
            } catch (e: Exception) {
                return Reply("I don't have a rate for that currency.")
            }
            val amount = it.groupValues[1].toDouble()
            return Reply(String.format(Locale.ROOT, "%,.2f %s is about %,.2f %s at today's rate.", amount, from, amount * rate, to))
        }

        // ---- GitHub
        if (has("(check|what('s| is)) (the )?(build|workflow|action)s? status|did the (last )?build (pass|work|succeed|fail)|inspect (my |the )?(latest )?(build|workflow runs?)|explain (the )?failed workflow")) {
            if (!AppState.online.value) return Reply("I need the internet to check GitHub.")
            val json = withContext(Dispatchers.IO) { getJson("https://api.github.com/repos/$REPO/actions/runs?per_page=1") }
                ?: return Reply("I couldn't reach GitHub just now.")
            return try {
                val run = JSONObject(json).getJSONArray("workflow_runs").getJSONObject(0)
                val status = run.optString("status")
                val result = run.optString("conclusion")
                Reply(
                    "Run ${run.optInt("run_number")}, ${run.optString("display_title").take(80)}: " +
                        if (status != "completed") "still running." else if (result == "success") "it passed." else "it ended with $result. Open the Actions page on GitHub to see the failing step."
                )
            } catch (e: Exception) {
                Reply("I couldn't read the build status.")
            }
        }
        if (has("(show|list|read) (the )?(recent |latest |last )?commits?( history)?|show commit history")) {
            if (!AppState.online.value) return Reply("I need the internet to check GitHub.")
            val json = withContext(Dispatchers.IO) { getJson("https://api.github.com/repos/$REPO/commits?per_page=3") }
                ?: return Reply("I couldn't reach GitHub just now.")
            return try {
                val arr = JSONArray(json)
                val msgs = (0 until arr.length()).map { i -> arr.getJSONObject(i).getJSONObject("commit").getString("message").lines().first() }
                Reply("The latest changes: ${msgs.joinToString("; ")}.")
            } catch (e: Exception) {
                Reply("I couldn't read the commit history.")
            }
        }
        if (has("(show|list|search|any) (the |my |open )?(issues|pull requests|prs)")) {
            if (!AppState.online.value) return Reply("I need the internet to check GitHub.")
            val prs = has("pull|prs")
            val json = withContext(Dispatchers.IO) { getJson("https://api.github.com/repos/$REPO/${if (prs) "pulls" else "issues"}?state=open&per_page=5") }
                ?: return Reply("I couldn't reach GitHub just now.")
            val arr = try {
                JSONArray(json)
            } catch (e: Exception) {
                JSONArray()
            }
            if (arr.length() == 0) return Reply("There are no open ${if (prs) "pull requests" else "issues"}.")
            val titles = (0 until arr.length()).map { i -> arr.getJSONObject(i).optString("title") }
            return Reply("Open ${if (prs) "pull requests" else "issues"}: ${titles.joinToString("; ")}.")
        }

        // ---- workflows: YouTube or Google search, then open a result
        m("^(?:open youtube(?:,| and)? )?(?:search|look)(?: youtube)?(?: for)? (.+?)(?:,| and)(?: then)? (?:open|play|tap) the (first|second|third|fourth|fifth|last) (?:result|video|one)(?: on youtube)?$")?.let {
            val q = it.groupValues[1].removePrefix("youtube for ").removeSuffix(" on youtube")
            val yt = has("youtube")
            val ok = if (yt) {
                launch(ctx, Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(android.app.SearchManager.QUERY, q)) ||
                    launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
            } else {
                launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            }
            if (!ok) return Reply("I couldn't start the search.")
            if (svc() == null) return done("Here are the results for $q. Turn on screen control and I'll open the result for you.")
            delay(4500)
            val list = items()
            val k = ordinal(it.groupValues[2]) ?: 1
            val target = (if (k == -1) list.lastOrNull() else list.getOrNull(k - 1)) ?: return done("Here are the results for $q.")
            click(target)
            return done("Opening ${label(target).take(60)}.")
        }

        // ---- prepare the phone
        if (has("prepare (my )?phone for (studying|study|work|focus)|(start|enable) (a )?focus( mode| setup)?|study mode")) {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val dndOk = nm.isNotificationPolicyAccessGranted
            if (dndOk) nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            return Reply(
                (if (dndOk) "Do Not Disturb is on. " else "") + "Focus time. Say set a timer for 25 minutes to start a study session, and I'll keep quiet until then." +
                    if (!dndOk) " Allow Do Not Disturb access in the setup screen and I'll silence notifications too." else ""
            )
        }

        return null
    }
}
