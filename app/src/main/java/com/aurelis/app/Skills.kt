package com.aurelis.app

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import java.util.Locale
import kotlin.random.Random

/** Extra switches, kept in the same settings file as the rest. */
object Opt {
    private fun p(ctx: Context) = ctx.getSharedPreferences("aurelis", Context.MODE_PRIVATE)

    /** true = answer anything it hears; false = only after "Hey AURELIS" or a double clap. */
    fun openListening(ctx: Context) = p(ctx).getBoolean("open_listening", true)
    fun setOpenListening(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("open_listening", v).apply()

    fun clap(ctx: Context) = p(ctx).getBoolean("clap_wake", true)
    fun setClap(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("clap_wake", v).apply()

    fun autoLearn(ctx: Context) = p(ctx).getBoolean("auto_learn", true)
    fun setAutoLearn(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("auto_learn", v).apply()

    fun askedBattery(ctx: Context) = p(ctx).getBoolean("asked_battery", false)
    fun setAskedBattery(ctx: Context) = p(ctx).edit().putBoolean("asked_battery", true).apply()
}

/** Self-learning: phrases the user taught, and corrections of things AURELIS misheard. */
object Learned {
    @Volatile
    var lastHeard = ""

    private fun p(ctx: Context) = ctx.getSharedPreferences("aurelis_learned", Context.MODE_PRIVATE)

    fun teach(ctx: Context, phrase: String, meaning: String) {
        val k = Intents.norm(phrase)
        if (k.isNotEmpty() && meaning.isNotBlank()) p(ctx).edit().putString("cmd:$k", meaning.trim()).apply()
    }

    fun fix(ctx: Context, heard: String, meant: String) {
        val k = Intents.norm(heard)
        if (k.isNotEmpty() && meant.isNotBlank()) p(ctx).edit().putString("fix:$k", meant.trim()).apply()
    }

    fun resolve(ctx: Context, text: String): String? {
        val k = Intents.norm(text)
        if (k.isEmpty()) return null
        val sp = p(ctx)
        return sp.getString("cmd:$k", null) ?: sp.getString("fix:$k", null)
    }

    fun taught(ctx: Context): List<Pair<String, String>> =
        p(ctx).all.entries.filter { it.key.startsWith("cmd:") }
            .map { it.key.removePrefix("cmd:") to it.value.toString() }

    fun fixCount(ctx: Context): Int = p(ctx).all.keys.count { it.startsWith("fix:") }

    fun forget(ctx: Context, phrase: String): Boolean {
        val k = Intents.norm(phrase)
        val sp = p(ctx)
        val had = sp.contains("cmd:$k") || sp.contains("fix:$k")
        sp.edit().remove("cmd:$k").remove("fix:$k").apply()
        return had
    }
}

/** Decides whether something heard in open listening is worth answering. */
object Speech {
    private val FILLER = setOf(
        "the", "a", "an", "uh", "huh", "hmm", "hm", "oh", "ah", "um", "mm", "and", "but", "i", "it", "in",
        "to", "of", "yeah", "so", "is", "you", "he", "she", "we", "they", "that", "this", "er", "eh"
    )
    private val SINGLE = setOf(
        "hello", "hi", "hey", "stop", "pause", "play", "resume", "next", "previous", "skip", "louder",
        "quieter", "mute", "unmute", "time", "date", "battery", "help", "goodbye", "bye", "yes", "no",
        "okay", "cancel", "thanks", "aurelis"
    )

    fun meaningful(text: String): Boolean {
        val words = Intents.norm(text).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        if (words.size == 1) return words[0] in SINGLE
        return words.count { it !in FILLER } >= 1
    }
}

/** Phone-control and everyday commands that work without the cloud. */
object Skills {
    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100
    )
    private val COMMAND_STARTS = setOf(
        "turn", "set", "open", "launch", "start", "play", "pause", "resume", "stop", "volume", "mute", "unmute",
        "call", "text", "remind", "add", "show", "navigate", "take", "brightness", "next", "previous", "skip",
        "go", "tell", "what's", "what", "read", "list", "cancel", "delete", "remove", "say", "search", "louder",
        "quieter", "torch", "flashlight", "battery", "time", "increase", "decrease", "lower", "raise", "make"
    )
    private val JOKES = listOf(
        "Why did the phone go to school? It wanted to be a smartphone.",
        "I would tell you a joke about Wi-Fi, but I'm not sure you'd get it.",
        "Why was the computer cold? It left its Windows open.",
        "I asked my battery how it was feeling. It said it was drained."
    )

    /** A cheap guess at whether a piece of text is an instruction rather than conversation. */
    fun commandLike(text: String): Boolean =
        Intents.norm(text).substringBefore(" ") in COMMAND_STARTS

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
                if (next != null && next in 1..9 && tens < 100) {
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

    private fun launch(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    private fun gone(ok: Boolean, text: String): Reply =
        if (ok) Reply(text, end = true, leftApp = true)
        else Reply("I couldn't open that on this phone. If AURELIS is in the background, allow \"Display over other apps\" in Settings so I can open things for you.")

    // ------------------------------------------------------------------ volume, media, brightness

    private fun volume(ctx: Context, change: String, percent: Int? = null): Reply {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(s)
        val cur = am.getStreamVolume(s)
        val step = maxOf(1, max / 8)
        val target = when (change) {
            "up" -> cur + step
            "down" -> cur - step
            "max" -> max
            "mute" -> 0
            "unmute" -> if (cur == 0) max / 2 else cur
            else -> ((percent ?: 50).coerceIn(0, 100) * max + 50) / 100
        }.coerceIn(0, max)
        am.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI)
        val pct = target * 100 / max
        return Reply(if (target == 0) "Muted." else "Volume at $pct percent.")
    }

    private fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun brightness(ctx: Context, change: String, percent: Int? = null): Reply {
        if (!Settings.System.canWrite(ctx)) {
            launch(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            return Reply("I need permission to change system settings first. I've opened the page: switch it on, then ask me again.", end = true, leftApp = true)
        }
        val cr = ctx.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when (change) {
            "up" -> cur + 50
            "down" -> cur - 50
            "max" -> 255
            "min" -> 10
            else -> (percent ?: 50).coerceIn(1, 100) * 255 / 100
        }.coerceIn(5, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        return Reply("Brightness at ${target * 100 / 255} percent.")
    }

    // ------------------------------------------------------------------ calculator

    private fun calc(expr: String): String? {
        var s = " " + digits(expr.lowercase(Locale.ROOT).replace(",", "").replace("?", "").trim()) + " "
        Regex("^ (\\d+(?:\\.\\d+)?) percent of (\\d+(?:\\.\\d+)?) $").find(s)?.let {
            return fmt(it.groupValues[1].toDouble() * it.groupValues[2].toDouble() / 100.0)
        }
        s = s.replace(" plus ", " + ").replace(" minus ", " - ")
            .replace(" times ", " * ").replace(" multiplied by ", " * ").replace(" x ", " * ")
            .replace(" divided by ", " / ").replace(" over ", " / ")
        val tokens = s.trim().split(Regex("\\s+"))
        if (tokens.size < 3 || tokens.size % 2 == 0) return null
        val nums = ArrayList<Double>()
        val ops = ArrayList<String>()
        for ((i, t) in tokens.withIndex()) {
            if (i % 2 == 0) nums.add(t.toDoubleOrNull() ?: return null)
            else if (t in setOf("+", "-", "*", "/")) ops.add(t) else return null
        }
        // multiplication and division first
        var i = 0
        while (i < ops.size) {
            if (ops[i] == "*" || ops[i] == "/") {
                if (ops[i] == "/" && nums[i + 1] == 0.0) return null
                val v = if (ops[i] == "*") nums[i] * nums[i + 1] else nums[i] / nums[i + 1]
                nums[i] = v
                nums.removeAt(i + 1)
                ops.removeAt(i)
            } else {
                i++
            }
        }
        var total = nums[0]
        for (j in ops.indices) total = if (ops[j] == "+") total + nums[j + 1] else total - nums[j + 1]
        return fmt(total)
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", v)

    // ------------------------------------------------------------------ contacts

    private fun findContact(ctx: Context, name: String): Pair<String, String>? {
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        ctx.contentResolver.query(
            phone,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"), null
        )?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: name) to (c.getString(1) ?: "")
        }
        return null
    }

    private fun contactsAllowed(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ settings pages

    private fun settingsPage(ctx: Context, what: String): Reply? {
        val action = when {
            what.contains("wi") -> Settings.ACTION_WIFI_SETTINGS
            what.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            what.contains("data") -> Settings.ACTION_DATA_ROAMING_SETTINGS
            what.contains("hotspot") -> Settings.ACTION_WIRELESS_SETTINGS
            what.contains("airplane") || what.contains("flight") -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            what.contains("location") || what.contains("gps") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            what.contains("do not disturb") -> Settings.ACTION_SOUND_SETTINGS
            else -> return null
        }
        return gone(launch(ctx, Intent(action)), "Android doesn't let apps switch $what by themselves, so I've opened its switch for you.")
    }

    // ------------------------------------------------------------------ the commands

    /** Returns a reply if [raw] was one of these commands, or null to let the rest of AURELIS handle it. */
    suspend fun handle(ctx: Context, raw: String): Reply? {
        val n = Intents.norm(raw)
        if (n.isEmpty()) return null
        val d = digits(n)
        fun m(pattern: String) = Regex(pattern).find(n)
        fun md(pattern: String) = Regex(pattern).find(d)
        fun has(pattern: String) = Regex(pattern).containsMatchIn(n)

        // ---- teaching (self-learning)
        m("^(?:when|whenever|if) i say (.+?) (?:you say|you reply|reply with|reply|respond with|respond|answer) (.+)$")?.let {
            Learned.teach(ctx, it.groupValues[1], "say " + it.groupValues[2])
            return Reply("Learned. When you say ${it.groupValues[1]}, I'll say ${it.groupValues[2]}.")
        }
        m("^(?:when|whenever|if) i say (.+?) (?:it means|that means|means|i mean|then|you should|do|just) (.+)$")?.let {
            Learned.teach(ctx, it.groupValues[1], it.groupValues[2])
            return Reply("Learned. When you say ${it.groupValues[1]}, I'll ${it.groupValues[2]}.")
        }
        m("^(?:learn|know) that (.+?) means (.+)$")?.let {
            Learned.teach(ctx, it.groupValues[1], it.groupValues[2])
            return Reply("Learned. ${it.groupValues[1]} now means ${it.groupValues[2]}.")
        }
        if (has("^what have you learn(ed|t)|^(list|show) (my |the )?(learned|taught|custom) (commands|phrases)|^what did i teach you")) {
            val list = Learned.taught(ctx)
            val fixes = Learned.fixCount(ctx)
            if (list.isEmpty() && fixes == 0) return Reply("Nothing yet. Teach me by saying: when I say good morning, then tell me the time and then read my tasks.")
            val spoken = list.take(6).joinToString("; ") { "${it.first} means ${it.second}" }
            return Reply((if (list.isEmpty()) "No taught phrases yet." else "You taught me: $spoken.") +
                (if (fixes > 0) " I've also learned $fixes correction${if (fixes > 1) "s" else ""} from things I misheard." else ""))
        }
        m("^(?:forget|unlearn|delete) (?:the )?(?:command|phrase|shortcut) (.+)$")?.let {
            return Reply(if (Learned.forget(ctx, it.groupValues[1])) "Forgotten. ${it.groupValues[1]} no longer means anything special." else "I hadn't learned ${it.groupValues[1]}.")
        }

        // ---- greetings and small talk that should work with no internet
        if (has("^(hello|hi|hey|good morning|good afternoon|good evening)( there)?( aurelis)?$")) {
            return Reply("Hello. How can I help?")
        }
        if (has("^(thanks|thank you)( aurelis| very much| so much)?$")) return Reply("You're welcome.", end = true)
        if (has("^(repeat|say) that( again)?$|^what did you say$|^come again$|^pardon$")) {
            val last = AppState.messages.value.lastOrNull { it.role == "assistant" }?.text
            return Reply(last ?: "I haven't said anything yet.")
        }
        if (has("^what can you do|^help$|^(list|show) (your |the )?commands")) {
            return Reply(
                "I can control volume, music and brightness, set alarms, timers and reminders, manage tasks and memories, " +
                    "open apps, the camera and maps, call or text your contacts, search the web, do sums, and answer questions. " +
                    "You can also teach me: say, when I say good night, then turn off the torch and then set an alarm for 6 a m."
            )
        }

        // ---- how AURELIS listens
        if (has("(always listen|open listening|no wake word|without (the )?wake word|(turn off|disable|stop using) (the )?wake word|listen to everything)")) {
            Opt.setOpenListening(ctx, true)
            return Reply("Done. I'll answer whenever you speak, no wake word needed.")
        }
        if (has("((require|use|turn on|enable) (the )?wake word|only (listen|respond|answer) (to|when i say) hey)")) {
            Opt.setOpenListening(ctx, false)
            return Reply("Done. I'll only respond after Hey AURELIS, or a double clap.")
        }
        if (has("(turn on|enable|use) (the )?clap")) {
            Opt.setClap(ctx, true)
            return Reply("Clap to wake is on. Clap twice and I'll listen.")
        }
        if (has("(turn off|disable|stop) (the )?clap")) {
            Opt.setClap(ctx, false)
            return Reply("Clap to wake is off.")
        }
        if (has("^(speak|talk) (slower|more slowly)|^slow down$")) return rate(ctx, -0.15f)
        if (has("^(speak|talk) (faster|more quickly)|^speed up$")) return rate(ctx, 0.15f)
        if (has("^(speak|talk) normally|^normal speed$")) return rate(ctx, 0f)

        // ---- volume
        md("(?:set |turn |put )?(?:the )?volume (?:to |at )?(\\d{1,3})(?: percent)?$")?.let {
            return volume(ctx, "set", it.groupValues[1].toInt())
        }
        if (has("^(max(imum)?|full) volume|volume (to |at )?(max(imum)?|full)|^as loud as (possible|you can)")) return volume(ctx, "max")
        if (has("^unmute")) return volume(ctx, "unmute")
        if (has("^(mute|silence)( the)?( volume| sound| phone| music)?$")) return volume(ctx, "mute")
        if (has("volume up|^louder$|(increase|raise) (the )?volume|turn (it|the volume|the sound) up|^turn up")) return volume(ctx, "up")
        if (has("volume down|^quieter$|(decrease|lower|reduce) (the )?volume|turn (it|the volume|the sound) down|^turn down")) return volume(ctx, "down")

        // ---- brightness
        md("(?:set )?(?:the )?(?:screen )?brightness (?:to |at )?(\\d{1,3})(?: percent)?$")?.let {
            return brightness(ctx, "set", it.groupValues[1].toInt())
        }
        if (has("brightness (to )?(max(imum)?|full)|(full|max(imum)?) brightness")) return brightness(ctx, "max")
        if (has("brightness (to )?(low(est)?|min(imum)?)|(lowest|min(imum)?) brightness")) return brightness(ctx, "min")
        if (has("brightness up|(increase|raise) (the )?brightness|(screen )?brighter")) return brightness(ctx, "up")
        if (has("brightness down|(decrease|lower|reduce) (the )?brightness|dimmer|(dim|darken) (the )?screen")) return brightness(ctx, "down")

        // ---- music and media
        if (has("^(next|skip)( (the |this )?(song|track|one))?$|^skip$")) {
            mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
            return Reply("Next track.", end = true)
        }
        if (has("^(previous|last)( (song|track))?$|^go back a (song|track)$")) {
            mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            return Reply("Previous track.", end = true)
        }
        if (has("^pause( (the |my )?(music|song|video))?$|^stop (the |my )?(music|song)$")) {
            mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
            return Reply("Paused.", end = true)
        }
        if (has("^(play|resume)( (the |my |some )?(music|song))?$|^continue (the )?music$")) {
            mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
            return Reply("Playing.", end = true)
        }
        m("^play (.+?) on youtube$")?.let {
            val q = it.groupValues[1]
            val ok = launch(ctx, Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, q)) ||
                launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
            return gone(ok, "Searching YouTube for $q.")
        }
        m("^play (.+)$")?.let {
            val q = it.groupValues[1]
            if (!q.startsWith("a game") && !q.startsWith("with ")) {
                val ok = launch(
                    ctx, Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        .putExtra(SearchManager.QUERY, q)
                ) || launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
                return gone(ok, "Playing $q.")
            }
        }

        // ---- alarms and timers
        if (has("(show|open|list|see|browse|check|read|what are|view)( me)?( all)?( my| the)? alarms")) {
            return gone(
                launch(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS)),
                "Here are your alarms. Android doesn't let me read them aloud or scroll them, but you can tell me a time to turn one off."
            )
        }
        md("(?:delete|cancel|remove|dismiss|turn off|switch off|stop)(?: my| the)?(?: next)?(?: (\\d{1,2})(?: (\\d{2}))? ?(am|pm|a m|p m)?)? alarm(?: (?:at|for) (\\d{1,2})(?: (\\d{2}))? ?(am|pm|a m|p m)?)?")?.let {
            val g = it.groupValues
            val hour = (g[1].ifEmpty { g[4] }).toIntOrNull()
            val minute = (g[2].ifEmpty { g[5] }).toIntOrNull() ?: 0
            val ap = (g[3].ifEmpty { g[6] }).replace(" ", "")
            val i = Intent(AlarmClock.ACTION_DISMISS_ALARM)
            if (hour != null) {
                i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                i.putExtra(AlarmClock.EXTRA_HOUR, hour % 12)
                i.putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (ap == "pm" || hour > 12) i.putExtra(AlarmClock.EXTRA_IS_PM, true)
                if (ap == "am") i.putExtra(AlarmClock.EXTRA_IS_PM, false)
            } else {
                i.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            }
            return gone(launch(ctx, i), "I've asked your clock to turn that alarm off. Android only lets me switch alarms off, not erase them; your clock may ask you to confirm.")
        }
        md("(?:set|start)(?: a| the)? timer (?:for )?(\\d+) (second|minute|hour)s?")?.let {
            val nUnits = it.groupValues[1].toInt()
            val secs = when (it.groupValues[2]) {
                "hour" -> nUnits * 3600
                "minute" -> nUnits * 60
                else -> nUnits
            }
            val ok = launch(
                ctx, Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "AURELIS timer")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            )
            return if (ok) Reply("Timer set for $nUnits ${it.groupValues[2]}${if (nUnits == 1) "" else "s"}.") else gone(false, "")
        }

        // ---- reminders and tasks (AURELIS's own, so these can really be deleted)
        if (has("(delete|cancel|clear|remove) all (my |the )?reminders")) {
            val db = AurelisApp.instance.db
            val all = db.reminders().pending()
            all.forEach {
                Reminders.cancel(ctx, it)
                db.reminders().delete(it)
            }
            return Reply(if (all.isEmpty()) "You had no reminders." else "Deleted ${all.size} reminder${if (all.size > 1) "s" else ""}.")
        }
        m("^(?:delete|cancel|remove) (?:the |my )?reminder (?:to |about |for )?(.+)$")?.let {
            val q = it.groupValues[1]
            val db = AurelisApp.instance.db
            val hit = db.reminders().pending().firstOrNull { r -> Intents.norm(r.text).contains(q) }
                ?: return Reply("I couldn't find a reminder about $q.")
            Reminders.cancel(ctx, hit)
            db.reminders().delete(hit)
            return Reply("Deleted the reminder: ${hit.text}.")
        }
        m("^(?:delete|remove) (?:the |my )?task (.+)$")?.let {
            val q = it.groupValues[1]
            val db = AurelisApp.instance.db
            val hit = db.tasks().list().firstOrNull { t -> Intents.norm(t.title).contains(q) }
                ?: return Reply("I couldn't find a task matching $q.")
            db.tasks().delete(hit)
            return Reply("Deleted the task: ${hit.title}.")
        }
        if (has("(clear|delete|remove) (my |the |all )?(completed|done|finished) tasks")) {
            val db = AurelisApp.instance.db
            val done = db.tasks().list().filter { it.done }
            done.forEach { db.tasks().delete(it) }
            return Reply("Cleared ${done.size} completed task${if (done.size == 1) "" else "s"}.")
        }
        Regex("^(?:take a note|note that|make a note|write down|note)(?: that)?[:,]? (.+)$", RegexOption.IGNORE_CASE).find(raw.trim())?.let {
            val note = it.groupValues[1].trim().trimEnd('.')
            AurelisApp.instance.db.memories().insert(Memory(category = MemoryCategory.IMPORTANT, content = note))
            return Reply("Noted: $note.")
        }

        // ---- calls and messages by contact name
        m("^(?:call|phone|ring|dial) (.+)$")?.let {
            val who = it.groupValues[1]
            if (who.startsWith("me ") || Regex("^[0-9 ]+$").matches(digits(who))) return@let
            if (!contactsAllowed(ctx)) return Reply("I need the Contacts permission to find $who. Open AURELIS and allow it, then ask again.")
            val c = findContact(ctx, who) ?: return Reply("I couldn't find $who in your contacts.")
            return gone(launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.second)))), "Calling ${c.first} is ready. Tap the call button to confirm.")
        }
        m("^(?:text|message|sms|send a message to|send a text to) (.+?) (?:saying|that says|to say|and say) (.+)$")?.let {
            val who = it.groupValues[1]
            val body = it.groupValues[2]
            if (!contactsAllowed(ctx)) return Reply("I need the Contacts permission to find $who. Open AURELIS and allow it, then ask again.")
            val c = findContact(ctx, who) ?: return Reply("I couldn't find $who in your contacts.")
            val ok = launch(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.second))).putExtra("sms_body", body))
            return gone(ok, "Your message to ${c.first} is ready. Check it and tap send.")
        }

        // ---- camera, maps, web
        if (has("^(open|launch|start) (the )?camera$|take a (photo|picture|selfie)")) {
            return gone(launch(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), "Camera open.")
        }
        m("^(?:navigate|take me|directions|drive|get me) to (.+)$")?.let {
            val q = it.groupValues[1]
            val ok = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))) ||
                launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
            return gone(ok, "Starting directions to $q.")
        }
        m("^(?:where is|show me|find) (.+?) on (?:the |a )?map$")?.let {
            val q = it.groupValues[1]
            return gone(launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))), "Showing $q on the map.")
        }
        if (has("weather|forecast|is it going to rain")) {
            if (!AppState.online.value) return Reply("I'm offline, so I can't check the weather right now.")
            return gone(launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(n)))), "Here's the weather.")
        }

        // ---- getting around the phone
        if (has("^(go |take me )?(to )?(the )?home( screen)?$|^go home$|^minimi[sz]e")) {
            return gone(launch(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)), "Home screen.")
        }
        m("^(?:open|show|go to|turn on|turn off|enable|disable|switch on|switch off) (?:the |my )?(wi fi|wifi|bluetooth|mobile data|data|hotspot|airplane mode|flight mode|location|gps|do not disturb)(?: settings)?$")?.let {
            settingsPage(ctx, it.groupValues[1])?.let { r -> return r }
        }
        if (has("^(open|show) (the |phone )?settings$")) return gone(launch(ctx, Intent(Settings.ACTION_SETTINGS)), "Settings open.")
        if (has("^(scroll|swipe) (up|down|left|right)|^go back$|^(tap|click|press) |screenshot|^lock (the|my) (phone|screen)|recent apps|^close (this|the) app")) {
            return Reply(
                "I can't scroll, tap or press buttons inside other apps yet. That needs Android's Accessibility access, " +
                    "and Play Protect blocks it for apps installed outside the Play Store."
            )
        }
        if (n.contains("notification")) {
            return Reply("I can't read notifications. Play Protect blocks that access for apps installed outside the Play Store.")
        }

        // ---- sums, chance and fun
        Regex("^(?:what is|what's|whats|calculate|compute|how much is)\\s+(.+?)\\??$").find(raw.trim().lowercase(Locale.ROOT))?.let {
            calc(it.groupValues[1])?.let { v -> return Reply("That's $v.") }
        }
        if (has("(flip|toss) a coin")) return Reply(if (Random.nextBoolean()) "Heads." else "Tails.")
        if (has("roll (a |the )?di(ce|e)")) return Reply("You rolled a ${Random.nextInt(1, 7)}.")
        md("(?:pick|choose|give me|say) a (?:random )?number between (\\d+) and (\\d+)")?.let {
            val a = it.groupValues[1].toInt()
            val b = it.groupValues[2].toInt()
            return Reply("${Random.nextInt(minOf(a, b), maxOf(a, b) + 1)}.")
        }
        if (has("tell me a joke|make me laugh") && !AppState.online.value) return Reply(JOKES.random())

        Regex("^say\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw.trim())?.let { return Reply(it.groupValues[1]) }
        return null
    }

    private fun rate(ctx: Context, delta: Float): Reply {
        val prefs = Prefs(ctx)
        prefs.speechRate = if (delta == 0f) 1.0f else (prefs.speechRate + delta).coerceIn(0.6f, 1.6f)
        Voice.applySettings(ctx)
        return Reply(if (delta < 0f) "I'll speak more slowly." else if (delta > 0f) "I'll speak faster." else "Back to normal speed.")
    }
}
