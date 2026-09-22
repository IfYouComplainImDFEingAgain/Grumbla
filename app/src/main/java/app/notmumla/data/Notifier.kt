package app.notmumla.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.notmumla.MainActivity
import app.notmumla.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts chat/mention notifications (separate from the ongoing voice-service notification). */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var nextId = 2000

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Chat messages and mentions" },
        )
    }

    /** Show a message notification. [mention] raises priority and enables sound/vibration. */
    fun postMessage(sender: String, text: String, mention: Boolean, sound: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(if (mention) "$sender mentioned you" else sender)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(if (mention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)

        val defaults = when {
            mention && sound -> NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE
            mention -> NotificationCompat.DEFAULT_VIBRATE
            else -> 0
        }
        builder.setDefaults(defaults)

        NotificationManagerCompat.from(context).notify(nextId++, builder.build())
        if (nextId > 2100) nextId = 2000
    }

    private companion object {
        const val CHANNEL_ID = "messages"
    }
}
