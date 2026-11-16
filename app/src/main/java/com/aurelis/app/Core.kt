package com.aurelis.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Visual + logical state of the assistant, shown by the AI core. */
enum class CoreState(val label: String) {
    IDLE("Ready"),
    STANDBY("Standby · listening for \"Hey AURELIS\""),
    WAKE("Activated"),
    LISTENING("Listening…"),
    THINKING("Thinking…"),
    SPEAKING("Speaking…"),
    OFFLINE("Offline"),
    ERROR("Attention needed")
}

enum class BrainMode(val label: String) {
    ONLINE("Online intelligence"),
    OFFLINE("Offline intelligence"),
    LIMITED("Limited mode")
}

data class Msg(val role: String, val text: String, val time: Long = System.currentTimeMillis())

/** Shared, observable app state (UI and service both read/write it). */
object AppState {
    val core = MutableStateFlow(CoreState.IDLE)
    val online = MutableStateFlow(false)
    val serviceRunning = MutableStateFlow(false)
    val wakeActive = MutableStateFlow(false)
    val heard = MutableStateFlow("")
    val status = MutableStateFlow("")
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val speechDownload = MutableStateFlow(-1)            // -1 idle, 0..100 progress
    val modelDownload = MutableStateFlow<Pair<String, Int>?>(null)
    val uiVisible = MutableStateFlow(false)

    fun add(role: String, text: String) {
        messages.update { (it + Msg(role, text)).takeLast(300) }
    }
}

/** App-wide background scope. */
object Bg {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** Simple typed settings. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("aurelis", Context.MODE_PRIVATE)

    var cloudModel: String
        get() = p.getString("cloud_model", DEFAULT_CLOUD_MODEL)?.ifBlank { DEFAULT_CLOUD_MODEL } ?: DEFAULT_CLOUD_MODEL
        set(v) = p.edit().putString("cloud_model", v.trim()).apply()
    var speechRate: Float
        get() = p.getFloat("speech_rate", 1.0f)
        set(v) = p.edit().putFloat("speech_rate", v).apply()
    var voiceName: String
        get() = p.getString("voice_name", "") ?: ""
        set(v) = p.edit().putString("voice_name", v).apply()
    var wakeExtra: String
        get() = p.getString("wake_extra", "") ?: ""
        set(v) = p.edit().putString("wake_extra", v).apply()
    var listeningWanted: Boolean
        get() = p.getBoolean("listening_wanted", false)
        set(v) = p.edit().putBoolean("listening_wanted", v).apply()
    var autoOpen: Boolean
        get() = p.getBoolean("auto_open", true)
        set(v) = p.edit().putBoolean("auto_open", v).apply()
    var googleSpeechOnline: Boolean
        get() = p.getBoolean("google_speech_online", true)
        set(v) = p.edit().putBoolean("google_speech_online", v).apply()
    var saveConversations: Boolean
        get() = p.getBoolean("save_conversations", true)
        set(v) = p.edit().putBoolean("save_conversations", v).apply()
    var useMemory: Boolean
        get() = p.getBoolean("use_memory", true)
        set(v) = p.edit().putBoolean("use_memory", v).apply()
    var speakTyped: Boolean
        get() = p.getBoolean("speak_typed", true)
        set(v) = p.edit().putBoolean("speak_typed", v).apply()
    var localModelId: String
        get() = p.getString("local_model", "") ?: ""
        set(v) = p.edit().putString("local_model", v).apply()

    companion object {
        const val DEFAULT_CLOUD_MODEL = "gemini-3.5-flash-lite"
    }
}

/** Stores secrets (the cloud API key) encrypted with a key held in Android Keystore. */
object SecureStore {
    private const val ALIAS = "aurelis_secrets"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("aurelis_secure", Context.MODE_PRIVATE)

    fun put(ctx: Context, name: String, value: String) {
        if (value.isBlank()) {
            prefs(ctx).edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(enc, Base64.NO_WRAP)
        prefs(ctx).edit().putString(name, packed).apply()
    }

    fun get(ctx: Context, name: String): String {
        val packed = prefs(ctx).getString(name, null) ?: return ""
        return try {
            val parts = packed.split(":")
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun clear(ctx: Context) = prefs(ctx).edit().clear().apply()
}

class AurelisApp : Application() {

    companion object {
        const val CH_STANDBY = "aurelis_standby"
        const val CH_ALERTS = "aurelis_alerts"
        lateinit var instance: AurelisApp
            private set
    }

    val prefs by lazy { Prefs(this) }
    val db by lazy { AurelisDb.get(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        Voice.init(this)
        watchConnectivity()
        Bg.scope.launch {
            // Restore recent conversation so the Intelligence screen isn't empty after restart.
            if (prefs.saveConversations) {
                val rows = db.chat().recent(60).reversed()
                AppState.messages.value = rows.map { Msg(it.role, it.text, it.time) }
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STANDBY, "AURELIS listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while AURELIS listens for \"Hey AURELIS\""
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "AURELIS alerts & reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders and activation prompts"
            }
        )
    }

    private fun watchConnectivity() {
        val cm = getSystemService(ConnectivityManager::class.java)
        fun refresh() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            AppState.online.value = caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        refresh()
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
            })
        } catch (e: Exception) {
            // Ignore: we keep the last known state.
        }
    }
}
