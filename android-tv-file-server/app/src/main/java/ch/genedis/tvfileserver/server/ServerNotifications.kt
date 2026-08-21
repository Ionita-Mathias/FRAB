package ch.genedis.tvfileserver.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ch.genedis.tvfileserver.R
import ch.genedis.tvfileserver.ui.MainActivity
import ch.genedis.tvfileserver.ui.UiFormat

/** Builds the persistent notification that keeps the server alive in the background. */
object ServerNotifications {

    const val CHANNEL_ID = "file_server"
    const val NOTIFICATION_ID = 1001

    /** Creates the channel. Safe to call repeatedly. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            // LOW: the notification is a status surface, not something to interrupt a film.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** The notification for the current [state]. */
    fun build(context: Context, state: ServerUiState): Notification {
        val title = when (state.status) {
            ServerStatus.RUNNING -> context.getString(R.string.notification_title_running)
            ServerStatus.STARTING -> context.getString(R.string.notification_title_starting)
            ServerStatus.STOPPING -> context.getString(R.string.notification_title_starting)
            ServerStatus.ERROR -> context.getString(R.string.notification_title_stopped)
            ServerStatus.STOPPED -> context.getString(R.string.notification_title_stopped)
        }

        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingIntentFlags(),
        )
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            FileServerService.intent(context, FileServerService.ACTION_STOP),
            pendingIntentFlags(),
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(describe(context, state))
            .setStyle(NotificationCompat.BigTextStyle().bigText(describeLong(context, state)))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.notification_action_stop), stop)
            .build()
    }

    private fun describe(context: Context, state: ServerUiState): String {
        val url = state.webUrl ?: return context.getString(R.string.notification_text_idle)
        val active = state.totals.activeCount
        return if (active == 0) {
            url
        } else {
            val speed = UiFormat.formatSpeed(state.totals.uploadBps + state.totals.downloadBps)
            context.resources.getQuantityString(R.plurals.notification_text_transfers, active, active, speed)
        }
    }

    private fun describeLong(context: Context, state: ServerUiState): String {
        val lines = ArrayList<String>(4)
        state.webUrl?.let { lines.add(it) }
        state.davUrl?.let { lines.add(it) }
        state.ftpUrl?.let { lines.add(it) }
        if (state.authEnabled) {
            lines.add(context.getString(R.string.notification_credentials, state.username, state.password))
        }
        return if (lines.isEmpty()) context.getString(R.string.notification_text_idle) else lines.joinToString("\n")
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private const val REQUEST_OPEN = 1
    private const val REQUEST_STOP = 2
}
