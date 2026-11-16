package com.aurelis.app

import android.Manifest
import android.app.ActivityManager
import android.app.DownloadManager
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

/** Switches added with the command library. */
object Opt2 {
    private fun p(ctx: Context) = ctx.getSharedPreferences("aurelis", Context.MODE_PRIVATE)

    /** true = never send anything to the cloud, even when the internet is available. */
    fun forceOffline(ctx: Context) = p(ctx).getBoolean("force_offline", false)
    fun setForceOffline(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("force_offline", v).apply()
}

/**
 * The AURELIS command library: everything here works on the phone itself, with no internet
 * and no special Android access. Returns null when the words are not one of these commands.
 */
object MoreSkills {
    private const val REPO = "eddiegucciw/AURELIS"

    private val WORDS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90, "hundred" to 100
    )
    private val YES = setOf("yes", "yeah", "yep", "sure", "do it", "go ahead", "confirm", "ok", "okay", "yes please", "correct")

    /** Something waiting for the user to say yes. */
    private var pendingName = ""
    private var pendingAt = 0L

    private fun digits(s: String): String {
        val w = s.split(" ")
        val out = ArrayList<String>()
        var i = 0
        while (i < w.size) {
            val t = w[i]
            val tens = TENS[t]
            val unit = WORDS[t]
            if (tens != null) {
                var v: Int = tens
                val next = if (i + 1 < w.size) WORDS[w[i + 1]] else null
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

    private fun view(ctx: Context, url: String) = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun appFor(ctx: Context, category: String) =
        launch(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category))

    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", v)

    // ------------------------------------------------------------------ display

    private fun canWrite(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    private fun askWrite(ctx: Context): Reply {
        launch(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        return Reply(
            "To change the display I need one switch turned on. I've opened it: turn on Allow modify system settings, come back, and ask me again.",
            end = true, leftApp = true
        )
    }

    private fun brightness(ctx: Context, change: String, percent: Int? = null): Reply {
        if (!canWrite(ctx)) return askWrite(ctx)
        val cr = ctx.contentResolver
        return try {
            if (change == "auto") {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                return Reply("Automatic brightness is on.")
            }
            val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val target = when (change) {
                "up" -> cur + 50
                "down" -> cur - 50
                "max" -> 255
                "min" -> 8
                "half" -> 128
                else -> (percent ?: 50).coerceIn(1, 100) * 255 / 100
            }.coerceIn(5, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
            Reply("Brightness at ${target * 100 / 255} percent.")
        } catch (e: Exception) {
            gone(launch(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS)), "This phone wouldn't let me change brightness directly, so I've opened display settings.")
        }
    }

    private fun systemInt(ctx: Context, key: String, value: Int, done: String): Reply {
        if (!canWrite(ctx)) return askWrite(ctx)
        return try {
            Settings.System.putInt(ctx.contentResolver, key, value)
            Reply(done)
        } catch (e: Exception) {
            gone(launch(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS)), "This phone keeps that setting locked, so I've opened display settings for you.")
        }
    }

    private fun fontScale(ctx: Context, delta: Float): Reply {
        if (!canWrite(ctx)) return askWrite(ctx)
        return try {
            val cr = ctx.contentResolver
            val cur = Settings.System.getFloat(cr, Settings.System.FONT_SCALE, 1.0f)
            val target = if (delta == 0f) 1.0f else (cur + delta).coerceIn(0.85f, 1.6f)
            Settings.System.putFloat(cr, Settings.System.FONT_SCALE, target)
            Reply(if (delta == 0f) "Text size back to normal." else if (delta > 0) "Text is bigger now." else "Text is smaller now.")
        } catch (e: Exception) {
            gone(launch(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS)), "This phone keeps text size locked, so I've opened display settings for you.")
        }
    }

    // ------------------------------------------------------------------ sound

    private fun stream(ctx: Context, s: Int, name: String, change: String, percent: Int? = null): Reply {
        val am = ctx.getSystemService(AudioManager::class.java)
        return try {
            val max = am.getStreamMaxVolume(s)
            val cur = am.getStreamVolume(s)
            val step = maxOf(1, max / 7)
            val target = when (change) {
                "up" -> cur + step
                "down" -> cur - step
                "max" -> max
                "mute" -> 0
                else -> ((percent ?: 50).coerceIn(0, 100) * max + 50) / 100
            }.coerceIn(0, max)
            am.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI)
            Reply("$name volume at ${target * 100 / max} percent.")
        } catch (e: Exception) {
            gone(launch(ctx, Intent(Settings.ACTION_SOUND_SETTINGS)), "Do Not Disturb is blocking that, so I've opened sound settings.")
        }
    }

    private fun dndAllowed(ctx: Context) =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun askDnd(ctx: Context): Reply {
        launch(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        return Reply(
            "To switch Do Not Disturb and silent mode I need one permission. I've opened the list: choose AURELIS and allow it, then ask me again.",
            end = true, leftApp = true
        )
    }

    private fun ringer(ctx: Context, mode: Int, done: String): Reply {
        if (mode == AudioManager.RINGER_MODE_SILENT && !dndAllowed(ctx)) return askDnd(ctx)
        return try {
            ctx.getSystemService(AudioManager::class.java).ringerMode = mode
            Reply(done)
        } catch (e: SecurityException) {
            askDnd(ctx)
        }
    }

    private fun dnd(ctx: Context, on: Boolean): Reply {
        if (!dndAllowed(ctx)) return askDnd(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return Reply(if (on) "Do Not Disturb is on." else "Do Not Disturb is off.")
    }

    private fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ------------------------------------------------------------------ phone facts

    private fun gb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1073741824.0)

    private fun storage(): String {
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes
        val total = st.totalBytes
        val pct = if (total > 0) (total - free) * 100 / total else 0
        return "You have ${gb(free)} gigabytes free out of ${gb(total)}. Storage is $pct percent full."
    }

    private fun memory(ctx: Context): String {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return "${gb(mi.availMem)} gigabytes of memory are free out of ${gb(mi.totalMem)}." +
            (if (mi.lowMemory) " The phone is running low on memory." else "")
    }

    private fun network(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "You are offline. There is no internet connection right now."
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "a network"
        }
        val works = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val down = caps.linkDownstreamBandwidthKbps
        return "You are connected through $kind" +
            (if (works) " and the internet is working" else " but the internet isn't getting through") +
            (if (works && down > 0) ", at roughly ${maxOf(1, down / 1000)} megabits per second." else ".")
    }

    private fun charging(ctx: Context): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (bm.isCharging) "Yes, it's charging, at $pct percent." else "No, it's not charging. Battery is at $pct percent."
    }

