package io.github.weavespeak.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * "Missed call from Kestrel", with Call back — or "You missed a huddle in Planning", with
 * Join. Posted when a ring ends unanswered on every device (the server's `missed` push).
 *
 * Both actions open the app through CallEntry, like Answer, and the page does the rest once
 * it has signed back in: calls back, or walks into the huddle.
 */
object MissedCall {

    private const val CHANNEL = "missed"

    fun show(
        context: Context, callId: String, serverId: String, kind: String,
        title: String, text: String, threadId: String?, channelId: String?,
    ) {
        ensureChannel(context)
        val id = 5000 + (callId.hashCode() and 0x7fff)
        val follow = PendingIntent.getActivity(
            context, id,
            Intent()
                .setComponent(ComponentName(context.packageName, "io.github.weavespeak.client.CallEntry"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(IncomingCall.EXTRA_ACTION, if (kind == "huddle") "join" else "callback")
                .putExtra(IncomingCall.EXTRA_CALL_ID, callId)
                .putExtra(IncomingCall.EXTRA_KIND, kind)
                .putExtra(IncomingCall.EXTRA_THREAD, threadId)
                .putExtra(IncomingCall.EXTRA_CHANNEL, channelId)
                .putExtra(IncomingCall.EXTRA_SERVER, serverId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_weave)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(follow)
            .addAction(R.drawable.ic_stat_weave, if (kind == "huddle") "Join" else "Call back", follow)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A call or a huddle that rang while you were away."
            },
        )
    }
}
