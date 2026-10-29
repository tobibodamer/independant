package app.pendant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.pendant.MainActivity
import app.pendant.R
import app.pendant.recording.RecordingController
import app.pendant.recording.RecordingService
import app.pendant.settings.StatusBarIndicator

object Notifications {
    const val CH_STATUS_ICON = "status_icon"
    const val CH_STATUS_QUIET = "status_quiet"
    const val CH_ALERTS = "alerts"
    const val NOTIF_FG_ID = 42
    const val NOTIF_ALERT_ID = 43

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS_ICON, context.getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_status_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS_QUIET, context.getString(R.string.notif_channel_quiet), NotificationManager.IMPORTANCE_MIN).apply {
                description = context.getString(R.string.notif_channel_quiet_desc)
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_alerts_desc)
            },
        )
    }

    fun buildStatus(
        context: Context,
        state: RecordingController.State,
        text: String,
        indicator: StatusBarIndicator,
    ): androidx.core.app.NotificationCompat.Builder {
        val channel = if (indicator == StatusBarIndicator.SHOW_ICON) CH_STATUS_ICON else CH_STATUS_QUIET
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        when (state) {
            RecordingController.State.RECORDING -> {
                builder.setContentTitle("Recording speech")
                addPauseAction(context, builder, pausing = true)
            }
            RecordingController.State.PAUSED -> {
                builder.setContentTitle("Recording paused")
                addPauseAction(context, builder, pausing = false)
            }
            else -> builder.setContentTitle("Pendant")
        }
        builder.setContentText(text)
        return builder
    }

    private fun addPauseAction(context: Context, builder: NotificationCompat.Builder, pausing: Boolean) {
        val action = if (pausing) RecordingService.ACTION_PAUSE else RecordingService.ACTION_RESUME
        val icon = if (pausing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val label = if (pausing) "Pause" else "Resume"
        val intent = PendingIntent.getService(
            context,
            if (pausing) 1 else 2,
            Intent(context, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.addAction(icon, label, intent)
        val stopIntent = PendingIntent.getService(
            context,
            3,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
    }

    fun showAlert(context: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context,
            4,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ALERT_ID, n)
        } catch (_: SecurityException) {
        }
    }
}
