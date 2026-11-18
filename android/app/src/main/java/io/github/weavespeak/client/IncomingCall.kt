package io.github.weavespeak.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.Person

/**
 * The phone ringing: someone is calling you privately, or a huddle you belong to has
 * started.
 *
 * A notification rather than anything drawn by the page, because only a notification can do
 * what a ring has to: sound your own ringtone and vibrate (as loud or as silent as your
 * phone's ringer and Do Not Disturb say — the channel is Android's to govern), keep ringing
 * until answered (FLAG_INSISTENT), and fill the screen on top of the lock screen when the
 * phone is asleep (the full-screen intent). With the phone in use it drops down from the top
 * instead, with Answer and Decline on it.
 *
 * The same ring can arrive twice — over the socket while the app is open, and as a push
 * (Release 4) — so every ring is known by its call id, and an id that has ended stays
 * remembered long enough to ignore the copy that arrives late.
 *
 * Answer opens the app (through the non-exported CallEntry alias, so no other app can
 * forge one), and Decline goes to CallActionReceiver; both reach the page as a "callAction".
 */
object IncomingCall {

    /** One ring, as the page or a push describes it. */
    data class Ring(
        val callId: String,
        val title: String,        // the caller's name, or the huddle's
        val subtitle: String,     // "is calling you" / "started a huddle"
        val kind: String,         // "dm" | "huddle"
        val threadId: String?,
        val channelId: String?,
        val serverId: String?,
        val expiresAt: Long,
    )

    private const val CHANNEL = "calls"
    private const val ENDED_MEMORY_MS = 2 * 60 * 1000L
    private const val FALLBACK_RING_MS = 45_000L

    const val EXTRA_ACTION = "weave.call.action"
    const val EXTRA_CALL_ID = "weave.call.id"
    const val EXTRA_KIND = "weave.call.kind"
    const val EXTRA_THREAD = "weave.call.thread"
    const val EXTRA_CHANNEL = "weave.call.channel"
    const val EXTRA_SERVER = "weave.call.server"
    const val EXTRA_TITLE = "weave.call.title"

    private val ringing = mutableMapOf<String, Ring>()
    private val ended = LinkedHashMap<String, Long>()

    /** Ring, unless this call is already ringing or has already ended. */
    @Synchronized
    fun ring(context: Context, ring: Ring): Boolean {
        forgetOld()
        if (ringing.containsKey(ring.callId) || ended.containsKey(ring.callId)) return false
        val left = (ring.expiresAt - System.currentTimeMillis()).takeIf { it > 0 } ?: FALLBACK_RING_MS
        ringing[ring.callId] = ring
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(idFor(ring.callId), build(context, ring, left))
        AppLog.write(context, "info", "ringing for a ${ring.kind} call")
        return true
    }

    /** Stop ringing: answered, declined, cancelled, or over. Remembered so a late copy is ignored. */
    @Synchronized
    fun end(context: Context, callId: String) {
        forgetOld()
        ringing.remove(callId)
        ended[callId] = SystemClock.elapsedRealtime()
        context.getSystemService(NotificationManager::class.java).cancel(idFor(callId))
    }

    @Synchronized
    fun ringFor(callId: String): Ring? = ringing[callId]

    private fun forgetOld() {
        val now = SystemClock.elapsedRealtime()
        ended.entries.removeAll { now - it.value > ENDED_MEMORY_MS }
    }

    private fun idFor(callId: String) = 1000 + (callId.hashCode() and 0x7fff)

    private fun build(context: Context, ring: Ring, leftMs: Long): Notification {
        val show = activityIntent(context, ring, "show", 0)
        val answer = activityIntent(context, ring, "accept", 1)
        val decline = PendingIntent.getBroadcast(
            context, idFor(ring.callId) * 4 + 2,
            Intent(context, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_DECLINE)
                .putExtras(extras(ring, "decline")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val caller = Person.Builder().setName(ring.title).setImportant(true).build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_weave)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .setContentTitle(ring.title)
            .setContentText(ring.subtitle)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(show)
            .setFullScreenIntent(show, true)
            .setTimeoutAfter(leftMs)
            .build()
        // Keeps sounding until somebody does something, as a phone's own ringing does, rather
        // than one chime that is easy to miss in a pocket.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        return notification
    }

    /**
     * Into the app through CallEntry: an alias of MainActivity that is not exported, so
     * only this app's own notifications can open the app on a call.
     */
    private fun activityIntent(context: Context, ring: Ring, action: String, slot: Int): PendingIntent =
        PendingIntent.getActivity(
            context, idFor(ring.callId) * 4 + slot,
            Intent()
                .setComponent(ComponentName(context.packageName, "io.github.weavespeak.client.CallEntry"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtras(extras(ring, action)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun extras(ring: Ring, action: String) = android.os.Bundle().apply {
        putString(EXTRA_ACTION, action)
        putString(EXTRA_CALL_ID, ring.callId)
        putString(EXTRA_KIND, ring.kind)
        putString(EXTRA_THREAD, ring.threadId)
        putString(EXTRA_CHANNEL, ring.channelId)
        putString(EXTRA_SERVER, ring.serverId)
        putString(EXTRA_TITLE, ring.title)
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings when somebody calls you on Weave, or a huddle you belong to starts."
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** Android 14 and later let people switch off full-screen calls; earlier, they are always on. */
    fun canFillScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
