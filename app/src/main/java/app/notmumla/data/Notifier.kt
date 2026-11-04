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
        // Silent: the join chime (if on) already plays from SessionManager.
        manager.createNotificationChannel(
            NotificationChannel(ACTIVITY_CHANNEL_ID, "Channel activity", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Users joining and recording, while the app is in the background" },
        )
    }

    /**
     * Show a message notification. [mention] and [isPrivate] (a message addressed only to us) raise
     * priority and enable sound/vibration.
     */
    fun postMessage(sender: String, text: String, mention: Boolean, sound: Boolean, isPrivate: Boolean = false) {
        val urgent = mention || isPrivate
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(when { isPrivate -> "Private message from $sender"; mention -> "$sender mentioned you"; else -> sender })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)

        val defaults = when {
            urgent && sound -> NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE
            urgent -> NotificationCompat.DEFAULT_VIBRATE
            else -> 0
        }
        builder.setDefaults(defaults)

        NotificationManagerCompat.from(context).notify(nextId++, builder.build())
        if (nextId > 2100) nextId = 2000
    }

    /**
     * A low-priority, auto-cancelled channel-activity notification (NEXT 2.4). Posts with the same [tag]
     * replace each other, so a run of joins or one user's start/stop recording doesn't pile up.
     */
    fun postActivity(tag: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, ACTIVITY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(context).notify(tag, ACTIVITY_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "messages"
        const val ACTIVITY_CHANNEL_ID = "channel_activity"
        const val ACTIVITY_ID = 3000
    }
}
