package com.aurelis.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A command AURELIS understands. */
sealed class Cmd {
    object Time : Cmd()
    object DateToday : Cmd()
    object Battery : Cmd()
    data class Torch(val on: Boolean) : Cmd()
    data class Remember(val content: String, val category: String) : Cmd()
    object Recall : Cmd()
    data class Forget(val query: String) : Cmd()
    data class Remind(val text: String, val at: Long) : Cmd()
    data class AddTask(val title: String) : Cmd()
    object ListTasks : Cmd()
    data class CompleteTask(val query: String) : Cmd()
    object ListReminders : Cmd()
    object ReadNotifications : Cmd()
    data class OpenApp(val name: String) : Cmd()
    data class WebSearch(val query: String) : Cmd()
    data class SetAlarm(val hour: Int, val minute: Int) : Cmd()
    data class Dial(val number: String) : Cmd()
    data class Sms(val number: String, val message: String) : Cmd()
    object ReadClipboard : Cmd()
    object Status : Cmd()
    data class Volume(val action: String, val percent: Int?) : Cmd()
    object ShowAlarms : Cmd()
    data class DeleteReminder(val query: String) : Cmd()
    object ClearReminders : Cmd()
    data class Teach(val trigger: String, val meaning: String) : Cmd()
    object ScreenControl : Cmd()
    object StopListening : Cmd()
    object Goodbye : Cmd()

    /** A plain-words command (from the cloud brain) to run through the local command handlers. */
    data class Phrase(val text: String) : Cmd()
}

data class Reply(
    val text: String,
    val end: Boolean = false,
    val stopListening: Boolean = false,
    val leftApp: Boolean = false
)

