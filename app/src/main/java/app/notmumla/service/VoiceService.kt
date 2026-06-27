package app.notmumla.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.notmumla.MainActivity
import app.notmumla.R
import app.notmumla.data.SessionManager
import app.notmumla.protocol.model.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the Mumble session + audio pipeline alive while the app is
 * backgrounded, and surfaces an ongoing notification with mute / disconnect actions.
 *
 * The session itself lives in the singleton [SessionManager]; this service owns the foreground
 * lifecycle and notification. Declared with foregroundServiceType="microphone|connectedDevice".
 */
@AndroidEntryPoint
class VoiceService : Service() {

    @Inject lateinit var sessionManager: SessionManager

    private val scope = CoroutineScope(SupervisorJob())
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us (sticky). There is no live session in a fresh
        // process and starting a microphone FGS from the background is disallowed — just bail out.
        if (intent == null || sessionManager.state.value.connection == ConnectionState.DISCONNECTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote to foreground immediately to satisfy the startForegroundService contract. Guard
        // against the background-start restriction so a race can never crash the app.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_TOGGLE_MUTE -> {
                val muted = sessionManager.state.value.self?.selfMute ?: false
                sessionManager.setMicMuted(!muted)
                sessionManager.setSelfMuteDeaf(!muted, sessionManager.state.value.self?.selfDeaf ?: false)
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
            }
            ACTION_DISCONNECT -> { sessionManager.disconnect(); stopSelf(); return START_NOT_STICKY }
        }

        if (observeJob == null) {
            observeJob = scope.launch {
                combine(sessionManager.state, sessionManager.serverLabel) { s, label -> s to label }
                    .collect { (state, _) ->
                        if (state.connection == ConnectionState.DISCONNECTED ||
                            state.connection == ConnectionState.FAILED
                        ) {
                            stopSelf()
                        } else {
                            notificationManager.notify(NOTIFICATION_ID, buildNotification())
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }

    private val notificationManager get() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Voice session", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Ongoing Mumble voice connection" }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val state = sessionManager.state.value
        val label = sessionManager.serverLabel.value.ifBlank { "Mumble" }
        val muted = state.self?.selfMute ?: false
        val statusText = when (state.connection) {
            ConnectionState.CONNECTED -> if (muted) "Connected · muted" else "Connected"
            ConnectionState.CONNECTING -> "Connecting…"
            ConnectionState.HANDSHAKING -> "Authenticating…"
            else -> "Disconnected"
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(label)
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (muted) "Unmute" else "Mute", action(ACTION_TOGGLE_MUTE))
            .addAction(0, "Disconnect", action(ACTION_DISCONNECT))
            .build()
    }

    private fun action(name: String): PendingIntent {
        val intent = Intent(this, VoiceService::class.java).setAction(name)
        return PendingIntent.getService(
            this, name.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_MUTE = "app.notmumla.action.TOGGLE_MUTE"
        const val ACTION_DISCONNECT = "app.notmumla.action.DISCONNECT"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
