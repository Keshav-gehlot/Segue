package com.segue.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import com.segue.app.R
import com.segue.app.ui.MainActivity
import com.segue.engine.EngineState

object Notifications {
    const val CHANNEL = "segue-status"
    const val ID = 1

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW)
        ch.description = context.getString(R.string.channel_status_desc)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun summary(state: EngineState?): String {
        if (state == null) return "Starting…"
        val connected = state.peers.count { it.connected }
        val where = when {
            state.playing -> "Playing here"
            state.iAmOwner -> "Paused here"
            else -> state.peers.firstOrNull { it.id == state.ownerId }?.let { p ->
                if (p.status?.playing == true) "Playing on ${p.label}" else "Paused on ${p.label}"
            } ?: "Nothing playing"
        }
        return "$where · $connected of ${state.peers.size} phones connected"
    }

    fun build(context: Context, state: EngineState?, session: MediaSession.Token?): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(
            context, 1, Intent(context, SegueService::class.java).setAction(SegueService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_segue)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(summary(state))
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.stop), stop).build())
        if (session != null) b.setStyle(Notification.MediaStyle().setMediaSession(session))
        return b.build()
    }
}