/** Rule-based understanding: fast, deterministic and fully offline. */
object Intents {
    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)
    private val GOODBYES = setOf(
        "goodbye", "bye", "good bye", "goodbye aurelis", "bye aurelis", "that's all", "thats all",
        "that will be all", "thank you that's all", "no thanks", "nothing", "never mind", "nevermind",
        "dismiss", "cancel", "stop"
    )
    private const val DUR = "(half an hour|(?:\\d+|a|an) (?:seconds?|minutes?|hours?|days?))"
    private const val AT = "(\\d{1,2})(?: (\\d{2}))? ?(am|pm|a m|p m)?"

    fun norm(s: String) = s.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9' ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun digits(s: String): String {
        val w = s.split(" ")
        val out = ArrayList<String>()
        var i = 0
        while (i < w.size) {
            val t = w[i]
            val tens = TENS[t]
            val unit = UNITS[t]
            if (tens != null) {
                var v = tens
                val next = if (i + 1 < w.size) UNITS[w[i + 1]] else null
                if (next != null && next in 1..9) {
                    v += next
                    i++
                }
                out.add(v.toString())
            } else if (unit != null) {
                out.add(unit.toString())
            } else {
                out.add(t)
            }
            i++
        }
        return out.joinToString(" ")
    }

    private fun durationMs(d: String): Long {
        if (d == "half an hour") return 30 * 60_000L
        val parts = d.split(" ")
        val n = parts[0].toLongOrNull() ?: 1L
        val unit = parts.getOrElse(1) { "minutes" }
        return when {
            unit.startsWith("second") -> n * 1000L
            unit.startsWith("hour") -> n * 3_600_000L
            unit.startsWith("day") -> n * 86_400_000L
            else -> n * 60_000L
        }
    }

    fun clockMs(hour: Int, minute: Int, ampm: String?): Long {
        var h = hour
        val ap = ampm?.replace(" ", "")
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h % 24)
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.before(now) && ap == null && h < 12) c.add(Calendar.HOUR_OF_DAY, 12)
        if (c.before(now)) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun category(content: String): String {
        val c = content.lowercase(Locale.ROOT)
        return when {
            Regex("favou?rite|i like|i love|i prefer|i hate|i don't like|call me").containsMatchIn(c) -> MemoryCategory.PREFERENCES
            c.contains("project") -> MemoryCategory.PROJECTS
            Regex("learn|studying|lesson|course").containsMatchIn(c) -> MemoryCategory.LEARNING
            else -> MemoryCategory.IMPORTANT
        }
    }

    fun parse(raw: String): Cmd? {
        val n = norm(raw)
        if (n.isEmpty()) return null
        val d = digits(n)

        if (n in GOODBYES) return Cmd.Goodbye
        if (Regex("^(stop|pause) listening|^go to sleep|^stand down").containsMatchIn(n)) return Cmd.StopListening

        // Teaching a shortcut
        Regex("^(?:when|whenever|if) i say (.+?) (?:i mean|it means|you should|then) (.+)$").find(n)?.let {
            return Cmd.Teach(it.groupValues[1], it.groupValues[2])
        }

        // Volume
        if (Regex("\\bvolume\\b|\\blouder\\b|\\bquieter\\b|^mute\\b|^unmute\\b").containsMatchIn(n)) {
            val pct = Regex("\\b(\\d{1,3})\\b").find(d)?.groupValues?.get(1)?.toIntOrNull()
            return when {
                n.contains("unmute") -> Cmd.Volume("unmute", null)
                Regex("\\bmute\\b|silent").containsMatchIn(n) -> Cmd.Volume("mute", null)
                Regex("\\bmax|\\bfull\\b|highest").containsMatchIn(n) -> Cmd.Volume("max", null)
                pct != null -> Cmd.Volume("set", pct.coerceIn(0, 100))
                Regex("\\bup\\b|increase|raise|louder|higher|more").containsMatchIn(n) -> Cmd.Volume("up", null)
                Regex("down|decrease|lower|reduce|quieter|less").containsMatchIn(n) -> Cmd.Volume("down", null)
                else -> Cmd.Volume("status", null)
            }
        }

        // Alarms in the Clock app, and other apps' screens
        if (Regex("(show|open|see|view|browse|list|check|read|delete|remove|cancel|edit|change|what are) (?:me )?(?:all )?(?:my |the |an? )?alarms?\\b|^my alarms$").containsMatchIn(n)) return Cmd.ShowAlarms
        if (Regex("^scroll (up|down)|^swipe |^go back$").containsMatchIn(n)) return Cmd.ScreenControl

        // Reminders
        if (Regex("^(?:delete|cancel|clear|remove) all (?:my |the )?reminders$").containsMatchIn(n)) return Cmd.ClearReminders
        Regex("^(?:delete|cancel|remove) (?:my |the )?reminder (?:to |about |for )?(.+)$").find(n)?.let {
            return Cmd.DeleteReminder(it.groupValues[1])
        }
        Regex("^(?:please )?(?:remind me|set a reminder|reminder)(?: to)? (.+?) in $DUR$").find(d)?.let {
            return Cmd.Remind(it.groupValues[1], System.currentTimeMillis() + durationMs(it.groupValues[2]))
        }
        Regex("^(?:please )?(?:remind me|set a reminder) in $DUR (?:to )?(.+)$").find(d)?.let {
            return Cmd.Remind(it.groupValues[2], System.currentTimeMillis() + durationMs(it.groupValues[1]))
        }
        Regex("^(?:please )?(?:remind me|set a reminder)(?: to)? (.+?) at $AT$").find(d)?.let {
            val g = it.groupValues
            return Cmd.Remind(g[1], clockMs(g[2].toInt(), g[3].toIntOrNull() ?: 0, g[4].ifEmpty { null }))
        }
        Regex("^(?:please )?(?:remind me|set a reminder) at $AT (?:to )?(.+)$").find(d)?.let {
            val g = it.groupValues
            return Cmd.Remind(g[4], clockMs(g[1].toInt(), g[2].toIntOrNull() ?: 0, g[3].ifEmpty { null }))
        }
        if (Regex("^(what are |list |read )?my reminders|^what reminders").containsMatchIn(n)) return Cmd.ListReminders

        // Memory
        Regex("^\\s*(?:please\\s+)?remember\\s+(?:that\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(raw.trim())?.let {
            val content = it.groupValues[1].trim().trimEnd('.')
            return Cmd.Remember(content, category(content))
        }
        if (Regex("what do you (remember|know about me)|^my memories|^what have you remembered").containsMatchIn(n)) return Cmd.Recall
        Regex("^(?:please )?forget (?:about |that )?(.+)$").find(n)?.let { return Cmd.Forget(it.groupValues[1]) }

        // Tasks
        Regex("^(?:add|create) (?:a )?(?:new )?task (?:to )?(.+)$").find(n)?.let { return Cmd.AddTask(it.groupValues[1]) }
        Regex("^add (.+?) to (?:my )?(?:task list|tasks|to do list|todo list|to do)$").find(n)?.let { return Cmd.AddTask(it.groupValues[1]) }
        if (Regex("^(what are |list |read |show )?my (tasks|to do list|todo list)|^what's on my (to do|todo) list").containsMatchIn(n)) return Cmd.ListTasks
        Regex("^(?:mark|complete|finish|tick off) (?:the )?(?:task )?(.+?)(?: as)?(?: done| complete| completed)?$").find(n)?.let {
            return Cmd.CompleteTask(it.groupValues[1])
        }

        // Device + info
        if (Regex("what time|the time|time is it").containsMatchIn(n)) return Cmd.Time
        if (Regex("what's the date|what is the date|today's date|what day is it|what's today").containsMatchIn(n)) return Cmd.DateToday
        if (n.contains("battery")) return Cmd.Battery
        if (Regex("torch|flashlight|flash light").containsMatchIn(n)) {
            if (Regex("\\boff\\b|turn it off|disable").containsMatchIn(n)) return Cmd.Torch(false)
            if (Regex("\\bon\\b|turn on|enable").containsMatchIn(n)) return Cmd.Torch(true)
        }
        if (n.contains("notifications")) return Cmd.ReadNotifications
        if (n.contains("clipboard")) return Cmd.ReadClipboard
        if (Regex("are you online|system status|status report|which mode").containsMatchIn(n)) return Cmd.Status
        Regex("^(?:set (?:an )?alarm|wake me up) (?:for |at )?$AT").find(d)?.let {
            val g = it.groupValues
            var h = g[1].toInt()
            val ap = g[3].replace(" ", "")
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            return Cmd.SetAlarm(h, g[2].toIntOrNull() ?: 0)
        }
        Regex("^(?:call|dial) ([0-9 ]{3,})$").find(d)?.let { return Cmd.Dial(it.groupValues[1].replace(" ", "")) }
        Regex("^(?:open|launch|start) (?:the )?(.+?)(?: app| application)?$").find(n)?.let {
            val name = it.groupValues[1]
            if (name.split(" ").size <= 3) return Cmd.OpenApp(name)
        }
        Regex("^(?:search (?:the web )?for|google|look up) (.+)$").find(n)?.let { return Cmd.WebSearch(it.groupValues[1]) }
        return null
    }

    /** Maps a JSON action from the cloud brain to a command. */
    fun fromJson(a: JSONObject): Cmd? = when (a.optString("action")) {
        "torch_on" -> Cmd.Torch(true)
        "torch_off" -> Cmd.Torch(false)
        "battery" -> Cmd.Battery
        "open_app" -> Cmd.OpenApp(a.optString("name"))
        "web_search" -> Cmd.WebSearch(a.optString("query"))
        "set_alarm" -> Cmd.SetAlarm(a.optInt("hour"), a.optInt("minute"))
        "set_reminder" -> {
            val text = a.optString("text", "your reminder")
            if (a.has("minutes")) Cmd.Remind(text, System.currentTimeMillis() + a.optLong("minutes") * 60_000L)
            else Cmd.Remind(text, clockMs(a.optInt("hour"), a.optInt("minute"), null))
        }
        "remember" -> {
            val c = a.optString("content")
            Cmd.Remember(c, a.optString("category").takeIf { it in MemoryCategory.ALL } ?: category(c))
        }
        "add_task" -> Cmd.AddTask(a.optString("title"))
        "list_tasks" -> Cmd.ListTasks
        "read_notifications" -> Cmd.ReadNotifications
        "show_alarms" -> Cmd.ShowAlarms
        "volume" ->
            if (a.has("percent")) Cmd.Volume("set", a.optInt("percent").coerceIn(0, 100))
            else Cmd.Volume(a.optString("direction", "status"), null)
        "dial" -> Cmd.Dial(a.optString("number"))
        "sms" -> Cmd.Sms(a.optString("number"), a.optString("message"))
        "command" -> Cmd.Phrase(a.optString("text"))
        else -> null
    }

    fun parseJsonAction(reply: String): JSONObject? {
        val t = reply.trim()
        if (!t.startsWith("{") && !t.startsWith("```")) return null
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            JSONObject(t.substring(s, e + 1)).takeIf {
                it.has("action") || it.has("actions") || it.has("say") || it.has("learn")
            }
        } catch (ex: Exception) {
            null
        }
    }
}

