package app.notmumla.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that will own the live Mumble connection and audio pipeline so voice
 * keeps running when the app is backgrounded. Declared in the manifest with
 * foregroundServiceType="microphone|connectedDevice".
 *
 * Milestone 1: stub. Wired up with notification + connection ownership in M2–M4.
 */
class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }
}
