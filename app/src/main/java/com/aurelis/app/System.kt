package com.aurelis.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import kotlinx.coroutines.launch

/** Schedules reminders with AlarmManager so they fire offline and while the phone sleeps. */
object Reminders {
    private fun pi(ctx: Context, r: Reminder): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, r.id.toInt(),
            Intent(ctx, ReminderReceiver::class.java).putExtra("id", r.id).putExtra("text", r.text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = maxOf(r.triggerAt, System.currentTimeMillis() + 2_000)
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, r))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, r))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx, r))
        }
    }

    fun cancel(ctx: Context, r: Reminder) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, r))
    }

    fun rescheduleAll(ctx: Context) {
        Bg.scope.launch {
            AurelisApp.instance.db.reminders().pending().forEach { schedule(ctx, it) }
        }
    }

    fun notify(ctx: Context, id: Int, title: String, text: String, openApp: Boolean = true) {
        val open = PendingIntent.getActivity(
            ctx, 100 + id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, AurelisApp.CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_core)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { if (openApp) setContentIntent(open) }
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
            // Notification permission not granted.
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0L)
        val text = intent.getStringExtra("text") ?: "Reminder"
        Reminders.notify(context, 5000 + (id % 10000).toInt(), "AURELIS reminder", text)
        if (AurelisService.running) {
            context.startService(
                Intent(context, AurelisService::class.java)
                    .setAction(AurelisService.ACTION_ANNOUNCE)
                    .putExtra("text", "Reminder: $text")
            )
        }
        val pending = goAsync()
        Bg.scope.launch {
            try {
                val db = AurelisApp.instance.db
                db.reminders().get(id)?.let { db.reminders().update(it.copy(done = true)) }
                db.history().insert(HistoryRow(time = System.currentTimeMillis(), text = "Reminder delivered: $text"))
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.rescheduleAll(context)
        if (Prefs(context).listeningWanted) {
            // Android 14+ forbids starting the microphone from boot; ask the user to tap instead.
            if (Build.VERSION.SDK_INT < 34) {
                try {
                    context.startForegroundService(
                        Intent(context, AurelisService::class.java).setAction(AurelisService.ACTION_START)
                    )
                    return
                } catch (e: Exception) {
                    // fall through to the notification
                }
            }
            Reminders.notify(context, 4001, "AURELIS is paused", "Tap to resume listening for \"Hey AURELIS\".")
        }
    }
}

/** Reads notifications only after the user grants Notification access in Android settings. */
class NotificationReader : NotificationListenerService() {

    companion object {
        private val recent = ArrayDeque<String>()

        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotificationReader::class.java).flattenToString()
            return flat.split(":").any { it == me }
        }

        fun latest(): List<String> = synchronized(recent) { recent.toList() }

        /** The running listener, while Android has it connected. */
        @Volatile
        var instance: NotificationReader? = null
            private set

        /** Clears every notification that can be cleared. Returns false without notification access. */
        fun clearAll(): Boolean {
            val me = instance ?: return false
            return try {
                me.cancelAllNotifications()
                synchronized(recent) { recent.clear() }
                true
            } catch (e: Exception) {
                false
            }
        }

        fun summary(ctx: Context): String {
            if (!enabled(ctx)) return "I don't have notification access yet. You can enable it in Settings, under Permissions."
            val list = latest()
            if (list.isEmpty()) return "You have no new notifications."
            return "Your latest notifications: " + list.take(5).joinToString(". ") + "."
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val extras = sbn.notification?.extras ?: return
        if (sbn.isOngoing) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        val line = "$app, $title: ${text.take(140)}"
        synchronized(recent) {
            recent.addFirst(line)
            while (recent.size > 20) recent.removeLast()
        }
    }
}