/** Things AURELIS can do on the phone. */
object Device {
    fun torch(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone doesn't have a torch."
        cm.setTorchMode(id, on)
        return if (on) "Torch on." else "Torch off."
    }

    fun battery(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Battery is at $pct percent" + if (bm.isCharging) " and charging." else "."
    }

    fun clipboard(ctx: Context): String {
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cb.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString() else ""
        return if (text.isBlank()) "The clipboard is empty, or Android isn't letting me read it right now." else "Your clipboard says: $text"
    }

    fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val target = name.lowercase(Locale.ROOT).trim()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT) == target }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(target) }
            ?: return "I couldn't find an app called $name."
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return "I can't open $name."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "Opening ${match.loadLabel(pm)}."
    }

    fun setAlarm(ctx: Context, hour: Int, minute: Int): String {
        ctx.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "AURELIS alarm")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return String.format(Locale.ROOT, "Alarm set for %02d:%02d.", hour, minute)
    }

    fun volume(ctx: Context, action: String, percent: Int?): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        val ui = AudioManager.FLAG_SHOW_UI
        when (action) {
            "up" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, ui) }
            "down" -> repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, ui) }
            "mute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, ui)
            "unmute" -> am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, ui)
            "max" -> am.setStreamVolume(s, max, ui)
            "set" -> am.setStreamVolume(s, (max * (percent ?: 50) / 100).coerceIn(0, max), ui)
        }
        if (action == "mute") return "Muted."
        return "Volume is at ${am.getStreamVolume(s) * 100 / max} percent."
    }

    fun showAlarms(ctx: Context): Boolean = try {
        ctx.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    fun webSearch(ctx: Context, q: String): String {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Searching the web for $q."
    }

    fun dial(ctx: Context, number: String): String {
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "I've opened the dialler with $number. Tap call when you're ready."
    }

    fun sms(ctx: Context, number: String, message: String): String {
        ctx.startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                .putExtra("sms_body", message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Your message is ready. Review it and tap send yourself."
    }
}

/** The reasoning loop: understand, decide which brain to use, act, answer. */
object Assistant {

    private var pending: Cmd? = null
    private val YES = setOf("yes", "yeah", "yep", "sure", "do it", "go ahead", "confirm", "ok", "okay", "yes please", "correct")
    private val NO = setOf("no", "nope", "cancel", "don't", "do not", "stop", "no thanks")

    fun mode(ctx: Context): BrainMode {
        val hasKey = SecureStore.get(ctx, "cloud_key").isNotBlank()
        return when {
            AppState.online.value && hasKey -> BrainMode.ONLINE
            LocalBrain.active(ctx) != null -> BrainMode.OFFLINE
            else -> BrainMode.LIMITED
        }
    }

    private suspend fun record(ctx: Context, role: String, text: String) {
        AppState.add(role, text)
        val app = AurelisApp.instance
        if (app.prefs.saveConversations) {
            app.db.chat().insert(ChatRow(role = role, text = text, time = System.currentTimeMillis()))
        }
    }

    suspend fun log(text: String) {
        AurelisApp.instance.db.history().insert(HistoryRow(time = System.currentTimeMillis(), text = text))
    }

    /** Handles one user utterance (spoken or typed). Never throws. */
    suspend fun respond(ctx: Context, input: String): Reply = withContext(Dispatchers.IO) {
        val text = input.trim()
        if (text.isEmpty()) return@withContext Reply("I didn't catch that.")
        record(ctx, "user", text)
        val reply = try {
            think(ctx, text)
        } catch (e: Throwable) {
            Reply("Something went wrong while I was working on that. ${e.message ?: ""}".trim())
        }
        try {
            learn(text)
        } catch (e: Throwable) {
            // Learning is best-effort.
        }
        record(ctx, "assistant", reply.text)
        reply
    }

    private val PERSONAL = Regex(
        "\\b(my name is|call me|i am an?|i'm an?|i am from|i'm from|i live|i work|i study|i like|i love|i hate|i prefer|" +
            "i don't like|i was born|i usually|i always|i never|my (favou?rite|wife|husband|girlfriend|boyfriend|mum|mom|" +
            "mother|dad|father|brother|sister|son|daughter|friend|boss|birthday|job|car|school|church|business|company|" +
            "name|age|home|house|office|doctor|teacher))\\b",
        RegexOption.IGNORE_CASE
    )
    private val NOT_A_FACT = Regex(
        "^(what|who|how|why|when|where|which|can|could|do|does|did|is|are|will|would|should|tell|show|open|set|remind|add|play)\\b",
        RegexOption.IGNORE_CASE
    )
    private val TAUGHT = Regex("^When I say \"(.+)\", I mean \"(.+)\"$")

    /** Self-learning: quietly keeps lasting facts the user mentions about themselves. */
    private suspend fun learn(said: String) {
        val app = AurelisApp.instance
        if (!app.prefs.useMemory) return
        val t = said.trim().trimEnd('.', '!')
        if (t.length !in 8..160 || t.endsWith("?") || NOT_A_FACT.containsMatchIn(t)) return
        if (!PERSONAL.containsMatchIn(t) || Intents.parse(t) != null) return
        val n = Intents.norm(t)
        if (app.db.memories().list().any { Intents.norm(it.content) == n }) return
        app.db.memories().insert(Memory(category = Intents.category(t), content = t))
        log("Learned from conversation: $t")
    }

    /** Shortcuts the user taught with "when I say X, I mean Y". */
    private suspend fun applyTaught(said: String): String {
        val n = Intents.norm(said)
        for (m in AurelisApp.instance.db.memories().list()) {
            if (m.category != MemoryCategory.LEARNING) continue
            val g = TAUGHT.find(m.content)?.groupValues ?: continue
            if (Intents.norm(g[1]) == n) return g[2]
        }
        return said
    }

    private suspend fun think(ctx: Context, input: String): Reply {
        val n = Intents.norm(input)

        // 1. Waiting for a yes/no on a sensitive action?
        pending?.let { p ->
            pending = null
            if (n in YES) return run(ctx, p, confirmed = true)
            if (n in NO) return Reply("Cancelled. Nothing was changed.")
        }

        val text = applyTaught(input)

        // 2. "No, I said X": learn the correction so the same mishearing is fixed next time.
        Regex("^(?:no |nope )?(?:i said|i meant|i mean) (.+)$").find(n)?.let { m ->
            val meant = m.groupValues[1]
            val wrong = Learned.lastHeard
            if (wrong.isNotBlank() && Intents.norm(wrong) != meant) Learned.fix(ctx, wrong, meant)
            val r = act(ctx, meant, 1)
            return r.copy(text = "Understood. " + r.text)
        }
        Learned.lastHeard = text
        return act(ctx, text, 0)
    }

    /**
     * Understands and carries out one request. Phrases the user taught, and chains joined
     * with "and then", come back through here one step at a time.
     */
    private suspend fun act(ctx: Context, text: String, depth: Int): Reply {
        if (depth < 3) {
            val taught = Learned.resolve(ctx, text)
            val steps = (taught ?: text).split(Regex("\\s+and then\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
            val chain = steps.size > 1 && steps.all { Intents.parse(it) != null || Skills.commandLike(it) }
            if (steps.isNotEmpty() && (taught != null || chain)) {
                val replies = ArrayList<Reply>()
                for (step in steps) replies.add(act(ctx, step, depth + 1))
                return Reply(
                    replies.joinToString(" ") { it.text },
                    end = replies.last().end,
                    stopListening = replies.any { it.stopListening },
                    leftApp = replies.any { it.leftApp }
                )
            }
        }

        // Commands we can understand locally and reliably.
        Skills.handle(ctx, text)?.let { return it }
        Intents.parse(text)?.let { return run(ctx, it, confirmed = false) }

        // 3. Conversation with the best available brain.
        val history = AppState.messages.value.dropLast(1)
        return when (mode(ctx)) {
            BrainMode.ONLINE -> {
                val key = SecureStore.get(ctx, "cloud_key")
                try {
                    val out = CloudBrain.ask(key, AurelisApp.instance.prefs.cloudModel, onlinePrompt(ctx, text), history, text)
                    fromCloud(ctx, out)
                } catch (e: CloudBrain.CloudError) {
                    // Fall back to the local brain rather than failing outright.
                    val local = if (LocalBrain.active(ctx) != null) LocalBrain.generate(ctx, offlinePrompt(ctx, text), history, text) else null
                    if (local != null) Reply("${e.userMessage} Using my offline brain instead: $local")
                    else Reply(e.userMessage)
                }
            }
            BrainMode.OFFLINE -> {
                val out = LocalBrain.generate(ctx, offlinePrompt(ctx, text), history, text)
                Reply(out?.ifBlank { null } ?: "My offline brain couldn't produce an answer. It may need more free memory.")
            }
            BrainMode.LIMITED -> Reply(
                if (!AppState.online.value)
                    "I'm in limited mode: there's no internet and no offline brain installed. I can still handle reminders, tasks, memories, time, battery and the torch. You can download an offline brain in Settings."
                else
                    "I'm in limited mode because no cloud API key is set. Add your Gemini key in Settings, or download an offline brain."
            )
        }
    }

    /** Turns the cloud brain's answer (plain words, or JSON with actions / a fact to learn) into a reply. */
    private suspend fun fromCloud(ctx: Context, out: String): Reply {
        val obj = Intents.parseJsonAction(out) ?: return Reply(out.ifBlank { "I'm not sure how to answer that." })
        val fact = obj.optString("learn").trim()
        if (fact.isNotEmpty() && Opt.autoLearn(ctx)) learnFact(fact)
        val cmds = ArrayList<Cmd>()
        if (obj.has("action")) Intents.fromJson(obj)?.let { cmds.add(it) }
        val arr = obj.optJSONArray("actions")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                Intents.fromJson(o)?.let { cmds.add(it) }
            }
        }
        val say = obj.optString("say").trim()
        if (cmds.isEmpty()) {
            return Reply(say.ifEmpty { if (fact.isNotEmpty()) "Noted." else "I'm not sure how to do that." })
        }
        val replies = ArrayList<Reply>()
        for (c in cmds) replies.add(run(ctx, c, confirmed = false))
        return Reply(
            (listOf(say) + replies.map { it.text }).filter { it.isNotEmpty() }.joinToString(" "),
            end = replies.last().end,
            stopListening = replies.any { it.stopListening },
            leftApp = replies.any { it.leftApp }
        )
    }

    /** Self-learning: keeps a lasting fact about the user (visible and deletable under Memory). */
    private suspend fun learnFact(fact: String) {
        val dao = AurelisApp.instance.db.memories()
        val key = Intents.norm(fact)
        if (dao.list().none { Intents.norm(it.content) == key }) {
            dao.insert(Memory(category = MemoryCategory.CONTEXT, content = fact))
            log("Learned about you: $fact")
        }
    }

    private suspend fun relevantMemories(text: String): String {
        val app = AurelisApp.instance
        if (!app.prefs.useMemory) return ""
        val all = app.db.memories().list()
        if (all.isEmpty()) return ""
        val words = Intents.norm(text).split(" ").filter { it.length > 3 }.toSet()
        val always = setOf(MemoryCategory.PREFERENCES, MemoryCategory.CONTEXT)
        val prefs = all.filter { it.category in always }.take(12)
        val scored = all.filter { it.category !in always }
            .map { m -> m to Intents.norm(m.content).split(" ").count { it in words } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
        val chosen = (prefs + scored).distinct()
        if (chosen.isEmpty()) return ""
        return chosen.joinToString("\n") { "- [${it.category}] ${it.content}" }
    }

    private fun now(): String = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())

    private suspend fun onlinePrompt(ctx: Context, text: String): String {
        val mem = relevantMemories(text)
        return """
            You are AURELIS (Adaptive Unified Reasoning & Executive Life Intelligence System), a personal
            intelligence system living on the user's Android phone. Personality: calm, intelligent, articulate,
            observant, respectful, helpful and context-aware. Philosophy: understand before acting. If a request
            is ambiguous, ask one short clarifying question instead of guessing.
            Your replies are spoken aloud: keep them to 1-3 natural sentences. Never use markdown, lists,
            asterisks or emoji. Current date and time: ${now()}. Mode: online.
            The user's words reach you through speech recognition that often mishears them. If the text looks
            garbled, work out what they most likely meant and answer that; if you really cannot tell, ask
            them to say it again.
            ${if (mem.isNotEmpty()) "Things the user has asked you to remember:\n$mem" else ""}

            When the user clearly asks you to perform one of these actions, reply with ONLY a single-line JSON
            object and nothing else:
              {"action":"set_reminder","text":"call mum","minutes":30}
              {"action":"set_reminder","text":"call mum","hour":17,"minute":30}
              {"action":"remember","content":"...","category":"Preferences|Projects|Important|Tasks|Learning|Context"}
              {"action":"add_task","title":"..."}
              {"action":"list_tasks"}
              {"action":"open_app","name":"WhatsApp"}
              {"action":"set_alarm","hour":6,"minute":30}
              {"action":"web_search","query":"..."}
              {"action":"torch_on"} / {"action":"torch_off"} / {"action":"battery"}
              {"action":"read_notifications"}
              {"action":"show_alarms"}
              {"action":"volume","direction":"up"}   (up, down, mute, unmute or max)
              {"action":"volume","percent":40}
              {"action":"dial","number":"..."}
              {"action":"sms","number":"...","message":"..."}
            For any other phone control, send it as plain words:
              {"action":"command","text":"volume up"}
            Phrases that work: volume up, volume down, mute, set volume to 60, play, pause, next song,
            previous song, play NAME on youtube, brightness up, set brightness to 40, show my alarms,
            cancel alarm at 6 30 am, set a timer for 10 minutes, open camera, navigate to PLACE, go home,
            open wifi settings, call CONTACT NAME, text CONTACT NAME saying MESSAGE, delete reminder about X,
            delete task X.
            To do several things at once: {"say":"On it.","actions":[{"action":"torch_on"},{"action":"command","text":"volume up"}]}
            Otherwise answer normally in plain words.
        """.trimIndent()
    }

    private suspend fun offlinePrompt(ctx: Context, text: String): String {
        val mem = relevantMemories(text)
        return "You are AURELIS, a calm and helpful personal assistant running fully offline on the user's phone. " +
            "You have no internet access, so you cannot know news, weather, prices or anything live; if asked, say you are offline. " +
            "Answer in one or two short spoken sentences, no lists or symbols. Current time: ${now()}." +
            (if (mem.isNotEmpty()) "\nUser memories:\n$mem" else "")
    }

    private fun confirmText(c: Cmd): String? = when (c) {
        is Cmd.Forget -> "Shall I delete memories about \"${c.query}\"? Say yes or no."
        is Cmd.Dial -> "Shall I open the dialler for ${c.number}? Say yes or no."
        Cmd.ClearReminders -> "Shall I delete all your reminders? Say yes or no."
        is Cmd.Sms -> "Shall I prepare a message to ${c.number} saying \"${c.message}\"? Say yes or no."
        else -> null
    }

    private suspend fun run(ctx: Context, c: Cmd, confirmed: Boolean): Reply {
        val app = AurelisApp.instance
        if (!confirmed) {
            confirmText(c)?.let {
                pending = c
                return Reply(it)
            }
        }
        return when (c) {
            Cmd.Goodbye -> Reply("Very well. I'll remain on standby.", end = true)
            is Cmd.Phrase -> Skills.handle(ctx, c.text)
                ?: Intents.parse(c.text)?.let { p -> run(ctx, p, confirmed = false) }
                ?: Reply("I understood, but that's something I can't do on this phone yet.")
            Cmd.StopListening -> Reply("Understood. I've paused listening. Tap the core when you need me.", end = true, stopListening = true)
            Cmd.Time -> Reply("It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + ".")
            Cmd.DateToday -> Reply("Today is " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()) + ".")
            Cmd.Battery -> Reply(Device.battery(ctx))
            is Cmd.Torch -> Reply(Device.torch(ctx, c.on))
            Cmd.ReadClipboard -> Reply(Device.clipboard(ctx))
            Cmd.Status -> {
                val m = mode(ctx)
                val net = if (AppState.online.value) "connected to the internet" else "offline"
                Reply("I'm $net, running in ${m.label.lowercase(Locale.ROOT)}.")
            }
            is Cmd.Remember -> {
                app.db.memories().insert(Memory(category = c.category, content = c.content))
                log("Saved memory (${c.category}): ${c.content}")
                Reply("Noted. I'll remember that ${c.content.replaceFirst(Regex("^(i|I) "), "you ")}.")
            }
            Cmd.Recall -> {
                val list = app.db.memories().list()
                if (list.isEmpty()) Reply("You haven't asked me to remember anything yet.")
                else Reply("Here's what I remember: " + list.take(6).joinToString("; ") { it.content } +
                    if (list.size > 6) ". And ${list.size - 6} more in the Memory section." else ".")
            }
            is Cmd.Forget -> {
                val words = Intents.norm(c.query).split(" ").filter { it.length > 2 }
                val hits = app.db.memories().list().filter { m ->
                    val mn = Intents.norm(m.content)
                    words.isNotEmpty() && words.all { mn.contains(it) }
                }
                hits.forEach { app.db.memories().delete(it) }
                if (hits.isNotEmpty()) log("Deleted ${hits.size} memory item(s) about ${c.query}")
                Reply(if (hits.isEmpty()) "I couldn't find a memory about ${c.query}." else "Done. I've forgotten ${hits.size} item${if (hits.size > 1) "s" else ""} about ${c.query}.")
            }
            is Cmd.Remind -> {
                val id = app.db.reminders().insert(Reminder(text = c.text, triggerAt = c.at))
                Reminders.schedule(ctx, Reminder(id = id, text = c.text, triggerAt = c.at))
                val whenText = SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(c.at))
                log("Reminder set for $whenText: ${c.text}")
                Reply("Reminder set for $whenText: ${c.text}.")
            }
            Cmd.ListReminders -> {
                val list = app.db.reminders().pending()
                if (list.isEmpty()) Reply("You have no upcoming reminders.")
                else Reply("You have ${list.size} upcoming: " + list.take(5).joinToString("; ") {
                    SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(it.triggerAt)) + ", " + it.text
                } + ".")
            }
            is Cmd.AddTask -> {
                app.db.tasks().insert(TaskItem(title = c.title))
                log("Task added: ${c.title}")
                Reply("Added to your tasks: ${c.title}.")
            }
            Cmd.ListTasks -> {
                val open = app.db.tasks().list().filter { !it.done }
                if (open.isEmpty()) Reply("Your task list is clear.")
                else Reply("You have ${open.size} open task${if (open.size > 1) "s" else ""}: " + open.take(6).joinToString("; ") { it.title } + ".")
            }
            is Cmd.CompleteTask -> {
                val q = Intents.norm(c.query)
                val t = app.db.tasks().list().firstOrNull { !it.done && Intents.norm(it.title).contains(q) }
                if (t == null) Reply("I couldn't find an open task matching ${c.query}.")
                else {
                    app.db.tasks().update(t.copy(done = true))
                    log("Task completed: ${t.title}")
                    Reply("Marked done: ${t.title}.")
                }
            }
            is Cmd.Volume -> withContext(Dispatchers.Main) { Reply(Device.volume(ctx, c.action, c.percent)) }
            Cmd.ShowAlarms -> withContext(Dispatchers.Main) {
                if (Device.showAlarms(ctx)) Reply(
                    "I've opened your alarms. Android doesn't let any app delete Clock alarms directly, so tap the one you want to change.",
                    end = true, leftApp = true
                ) else Reply("I couldn't open the Clock app on this phone.")
            }
            Cmd.ScreenControl -> Reply("I can't scroll or tap inside other apps yet. That needs Android's accessibility permission, which this version doesn't use.")
            Cmd.ClearReminders -> {
                val list = app.db.reminders().pending()
                list.forEach {
                    Reminders.cancel(ctx, it)
                    app.db.reminders().delete(it)
                }
                log("Deleted all reminders (${list.size})")
                Reply(if (list.isEmpty()) "You had no reminders." else "Done. I've deleted ${list.size} reminder${if (list.size > 1) "s" else ""}.")
            }
            is Cmd.DeleteReminder -> {
                val words = Intents.norm(c.query).split(" ").filter { it.length > 2 }
                val hits = app.db.reminders().pending().filter { r ->
                    val rn = Intents.norm(r.text)
                    words.isNotEmpty() && words.all { rn.contains(it) }
                }
                hits.forEach {
                    Reminders.cancel(ctx, it)
                    app.db.reminders().delete(it)
                }
                if (hits.isNotEmpty()) log("Deleted reminder: ${hits.joinToString { it.text }}")
                Reply(if (hits.isEmpty()) "I couldn't find a reminder about ${c.query}." else "Done. I've deleted the reminder: ${hits.joinToString(", ") { it.text }}.")
            }
            is Cmd.Teach -> {
                app.db.memories().insert(Memory(category = MemoryCategory.LEARNING, content = "When I say \"${c.trigger}\", I mean \"${c.meaning}\""))
                log("Learned shortcut: ${c.trigger} = ${c.meaning}")
                Reply("Learned. When you say ${c.trigger}, I'll treat it as ${c.meaning}.")
            }
            Cmd.ReadNotifications -> Reply(NotificationReader.summary(ctx))
            is Cmd.OpenApp -> withContext(Dispatchers.Main) {
                val r = Device.openApp(ctx, c.name)
                log(r)
                Reply(r, end = true, leftApp = true)
            }
            is Cmd.WebSearch -> withContext(Dispatchers.Main) {
                if (!AppState.online.value) Reply("I'm offline, so I can't search the web right now.")
                else Reply(Device.webSearch(ctx, c.query).also { log(it) }, end = true, leftApp = true)
            }
            is Cmd.SetAlarm -> withContext(Dispatchers.Main) { Reply(Device.setAlarm(ctx, c.hour, c.minute).also { log(it) }) }
            is Cmd.Dial -> withContext(Dispatchers.Main) { Reply(Device.dial(ctx, c.number).also { log("Dialler opened: ${c.number}") }, end = true, leftApp = true) }
            is Cmd.Sms -> withContext(Dispatchers.Main) { Reply(Device.sms(ctx, c.number, c.message).also { log("SMS drafted to ${c.number}") }, end = true, leftApp = true) }
        }
    }
}
