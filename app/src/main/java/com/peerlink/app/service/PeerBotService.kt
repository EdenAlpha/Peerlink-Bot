package com.peerlink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.peerlink.app.bot.BotMode
import com.peerlink.app.core.AppState

/**
 * Bot mode's lifeline: a foreground service that keeps the whole bot session
 * alive while the user plays in eFootball.
 *
 * Without it Android freezes the app the moment the user switches away, and
 * the bot never answers the game's "To Match" -- the 2026-10-15 "Waiting for
 * Opponent" forever case: the game pressed To Match, the bot side never did.
 *
 * Shows a persistent notification with the room number and the LIVE status
 * ("waiting for your game to join...", "ready sent...", "CONNECTED...") so
 * the bot's progress is visible without opening the app. START_STICKY.
 */
class PeerBotService : Service() {

    companion object {
        private const val CHANNEL_ID = "peerbot_foreground"
        private const val NOTIF_ID = 2010
        const val EXTRA_ROOM = "room_number"
        const val ACTION_STOP = "com.peerlink.BOT_STOP"

        /** Bot mode: ask the UI to raise the one-time VPN permission dialog. */
        const val ACTION_VPN_CONSENT = "com.peerlink.BOT_VPN_CONSENT"

        @Volatile
        var running = false
            private set

        @Volatile
        private var roomNumber = ""

        @Volatile
        private var statusText = "starting..."

        @Volatile
        private var active: PeerBotService? = null

        /** Start (or re-arm) the bot session lifeline for [room]. */
        fun start(context: Context, room: String) {
            roomNumber = room
            statusText = "waiting for your game to join..."
            val intent = Intent(context, PeerBotService::class.java).apply {
                putExtra(EXTRA_ROOM, room)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PeerBotService::class.java))
        }

        /** Live status line (notification + log). Called by the match driver. */
        fun status(text: String) {
            statusText = text
            AppState.appendLog("[BOT-SVC   ] $text")
            active?.refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        running = true
        BotMode.restoreIfActive(this)
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(statusText),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildNotification(statusText))
        }
        AppState.appendLog("[BOT-SVC   ] bot session service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_ROOM)?.let { if (it.isNotBlank()) roomNumber = it }
        refresh()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The whole point of this service: survive the user living inside
        // eFootball. Log it; START_STICKY keeps us scheduled if killed.
        AppState.appendLog("[BOT-SVC   ] app removed from recents -- bot session stays up")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        active = null
        running = false
        AppState.appendLog("[BOT-SVC   ] bot session service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refresh() {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(statusText))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "PeerLink Bot", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bot match room status"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PeerBotService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (roomNumber.isBlank()) "PeerLink Bot" else "PeerLink Bot - room $roomNumber"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