    private fun granted(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun diagnostics(ctx: Context): String {
        val problems = ArrayList<String>()
        if (!granted(ctx, Manifest.permission.RECORD_AUDIO)) problems.add("the microphone isn't allowed")
        if (!Ears.isInstalled(ctx)) problems.add("the offline speech pack isn't downloaded")
        if (SecureStore.get(ctx, "cloud_key").isBlank()) problems.add("there's no Gemini key")
        if (!AppState.online.value) problems.add("there's no internet")
        if (!granted(ctx, Manifest.permission.READ_CONTACTS)) problems.add("contacts aren't allowed, so I can't call people by name")
        if (!canWrite(ctx)) problems.add("I can't change brightness until you allow modify system settings")
        if (!Settings.canDrawOverlays(ctx)) problems.add("display over other apps is off, so I can't open things from the background")
        if (Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) problems.add("notifications are off, so reminders stay silent")
        val mode = when (Assistant.mode(ctx)) {
            BrainMode.ONLINE -> "online with Gemini"
            BrainMode.OFFLINE -> "offline with the on-device model"
            BrainMode.LIMITED -> "in limited mode with built-in commands only"
        }
        val head = "I'm running $mode. Listening is ${if (AppState.serviceRunning.value) "on" else "off"}."
        return if (problems.isEmpty()) "$head Everything checks out."
        else "$head ${problems.size} thing${if (problems.size > 1) "s" else ""} to fix: ${problems.joinToString("; ")}."
    }

    private fun latestBuild(): String? = try {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "AURELIS-Android")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        JSONObject(body).optString("tag_name").ifBlank { null }
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ contacts

    private fun findContact(ctx: Context, name: String): Pair<String, String>? {
        if (!granted(ctx, Manifest.permission.READ_CONTACTS)) return null
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"), null
        )?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: name) to (c.getString(1) ?: "")
        }
        return null
    }

    /** A phone number in international form for WhatsApp (Kenyan numbers starting with 0 become 254...). */
    private fun intl(number: String): String {
        val d = number.filter { it.isDigit() || it == '+' }
        return when {
            d.startsWith("+") -> d.drop(1)
            d.startsWith("0") && d.length == 10 -> "254" + d.drop(1)
            else -> d
        }
    }

    // ------------------------------------------------------------------ conversions

    private val UNIT_NAMES = mapOf(
        "km" to "kilometres", "kilometre" to "kilometres", "kilometres" to "kilometres", "kilometer" to "kilometres", "kilometers" to "kilometres",
        "mile" to "miles", "miles" to "miles",
        "m" to "metres", "metre" to "metres", "metres" to "metres", "meter" to "metres", "meters" to "metres",
        "foot" to "feet", "feet" to "feet", "ft" to "feet",
        "cm" to "centimetres", "centimetre" to "centimetres", "centimetres" to "centimetres", "centimeter" to "centimetres", "centimeters" to "centimetres",
        "inch" to "inches", "inches" to "inches",
        "kg" to "kilograms", "kilo" to "kilograms", "kilos" to "kilograms", "kilogram" to "kilograms", "kilograms" to "kilograms",
        "pound" to "pounds", "pounds" to "pounds", "lbs" to "pounds",
        "g" to "grams", "gram" to "grams", "grams" to "grams",
        "ounce" to "ounces", "ounces" to "ounces",
        "litre" to "litres", "litres" to "litres", "liter" to "litres", "liters" to "litres",
        "gallon" to "gallons", "gallons" to "gallons",
        "ml" to "millilitres", "millilitre" to "millilitres", "millilitres" to "millilitres", "milliliter" to "millilitres", "milliliters" to "millilitres",
        "celsius" to "celsius", "centigrade" to "celsius", "fahrenheit" to "fahrenheit",
        "hour" to "hours", "hours" to "hours", "minute" to "minutes", "minutes" to "minutes",
        "second" to "seconds", "seconds" to "seconds", "day" to "days", "days" to "days", "week" to "weeks", "weeks" to "weeks",
        "acre" to "acres", "acres" to "acres", "hectare" to "hectares", "hectares" to "hectares"
    )

    /** How many base units one of each unit is; units in the same group convert into each other. */
    private val FACTORS = mapOf(
        "kilometres" to ("length" to 1000.0), "miles" to ("length" to 1609.344), "metres" to ("length" to 1.0),
        "feet" to ("length" to 0.3048), "centimetres" to ("length" to 0.01), "inches" to ("length" to 0.0254),
        "kilograms" to ("mass" to 1000.0), "pounds" to ("mass" to 453.59237), "grams" to ("mass" to 1.0), "ounces" to ("mass" to 28.349523),
        "litres" to ("volume" to 1000.0), "gallons" to ("volume" to 3785.4118), "millilitres" to ("volume" to 1.0),
        "weeks" to ("time" to 604800.0), "days" to ("time" to 86400.0), "hours" to ("time" to 3600.0),
        "minutes" to ("time" to 60.0), "seconds" to ("time" to 1.0),
        "hectares" to ("area" to 10000.0), "acres" to ("area" to 4046.8564)
    )

    private fun convert(value: Double, fromRaw: String, toRaw: String): String? {
        val from = UNIT_NAMES[fromRaw] ?: return null
        val to = UNIT_NAMES[toRaw] ?: return null
        if (from == "celsius" && to == "fahrenheit") return "${fmt(value)} degrees Celsius is ${fmt(value * 9 / 5 + 32)} Fahrenheit."
        if (from == "fahrenheit" && to == "celsius") return "${fmt(value)} degrees Fahrenheit is ${fmt((value - 32) * 5 / 9)} Celsius."
        val a = FACTORS[from] ?: return null
        val b = FACTORS[to] ?: return null
        if (a.first != b.first) return null
        return "${fmt(value)} $from is ${fmt(value * a.second / b.second)} $to."
    }

    // ------------------------------------------------------------------ settings pages

    private fun page(ctx: Context, what: String): Reply? {
        val w = what.trim()
        val intent: Intent = when {
            w.contains("aurelis") || w.contains("your permission") || w.contains("app permission") || w.contains("app info") ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
            w.contains("notification") ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            w.contains("display") || w.contains("screen") || w.contains("dark mode") || w.contains("dark theme") ||
                w.contains("night mode") || w.contains("wallpaper") || w.contains("font") -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            w.contains("sound") || w.contains("ringtone") || w.contains("vibration") || w.contains("audio") -> Intent(Settings.ACTION_SOUND_SETTINGS)
            w.contains("battery saver") || w.contains("power saving") || w.contains("power saver") -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            w.contains("battery") -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            w.contains("storage") -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            w.contains("accessibility") -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            w.contains("privacy") -> Intent(Settings.ACTION_PRIVACY_SETTINGS)
            w.contains("security") || w.contains("fingerprint") || w.contains("screen lock") || w.contains("password") -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            w.contains("default app") -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            w.contains("app") -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            w.contains("date") || w.contains("time") -> Intent(Settings.ACTION_DATE_SETTINGS)
            w.contains("language") -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            w.contains("keyboard") -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            w.contains("developer") -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            w.contains("about") || w.contains("software update") || w.contains("system update") -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            w.contains("nfc") -> Intent(Settings.ACTION_NFC_SETTINGS)
            w.contains("vpn") -> Intent(Settings.ACTION_VPN_SETTINGS)
            w.contains("sim") || w.contains("mobile network") || w.contains("network operator") -> Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
            w.contains("account") || w.contains("sync") -> Intent(Settings.ACTION_SYNC_SETTINGS)
            w.contains("cast") -> Intent(Settings.ACTION_CAST_SETTINGS)
            w.contains("print") -> Intent(Settings.ACTION_PRINT_SETTINGS)
            else -> return null
        }
        val ok = launch(ctx, intent) || launch(ctx, Intent(Settings.ACTION_SETTINGS))
        return gone(ok, "Opening $w settings.")
    }

    // ------------------------------------------------------------------ the commands

    suspend fun handle(ctx: Context, raw: String): Reply? {
        val n = Intents.norm(raw)
        if (n.isEmpty()) return null
        val d = digits(n)
        fun m(pattern: String) = Regex(pattern).find(n)
        fun md(pattern: String) = Regex(pattern).find(d)
        fun has(pattern: String) = Regex(pattern).containsMatchIn(n)
        val app = AurelisApp.instance

        // ---- a yes or no we are waiting for
        if (pendingName.isNotEmpty()) {
            val name = pendingName
            val fresh = System.currentTimeMillis() - pendingAt < 60000
            pendingName = ""
            if (fresh && n in YES) {
                when (name) {
                    "chat" -> {
                        app.db.chat().clear()
                        AppState.messages.value = emptyList()
                        return Reply("Conversation history cleared.")
                    }
                    "history" -> {
                        app.db.history().clear()
                        return Reply("Activity history cleared.")
                    }
                    "key" -> {
                        SecureStore.clear(ctx)
                        return Reply("Your Gemini key has been deleted from this phone. I'll use built-in commands until you add one again in Settings.")
                    }
                    "learned" -> {
                        ctx.getSharedPreferences("aurelis_learned", Context.MODE_PRIVATE).edit().clear().apply()
                        return Reply("I've forgotten every phrase, routine and correction you taught me.")
                    }
                }
            }
            if (fresh && (n == "no" || n == "nope" || n == "cancel" || n == "no thanks")) return Reply("Cancelled. Nothing was changed.")
        }

        // ---- display: brightness, timeout, rotation, text size
        val brightWord = has(
            "brightness|screen light|display light|light of (the |my )?(screen|phone|display)|" +
                "(screen|display|phone)( is| looks)?( too| very| so| a bit)? (dark|dim|dull|bright|brighter|dimmer|darker|lighter)\\b|" +
                "(brighten|dim|darken|lighten)( up)? (the |my )?(screen|display|phone)|^(brighter|dimmer|darker|brighten up)$|" +
                "(make|turn|set|put) (it|the screen|the display|the phone|my screen|my phone) (brighter|dimmer|darker|lighter)|" +
                "i can't see (the|my) screen|^too (dark|bright)$"
        )
        if (brightWord && !has("torch|flashlight|flash light")) {
            if (has("auto(matic)?|adaptive")) return brightness(ctx, "auto")
            md("(\\d{1,3})")?.let { return brightness(ctx, "set", it.groupValues[1].toInt()) }
            return when {
                has("max|full|highest|brightest|all the way up") -> brightness(ctx, "max")
                has("\\bmin|lowest|dimmest|all the way down|as low") -> brightness(ctx, "min")
                has("half|medium|middle") -> brightness(ctx, "half")
                has("too (dark|dim|dull)|can't see|increase|raise|more|\\bup\\b|higher|brighter|brighten|boost|lighter|lighten") -> brightness(ctx, "up")
                has("too bright|decrease|reduce|lower|less|down|dim|darker|darken") -> brightness(ctx, "down")
                has("what|how|current|level|check") -> Reply(
                    "Brightness is at ${Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255} percent."
                )
                else -> brightness(ctx, "up")
            }
        }
        md("(?:screen|display) (?:timeout|time out|sleep)(?: time)?(?: to| at| after)? (\\d{1,3}) (second|minute)s?")?.let {
            val v = it.groupValues[1].toInt() * (if (it.groupValues[2] == "minute") 60000 else 1000)
            return systemInt(ctx, Settings.System.SCREEN_OFF_TIMEOUT, v.coerceIn(15000, 1800000), "The screen will now sleep after ${it.groupValues[1]} ${it.groupValues[2]}s.")
        }
        if (has("keep (the |my )?screen (on|awake)|don't let the screen (sleep|turn off)")) {
            return systemInt(ctx, Settings.System.SCREEN_OFF_TIMEOUT, 1800000, "The screen will stay on for up to 30 minutes without a touch.")
        }
        if (has("(auto|screen) ?rotat(e|ion)|rotate (the |my )?screen|lock (the )?rotation|portrait( mode)? lock")) {
            val off = has("off|disable|stop|lock|portrait|don't")
            return systemInt(ctx, Settings.System.ACCELEROMETER_ROTATION, if (off) 0 else 1, if (off) "Auto-rotate is off." else "Auto-rotate is on.")
        }
        if (has("(bigger|larger|increase|enlarge)( the| my)? (text|font|letters|writing)|(text|font|letters) (size )?(bigger|larger|up)|(i )?can't read the (text|screen)|make (the )?(text|font|letters|writing) (bigger|larger)")) return fontScale(ctx, 0.15f)
        if (has("(smaller|reduce|decrease)( the| my)? (text|font|letters|writing)|(text|font|letters) (size )?(smaller|down)|make (the )?(text|font|letters|writing) smaller")) return fontScale(ctx, -0.15f)
        if (has("(normal|default|reset)( the| my)? (text|font) size|(text|font) size (to |back to )?(normal|default)")) return fontScale(ctx, 0f)

        // ---- sound: ringer, Do Not Disturb, other volumes
        if (has("do not disturb|don't disturb|\\bdnd\\b")) {
            return dnd(ctx, !has("off|disable|stop|end|deactivate|cancel"))
        }
        if (has("silent mode|^(go |be )?silent$|silence (the |my )?(phone|ringer)|put (the |my )?phone on silent")) {
            return ringer(ctx, AudioManager.RINGER_MODE_SILENT, "The phone is on silent.")
        }
        if (has("vibrate mode|vibration mode|put (the |my )?phone on vibrate|^vibrate only$")) {
            return ringer(ctx, AudioManager.RINGER_MODE_VIBRATE, "The phone is on vibrate.")
        }
        if (has("(ring|sound|normal|loud) mode|turn (the )?(ringer|ringing|sound) (back )?on|take (the |my )?phone off silent")) {
            return ringer(ctx, AudioManager.RINGER_MODE_NORMAL, "The ringer is on.")
        }
        md("(ring|ringer|ringtone|alarm|call|notification) volume(?: to| at)?(?: (\\d{1,3}))?")?.let {
            val which = it.groupValues[1]
            val s = when (which) {
                "alarm" -> AudioManager.STREAM_ALARM
                "call" -> AudioManager.STREAM_VOICE_CALL
                "notification" -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_RING
            }
            val label = which.replaceFirstChar { c -> c.uppercase() }
            val pct = it.groupValues[2].toIntOrNull()
            return when {
                pct != null -> stream(ctx, s, label, "set", pct)
                has("max|full|highest") -> stream(ctx, s, label, "max")
                has("\\bup\\b|increase|raise|louder|higher") -> stream(ctx, s, label, "up")
                has("mute|\\boff\\b|zero") -> stream(ctx, s, label, "mute")
                else -> stream(ctx, s, label, "down")
            }
        }
        if (has("^(speak|talk) (up|louder)|^(i )?can't hear you|^you('re| are) too (quiet|soft)")) {
            return stream(ctx, AudioManager.STREAM_MUSIC, "My", "up")
        }
        if (has("^(speak|talk) (softer|quieter|more quietly|lower)|^you('re| are) too loud|^not so loud")) {
            return stream(ctx, AudioManager.STREAM_MUSIC, "My", "down")
        }
        if (has("(change|switch|use)( to)? (a |an |your |the )?(different |another |other |next |new )?voice$|^next voice$|^(i )?don't like your voice")) {
            val names = Voice.voiceNames.value
            if (names.size < 2) return Reply("This phone only has one English voice installed. You can add more in Android's text to speech settings.")
            val next = names[(names.indexOf(app.prefs.voiceName) + 1).coerceAtLeast(0) % names.size]
            app.prefs.voiceName = next
            Voice.applySettings(ctx)
            return Reply("How does this voice sound? Say change voice again to try the next one.")
        }
        if (has("^(stop|fast forward|rewind)( the)? (playback|video|music|song)?$|^fast forward$|^rewind$")) {
            val code = when {
                n.startsWith("fast") -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                n.startsWith("rewind") -> KeyEvent.KEYCODE_MEDIA_REWIND
                else -> KeyEvent.KEYCODE_MEDIA_STOP
            }
            mediaKey(ctx, code)
            return Reply("Done.", end = true)
        }
        if (has("find my phone|where are you|ring (my|the) phone")) {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            return Reply("I'm here! I'm right here! Follow my voice. I'm here!")
        }
        if (has("^vibrate( the phone)?$|^buzz$")) {
            return try {
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
                Reply("Buzz.")
            } catch (e: Exception) {
                Reply("This phone wouldn't vibrate for me.")
            }
        }

        // ---- cloud on or off
        if (has("(go|work|stay|switch to) offline|offline (only )?mode|private mode|(disable|turn off|stop|no) (the )?(cloud|gemini|online)( processing| brain| mode)?$|don't use (the )?(cloud|internet|gemini)")) {
            Opt2.setForceOffline(ctx, true)
            return Reply("Private mode is on. Nothing you say will leave this phone until you tell me to go online. I can still run every built-in command.")
        }
        if (has("(go|come|work|switch to|back) online|online mode|(enable|turn on|use) (the )?(cloud|gemini|online)( processing| brain| mode)?$|(leave|exit|turn off|disable) private mode")) {
            Opt2.setForceOffline(ctx, false)
            return Reply(if (AppState.online.value) "I'm back online." else "Cloud is allowed again, but there's no internet right now.")
        }

        // ---- phone facts and diagnostics
        if (has("(run|do|start)( a| the)? (diagnos|system check|self test|self check|health check)|^diagnos|what('s| is) wrong with you|are you (working|ok|okay)|(test|check) (your |all |the )?(permissions|capabilities|systems)|what permissions do you (have|need)|system status|status report")) {
            return Reply(diagnostics(ctx))
        }
        if (has("(how much |free |available |check (my |the )?)(storage|space|disk)|storage (left|status|space)|is my (phone|storage) full")) return Reply(storage())
        if (has("(how much |free |available |check (my |the )?)(ram|memory)\\b.*(phone|free|left|available|usage|have)?") && has("\\bram\\b|phone memory|memory (free|left|usage|available)|free memory|available memory")) return Reply(memory(ctx))
        if (has("(am i|are we|is the phone|are you) (online|connected|offline)|(check|test|how is|how's) (my |the )?(internet|connection|network|wi fi|wifi|signal)|internet (status|speed|working)|is (the |my )?(internet|wi fi|wifi) (working|on|connected)")) return Reply(network(ctx))
        if (has("is (it|my phone|the phone|the battery) charging|am i charging|charging status")) return Reply(charging(ctx))
        if (has("(what|which) (android|version of android)|android version")) {
            return Reply("This phone runs Android ${Build.VERSION.RELEASE}.")
        }
        if (has("(what|which) (phone|device|model) (is this|am i using|do i have)|phone model|device (name|model|info)|about (this|my) phone")) {
            return Reply("This is a ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}, running Android ${Build.VERSION.RELEASE}.")
        }
        if (has("(what|which) version (are you|of aurelis)|(your|aurelis|app) version|what build (are you|is this)")) {
            val v = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1"
            } catch (e: Exception) {
                "1"
            }
            return Reply("I'm AURELIS version $v.")
        }
        if (has("(check|any|look) for (an |a |new )?updates?|is there (an|a new) update|latest (build|version|release)|update yourself|are you up to date")) {
            if (!AppState.online.value) return Reply("I need the internet to check for a new build.")
            val tag = latestBuild() ?: return Reply("I couldn't reach GitHub just now. Try again in a moment.")
            Updater.check()
            return Reply("The newest build on GitHub is ${tag.replace("-", " ")}. If it's newer than mine, an update card is on my Home screen: tap it to install.")
        }
        if (has("privacy (status|report|check)|what (do|are) you (record|recording|send|sending|upload)|are you (recording|spying|listening to everything)|where does my (voice|audio|data) go")) {
            return Reply(
                "While I'm on standby, sound is checked on this phone only and thrown away; nothing is recorded or uploaded. " +
                    (if (Opt2.forceOffline(ctx)) "Private mode is on, so nothing leaves the phone at all. "
                    else "Only a question I can't answer myself is sent to Gemini, as text. ") +
                    "Say stop listening at any time to switch the microphone off."
            )
        }

        // ---- settings pages
        m("^(?:open|show|go to|take me to|change|adjust)(?: the| my)? (.+?) (?:settings?|options|preferences)$")?.let {
            page(ctx, it.groupValues[1])?.let { r -> return r }
        }
        m("^(?:open|show|go to)(?: the| my)? (?:settings? (?:for|of)) (.+)$")?.let {
            page(ctx, it.groupValues[1])?.let { r -> return r }
        }
        if (has("^(turn on|turn off|enable|disable|switch on|switch off|use|open|show)( the)? (dark|night|light) (mode|theme)$")) return page(ctx, "dark mode")
        if (has("^(turn on|turn off|enable|disable|switch on|switch off|open)( the)? (battery saver|power saving|power saver)( mode)?$")) return page(ctx, "battery saver")
        if (has("^(open|show)( my| your| the)? (app )?permissions$|^(manage|check|fix) (your|my|the|aurelis) permissions$")) return page(ctx, "aurelis")
        if (has("developer options|developer mode")) return page(ctx, "developer")
        if (has("^(change|set)( the| my)? (wallpaper|ringtone|language|screen lock|password|keyboard)")) {
            page(ctx, n)?.let { return it }
        }
        if (has("(system|software|phone|android) update")) return page(ctx, "software update")

        // ---- built-in apps
        m("^(?:open|launch|start|show|go to)(?: the| my)? (downloads?|gallery|photos|pictures|files?|file manager|my files|calendar|calculator|contacts|phone book|music|music player|email|mail|inbox|browser|internet|messages|sms|texts|dial(?:l)?er|phone|phone app|keypad|recent calls|call log|call history|missed calls|play store|app store|clock|stopwatch|timers?|maps?|youtube|whatsapp|notes?)(?: app)?$")?.let {
            val what = it.groupValues[1]
            val ok = when {
                what.startsWith("download") -> launch(ctx, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                what in setOf("gallery", "photos", "pictures") ->
                    appFor(ctx, Intent.CATEGORY_APP_GALLERY) || launch(ctx, Intent(Intent.ACTION_VIEW).setType("image/*"))
                what.contains("file") ->
                    (Build.VERSION.SDK_INT >= 29 && appFor(ctx, "android.intent.category.APP_FILES")) ||
                        launch(ctx, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                what == "calendar" -> appFor(ctx, Intent.CATEGORY_APP_CALENDAR)
                what == "calculator" -> appFor(ctx, Intent.CATEGORY_APP_CALCULATOR)
                what == "contacts" || what == "phone book" ->
                    appFor(ctx, Intent.CATEGORY_APP_CONTACTS) || launch(ctx, Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
                what.startsWith("music") -> appFor(ctx, Intent.CATEGORY_APP_MUSIC)
                what in setOf("email", "mail", "inbox") -> appFor(ctx, Intent.CATEGORY_APP_EMAIL)
                what == "browser" || what == "internet" -> appFor(ctx, Intent.CATEGORY_APP_BROWSER) || view(ctx, "https://www.google.com")
                what in setOf("messages", "sms", "texts") -> appFor(ctx, Intent.CATEGORY_APP_MESSAGING)
                what.startsWith("dial") || what.startsWith("phone") || what == "keypad" -> launch(ctx, Intent(Intent.ACTION_DIAL))
                what.contains("call") -> launch(ctx, Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE)) || launch(ctx, Intent(Intent.ACTION_DIAL))
                what.contains("store") -> view(ctx, "market://search?q=apps") || view(ctx, "https://play.google.com/store")
                what == "clock" || what == "stopwatch" -> launch(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS))
                what.startsWith("timer") -> launch(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS))
                what.startsWith("map") -> appFor(ctx, Intent.CATEGORY_APP_MAPS) || view(ctx, "geo:0,0?q=")
                else -> return@let
            }
            return gone(ok, "Opening $what.")
        }
        m("^(?:install|download|get|find)(?: the)? (.+?) (?:app )?(?:on|in|from) (?:the )?(?:play ?store|app store)$|^(?:search|look in) (?:the )?play ?store for (.+)$|^install (.+)$")?.let {
            val q = it.groupValues.drop(1).first { g -> g.isNotEmpty() }.removeSuffix(" app")
            val ok = view(ctx, "market://search?q=" + Uri.encode(q)) || view(ctx, "https://play.google.com/store/search?q=" + Uri.encode(q))
            return gone(ok, "Here is $q on the Play Store. Tap install if it's the right one.")
        }

        // ---- camera
        if (has("record (a |the )?video|video (camera|recording|mode)|start (video )?recording|open (the )?video camera")) {
            return gone(launch(ctx, Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)), "Video camera open. Tap record when you're ready.")
        }
        if (has("scan (a |the |this )?(qr|bar ?code|code)")) {
            return gone(launch(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), "Camera open. Point it at the code and tap the link that appears.")
        }

        // ---- timers and the clock
        if (has("(cancel|stop|dismiss|delete|end|clear) (the |my |all )?timers?")) {
            val ok = Build.VERSION.SDK_INT >= 28 && launch(ctx, Intent(AlarmClock.ACTION_DISMISS_TIMER))
            return gone(ok || launch(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS)), "Timer cancelled.")
        }
        if (has("(show|check|see|open|how long (is|on)) (the |my )?timers?|how much time (is )?left")) {
            return gone(launch(ctx, Intent(AlarmClock.ACTION_SHOW_TIMERS)), "Here are your timers.")
        }
        md("^snooze(?: (?:the |my )?alarm)?(?: for (\\d{1,2}) minutes?)?$")?.let {
            val i = Intent(AlarmClock.ACTION_SNOOZE_ALARM)
            it.groupValues[1].toIntOrNull()?.let { mins -> i.putExtra(AlarmClock.EXTRA_ALARM_SNOOZE_DURATION, mins) }
            return gone(launch(ctx, i), "Snoozed.")
        }

        // ---- maps
        m("^(?:find|show|search for|look for|where is|where's|where are|any)(?: me)?(?: the| a| an| some)? (?:nearest |closest |nearby )(.+?)(?: near me| nearby| around here| close by)?$")?.let {
            return gone(view(ctx, "geo:0,0?q=" + Uri.encode(it.groupValues[1])), "Here is the nearest ${it.groupValues[1]} on the map.")
        }
        m("^(?:find|show|search for|look for|any)(?: me)?(?: the| a| an| some)? (.+?) (?:near me|nearby|around here|close by|near here|around me)$")?.let {
            return gone(view(ctx, "geo:0,0?q=" + Uri.encode(it.groupValues[1])), "Here are ${it.groupValues[1]} near you.")
        }
        if (has("^where am i$|^(show|what('s| is)) my (current )?location$|^show me (on )?the map$")) {
            return gone(view(ctx, "geo:0,0?q=my+location"), "Here's where you are on the map.")
        }
        m("^(?:how far is|distance to|how long (?:does it take )?to (?:get to|reach)|how do i get to) (.+?)(?: from here)?$")?.let {
            return gone(view(ctx, "google.navigation:q=" + Uri.encode(it.groupValues[1])) || view(ctx, "geo:0,0?q=" + Uri.encode(it.groupValues[1])), "Here's the route to ${it.groupValues[1]}, with distance and time.")
        }
        m("^(?:walk|walking directions|cycle|cycling directions) to (.+)$")?.let {
            val mode = if (n.startsWith("walk")) "w" else "b"
            return gone(view(ctx, "google.navigation:q=" + Uri.encode(it.groupValues[1]) + "&mode=$mode"), "Starting directions to ${it.groupValues[1]}.")
        }
        if (has("(how is|how's|check|show)( the)? traffic")) {
            return gone(view(ctx, "https://www.google.com/maps/@?api=1&map_action=map&layer=traffic"), "Here's the traffic around you.")
        }

        // ---- the web
        m("^(?:search|look) (?:on )?youtube for (.+)$|^(?:youtube|find a video (?:of|about|on)|show me (?:a video|videos) (?:of|about|on)) (.+)$")?.let {
            val q = it.groupValues.drop(1).first { g -> g.isNotEmpty() }
            val ok = launch(ctx, Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, q)) ||
                view(ctx, "https://www.youtube.com/results?search_query=" + Uri.encode(q))
            return gone(ok, "Searching YouTube for $q.")
        }
        m("^(?:search|look up|check) (?:on )?wikipedia (?:for|about) (.+)$|^wikipedia (.+)$|^(?:search|look up) (.+) on wikipedia$")?.let {
            val q = it.groupValues.drop(1).first { g -> g.isNotEmpty() }
            return gone(view(ctx, "https://en.m.wikipedia.org/wiki/Special:Search?search=" + Uri.encode(q)), "Here is Wikipedia on $q.")
        }
        m("^(?:search|find|show me|look for) (?:images|pictures|photos) (?:of|for) (.+)$")?.let {
            return gone(view(ctx, "https://www.google.com/search?tbm=isch&q=" + Uri.encode(it.groupValues[1])), "Here are pictures of ${it.groupValues[1]}.")
        }
        m("^(?:open|go to|visit|browse to|load)(?: the)?(?: website| site| page)? ((?:[a-z0-9-]+ dot )+[a-z]{2,})$")?.let {
            val host = it.groupValues[1].replace(" dot ", ".")
            return gone(view(ctx, "https://$host"), "Opening $host.")
        }
        m("^(?:open|go to|visit|browse to|load)(?: the)?(?: website| site| page)? ((?:[a-z0-9-]+\\.)+[a-z]{2,}(?:/\\S*)?)$")?.let {
            return gone(view(ctx, "https://" + it.groupValues[1]), "Opening ${it.groupValues[1]}.")
        }
        m("^(?:what's|what is|show me|read|get|open)?(?: me)?(?: the| today's| latest)* ?(?:news|headlines)(?: today)?(?: (?:about|on|in|from) (.+))?$")?.let {
            val topic = it.groupValues[1]
            val url = if (topic.isEmpty()) "https://news.google.com" else "https://news.google.com/search?q=" + Uri.encode(topic)
            return gone(view(ctx, url), if (topic.isEmpty()) "Here are today's headlines." else "Here is the latest news on $topic.")
        }

        // ---- messages and people (drafts only: you always press send yourself)
        m("^(?:send (?:a )?whatsapp(?: message)? to|whatsapp) (.+?) (?:saying|that says|to say|and say|that) (.+)$")?.let {
            val who = it.groupValues[1]
            val msg = it.groupValues[2].replaceFirstChar { c -> c.uppercase() }
            val contact = findContact(ctx, who)
                ?: return Reply(
                    if (!granted(ctx, Manifest.permission.READ_CONTACTS)) "I need permission to see your contacts first. Open my permissions and allow Contacts."
                    else "I couldn't find $who in your contacts."
                )
            val ok = view(ctx, "https://wa.me/" + intl(contact.second) + "?text=" + Uri.encode(msg))
            return gone(ok, "Your WhatsApp message to ${contact.first} is ready. Check it and tap send.")
        }
        m("^(?:open )?(?:whatsapp|whatsapp chat|chat) (?:with|for) (.+)$")?.let {
            val contact = findContact(ctx, it.groupValues[1]) ?: return Reply("I couldn't find ${it.groupValues[1]} in your contacts.")
            return gone(view(ctx, "https://wa.me/" + intl(contact.second)), "Opening your chat with ${contact.first}.")
        }
        m("^(?:send (?:an )?email to|email|write (?:an )?email to|compose (?:an )?email to|draft (?:an )?email to) (.+?)(?: (?:about|with subject|subject) (.+?))?(?: (?:saying|that says|body) (.+))?$")?.let {
            val to = it.groupValues[1].replace(" at ", "@").replace(" dot ", ".").replace(" ", "")
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + (if (to.contains("@")) to else "")))
            if (it.groupValues[2].isNotEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, it.groupValues[2].replaceFirstChar { c -> c.uppercase() })
            if (it.groupValues[3].isNotEmpty()) i.putExtra(Intent.EXTRA_TEXT, it.groupValues[3].replaceFirstChar { c -> c.uppercase() })
            return gone(launch(ctx, i), "Your email draft is open. Check it and tap send.")
        }
        md("^(?:add|create|save|new)(?: a| new)? contact (?:called |named |for )?([a-z ]+?)(?: (?:with |on |at )?(?:number |phone )?([0-9 +]{7,}))?$")?.let {
            val i = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                .putExtra(ContactsContract.Intents.Insert.NAME, it.groupValues[1].split(" ").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } })
            if (it.groupValues[2].isNotBlank()) i.putExtra(ContactsContract.Intents.Insert.PHONE, it.groupValues[2].replace(" ", ""))
            return gone(launch(ctx, i), "I've filled in the contact. Tap save to keep it.")
        }
        m("^(?:what is|what's|find|get|tell me) (.+?)(?:'s| s)? (?:phone )?number$")?.let {
            val contact = findContact(ctx, it.groupValues[1]) ?: return@let
            return Reply("${contact.first}'s number is ${contact.second.toCharArray().joinToString(" ")}.")
        }
        m("^(?:share|send)(?: this| the)? (?:text|message|note)? ?(.+?) (?:with|to|via|using|on) (?:another |an |other )?apps?$")?.let {
            val i = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.groupValues[1]), "Share")
            return gone(launch(ctx, i), "Choose where to share it.")
        }
        m("^copy (.+?)(?: to (?:the |my )?clipboard)?$")?.let {
            if (it.groupValues[1] in setOf("that", "this", "it", "your answer", "your reply", "the answer")) {
                val last = AppState.messages.value.lastOrNull { msg -> msg.role == "assistant" }?.text ?: return Reply("There's nothing to copy yet.")
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AURELIS", last))
                return Reply("Copied my last answer.")
            }
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AURELIS", it.groupValues[1]))
            return Reply("Copied.")
        }

        // ---- memory
        m("^(?:search|look in|check) (?:my |your |the )?memor(?:y|ies) (?:for|about) (.+)$|^what do you (?:know|remember) about (.+)$|^do you remember (?:anything about )?(.+)$")?.let {
            val q = it.groupValues.drop(1).first { g -> g.isNotEmpty() }
            val all = app.db.memories().list()
            if (q == "me" || q == "myself") {
                if (all.isEmpty()) return Reply("I don't know anything about you yet. Tell me things, or say remember that, and I'll keep them.")
                return Reply("Here's what I know. " + all.takeLast(6).joinToString(". ") { mem -> mem.content.trimEnd('.') } + ".")
            }
            val words = q.split(" ").filter { w -> w.length > 2 }
            val hits = all.filter { mem -> val c = mem.content.lowercase(Locale.ROOT); words.any { w -> c.contains(w) } }
            if (hits.isEmpty()) return null
            return Reply("I remember: " + hits.take(5).joinToString(". ") { mem -> mem.content.trimEnd('.') } + ".")
        }
        if (has("how many (things|memories|facts) (do you|have you) (remember|know|learn(ed|t)|stored|saved)")) {
            val count = app.db.memories().list().size
            return Reply("I'm keeping $count memor${if (count == 1) "y" else "ies"}, ${Learned.taught(ctx).size} taught phrases and ${Learned.fixCount(ctx)} corrections.")
        }

        // ---- routines (stored the same way as taught phrases)
        m("^(?:create|make|add|save|new)(?: a| the)?(?: new)? routine (?:called |named )?(.+?) (?:that does|that will|that|which|to|and it should|it should|:) (.+)$")?.let {
            val name = it.groupValues[1].removeSuffix(" routine")
            Learned.teach(ctx, name, it.groupValues[2])
            return Reply("Routine $name saved. Say $name, or run $name, and I'll ${it.groupValues[2]}.")
        }
        if (has("^(list|show|what are|read)( me)?( all)?( my| the)? (routines|shortcuts|automations)$")) {
            val list = Learned.taught(ctx)
            if (list.isEmpty()) return Reply("You have no routines yet. Say: create a routine called good night that does turn off the torch and then set an alarm for 6 a m.")
            return Reply("Your routines: " + list.take(8).joinToString("; ") { r -> "${r.first}, which does ${r.second}" } + ".")
        }
        m("^(?:delete|remove|forget|cancel)(?: the| my)? (?:routine|shortcut|automation) (?:called |named )?(.+)$|^(?:delete|remove|forget)(?: the| my)? (.+) routine$")?.let {
            val name = it.groupValues.drop(1).first { g -> g.isNotEmpty() }
            return Reply(if (Learned.forget(ctx, name)) "Routine $name deleted." else "I don't have a routine called $name.")
        }

        // ---- the project
        if (has("(open|show)( my| the| our)? (github|git hub|repository|repo|source code|project page)")) {
            return gone(view(ctx, "https://github.com/$REPO"), "Opening the AURELIS project on GitHub.")
        }
        if (has("(open|show)( my| the)? (builds?|releases?|actions)( page)?$")) {
            return gone(view(ctx, "https://github.com/$REPO/releases"), "Here are the AURELIS builds.")
        }

        // ---- things that need a yes first
        if (has("(clear|delete|erase|wipe|reset)( all)?( the| my| our| this)? (conversation|chat)s?( history)?$")) {
            pendingName = "chat"; pendingAt = System.currentTimeMillis()
            return Reply("Delete the whole conversation history? Say yes to confirm.")
        }
        if (has("(clear|delete|erase|wipe)( all)?( the| my)? (activity|action|command) (history|log)$")) {
            pendingName = "history"; pendingAt = System.currentTimeMillis()
            return Reply("Delete the activity history? Say yes to confirm.")
        }
        if (has("(delete|remove|erase|forget)( my| the)? (api |gemini |cloud )+key")) {
            pendingName = "key"; pendingAt = System.currentTimeMillis()
            return Reply("Delete your Gemini key from this phone? Say yes to confirm.")
        }
        if (has("(forget|delete|erase|clear) (everything|all)( that)? (i|you) (taught|learn(ed|t))|(reset|clear) (your |the )?(learning|taught phrases|routines)")) {
            pendingName = "learned"; pendingAt = System.currentTimeMillis()
            return Reply("Forget every phrase, routine and correction you taught me? Say yes to confirm.")
        }

        // ---- sums and conversions
        md("^(?:convert |what is |what's |how much is |how many [a-z]+ (?:is|are|in) )?(-?\\d+(?:\\.\\d+)?) ?(?:degrees? )?([a-z]+) (?:to|in|into|as) ([a-z]+)$")?.let {
            convert(it.groupValues[1].toDouble(), it.groupValues[2], it.groupValues[3])?.let { r -> return Reply(r) }
        }
        md("^how many ([a-z]+) (?:are |is )?(?:there )?in (-?\\d+(?:\\.\\d+)?|a|an) ([a-z]+)$")?.let {
            val v = it.groupValues[2].toDoubleOrNull() ?: 1.0
            convert(v, it.groupValues[3], it.groupValues[1])?.let { r -> return Reply(r) }
        }
        md("^(?:what is |what's )?(?:the )?square root of (\\d+(?:\\.\\d+)?)$")?.let {
            return Reply("The square root of ${it.groupValues[1]} is ${fmt(Math.sqrt(it.groupValues[1].toDouble()))}.")
        }
        md("^(?:what is |what's )?(\\d+(?:\\.\\d+)?) (squared|cubed)$")?.let {
            val v = it.groupValues[1].toDouble()
            return Reply("${it.groupValues[1]} ${it.groupValues[2]} is ${fmt(if (it.groupValues[2] == "squared") v * v else v * v * v)}.")
        }
        md("^(?:what is |what's )?(\\d+(?:\\.\\d+)?) to the power (?:of )?(\\d+(?:\\.\\d+)?)$")?.let {
            return Reply("That's ${fmt(Math.pow(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()))}.")
        }
        md("^(?:what is |what's |calculate )?(?:a )?(\\d+(?:\\.\\d+)?) percent (?:tip|discount|off|increase|decrease|vat|tax) (?:on|of|from) (\\d+(?:\\.\\d+)?)$")?.let {
            val pct = it.groupValues[1].toDouble()
            val base = it.groupValues[2].toDouble()
            val part = base * pct / 100
            val less = has("discount|off|decrease")
            return Reply("${fmt(pct)} percent of ${fmt(base)} is ${fmt(part)}, so the total is ${fmt(if (less) base - part else base + part)}.")
        }
        md("^(?:split|divide) (\\d+(?:\\.\\d+)?) (?:between|among|by|for) (\\d+)(?: people| of us| ways)?$")?.let {
            val people = it.groupValues[2].toInt()
            if (people > 0) return Reply("That's ${fmt(it.groupValues[1].toDouble() / people)} each.")
        }

        // ---- things only an Accessibility service could do
        if (has("^(read|what's on|what is on|describe|summari[sz]e) (the |my |this )?screen|^type |^(press|tap|click|select|long press|double tap) |^(swipe|scroll|drag|pinch|zoom) |^(take a |capture )?screenshot|^lock (the |my )?(phone|screen)|^(close|switch|kill) (this |the |all )?apps?|^(read|check|clear|dismiss) (my |the |all )?notifications")) {
            return Reply(
                "That one needs Android's Accessibility access, which lets an app see and touch the screen. " +
                    "Google Play Protect blocks apps installed outside the Play Store from having it, so I leave it out to keep installing me simple. " +
                    "Everything else in your command library I can do."
            )
        }
        if (has("^(go to )?(sleep|standby|stand by)$|^(that's|that is) all( for now)?$|^(rest|relax|wait)( now| for now)?$")) {
            return Reply("Standing by.", end = true)
        }
        return null
    }
}
