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
 * lifecycle and notification. Declared with foregroundServiceType="microphone" only: every declared
 * type is claimed on start, and connectedDevice needs FOREGROUND_SERVICE_CONNECTED_DEVICE + a granted
 * runtime permission — without them startForeground throws, there is no FGS, and the OS silences
 * the mic (and mutes playback) as soon as the screen turns off.
 */
@AndroidEntryPoint
class VoiceService : Service() {

    @Inject lateinit var sessionManager: SessionManager

    private val scope = CoroutineScope(SupervisorJob())
    private var observeJob: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    /**
     * A foreground service keeps the process alive but does not stop the CPU from suspending in Doze
     * when the screen is off — which would stall the audio threads and drop the TCP tunnel. Hold a
     * partial wake lock (CPU) + a low-latency Wi-Fi lock (radio) for the duration of the call.
     */
    @android.annotation.SuppressLint("WakelockTimeout") // released on disconnect/onDestroy, not timed
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "notmumla:voice")
                .also { it.setReferenceCounted(false); runCatching { it.acquire() } }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "notmumla:voice",
            ).also { it.setReferenceCounted(false); runCatching { it.acquire() } }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        wifiLock = null
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
            androidx.core.app.ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "startForeground failed; voice will stop when backgrounded", t)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()

        when (intent.action) {
            ACTION_TOGGLE_MUTE -> {
                val self = sessionManager.state.value.self
                applyMuteDeaf(mute = !(self?.selfMute ?: false), deaf = self?.selfDeaf ?: false)
            }
            ACTION_TOGGLE_DEAFEN -> {
                val self = sessionManager.state.value.self
                val newDeaf = !(self?.selfDeaf ?: false)
                // Deafening implies muting; undeafening leaves the prior mute state.
                applyMuteDeaf(mute = newDeaf || (self?.selfMute ?: false), deaf = newDeaf)
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
        releaseLocks()
        super.onDestroy()
    }

    private fun applyMuteDeaf(mute: Boolean, deaf: Boolean) {
        sessionManager.setMicMuted(mute)
        sessionManager.setSelfMuteDeaf(mute, deaf)
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
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
        val label = sessionManager.serverLabel.value.ifBlank { "Grumbla" }
        val muted = state.self?.selfMute ?: false
        val deafened = state.self?.selfDeaf ?: false
        val statusText = when (state.connection) {
            ConnectionState.CONNECTED -> when {
                deafened -> "Connected · deafened"
                muted -> "Connected · muted"
                else -> "Connected"
            }
            ConnectionState.CONNECTING -> "Connecting…"
            ConnectionState.HANDSHAKING -> "Authenticating…"
            else -> "Disconnected"
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(label)
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (muted) "Unmute" else "Mute", action(ACTION_TOGGLE_MUTE))
            .addAction(0, if (deafened) "Undeafen" else "Deafen", action(ACTION_TOGGLE_DEAFEN))
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
        private const val TAG = "VoiceService"
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_MUTE = "app.notmumla.action.TOGGLE_MUTE"
        const val ACTION_TOGGLE_DEAFEN = "app.notmumla.action.TOGGLE_DEAFEN"
        const val ACTION_DISCONNECT = "app.notmumla.action.DISCONNECT"

        fun start(context: Context) {
            // Throws ForegroundServiceStartNotAllowedException if called from the background.
            runCatching { context.startForegroundService(Intent(context, VoiceService::class.java)) }
                .onFailure { android.util.Log.e(TAG, "startForegroundService failed", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceService::class.java))
        }
    }
}
