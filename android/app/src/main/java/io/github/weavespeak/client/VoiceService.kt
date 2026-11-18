package io.github.weavespeak.client

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat

/**
 * Keeps voice going while Weave is out of sight: the screen locked, another app in front.
 *
 * Without it Android does three things to a page in a call within seconds of it leaving the
 * screen. From Android 11 a background app's microphone records silence. Its process is
 * frozen once nothing visible needs it, which stops the WebView's socket and timers. And
 * Doze, with the screen off, takes the network away. A foreground service with the
 * microphone type is the sanctioned answer to all three, and the notification it must show
 * is useful in its own right: it is where Mute, Speaker and Hang up live while the app is
 * hidden, on the lock screen included.
 *
 * It is started by the page, only while the page stands in a room (WeaveNativePlugin
 * .voiceSession), and only from the foreground, which is the only place Android allows a
 * microphone service to start. Everything after that is an update to the running service.
 *
 * It holds no call logic. The controls only say what was pressed (VoiceBus), and the page
 * does what its own buttons do. The call's link to the system — audio focus, a phone call
 * putting it on hold, headset buttons — is CallSession's.
 */
class VoiceService : Service() {

    /** What the page says about the call; the notification is drawn from this alone. */
    data class Session(
        val title: String,
        val kind: String,          // "room" | "call"
        val muted: Boolean,
        val video: Boolean,
        val since: Long,           // when voice began, for the notification's timer
        val held: Boolean = false, // on hold for a phone call
    )

    private var session: Session? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var call: CallSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val next = pending ?: return stopNow()
                pending = null
                apply(next)
            }
            ACTION_MUTE -> VoiceBus.say(VoiceBus.MUTE)
            ACTION_UNMUTE -> VoiceBus.say(VoiceBus.UNMUTE)
            ACTION_SPEAKER -> {
                AudioRoute.of(this).toggleSpeaker()
                session?.let { promote(it) }
            }
            ACTION_HANG_UP -> VoiceBus.say(VoiceBus.HANG_UP)
            // A restart with no intent (the system recreating a killed service) has no page
            // behind it to carry a call, so there is nothing to keep in the foreground for.
            else -> if (session == null) return stopNow()
        }
        return START_NOT_STICKY
    }

    /**
     * Swiped away from Recents. The WebView went with the activity, and with it the call:
     * a service left running would show a call that no longer exists. So it ends, and the
     * server notices the socket go as it would for any other closed app.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.write(this, "info", "app swiped away while in voice; ending the voice service")
        stopNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        call?.end()
        call = null
        releaseLocks()
        session = null
        if (running === this) running = null
        super.onDestroy()
    }

    private fun apply(next: Session) {
        val first = session == null
        session = next
        if (!promote(next)) return
        if (first) {
            holdLocks()
            call = CallSession.create(this).also { it.start(next) }
        } else {
            call?.update(next)
        }
    }

    /** Enter (or re-enter) the foreground with the types the call needs now. */
    private fun promote(s: Session): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(s), types(s))
            } else {
                startForeground(NOTIFICATION_ID, notification(s))
            }
            true
        } catch (e: RuntimeException) {
            // A microphone service started from the background, or a type whose permission
            // was taken away. Neither is fixed by trying again; the page hears that voice in
            // the background is not available, and the call itself carries on in front.
            AppLog.write(this, "warn", "voice service could not enter the foreground: $e")
            stopNow()
            false
        }
    }

    /**
     * phoneCall always: this is a call, and on Android 14 and later it is the type a calling
     * app holds. microphone from Android 11, where it first exists, and only once microphone
     * access is granted — claiming it without the permission makes startForeground throw, and
     * the first getUserMedia is what asks. Never camera: a hidden app's camera is paused on
     * purpose (the page does that), and picture-in-picture is a visible window, which keeps
     * camera access without any type.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun types(s: Session): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && granted(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun notification(s: Session): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUp = action(ACTION_HANG_UP, 1)
        val person = Person.Builder().setName(s.title).setImportant(true).build()
        val speakerOn = AudioRoute.of(this).current == AudioRoute.KIND_SPEAKER

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_weave)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp))
            .setContentText(
                when {
                    s.held -> "On hold for your phone call"
                    s.muted -> "Your microphone is off"
                    s.kind == "call" -> "Private call"
                    else -> "In voice"
                },
            )
            .setUsesChronometer(true)
            .setWhen(s.since)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_weave,
                    if (s.muted) "Unmute" else "Mute",
                    action(if (s.muted) ACTION_UNMUTE else ACTION_MUTE, 2),
                ).build(),
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_weave,
                    if (speakerOn) "Speaker off" else "Speaker",
                    action(ACTION_SPEAKER, 3),
                ).build(),
            )
            .build()
    }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, VoiceService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * A partial wake lock so the CPU keeps encoding with the screen off, and a low-latency
     * Wi-Fi lock so the radio does not nap between packets — voice that arrives in bursts is
     * voice that breaks up. Both last exactly as long as the call.
     */
    private fun holdLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "weave:voice").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "weave:voice").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun stopNow(): Int {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "voice"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_START = "io.github.weavespeak.client.voice.START"
        private const val ACTION_MUTE = "io.github.weavespeak.client.voice.MUTE"
        private const val ACTION_UNMUTE = "io.github.weavespeak.client.voice.UNMUTE"
        private const val ACTION_SPEAKER = "io.github.weavespeak.client.voice.SPEAKER"
        private const val ACTION_HANG_UP = "io.github.weavespeak.client.voice.HANG_UP"

        @Volatile private var running: VoiceService? = null
        @Volatile private var pending: Session? = null

        /** Whether a call is being kept alive in the background right now. */
        val active: Boolean get() = running?.session != null

        /**
         * The page is in voice, or something about the call changed. The first call starts
         * the service (which only works from the foreground); later ones update it in place.
         */
        fun show(context: Context, s: Session) {
            val service = running
            if (service != null && service.session != null) {
                service.apply(s)
                return
            }
            pending = s
            ContextCompat.startForegroundService(
                context, Intent(context, VoiceService::class.java).setAction(ACTION_START),
            )
        }

        /** The page has left voice. */
        fun end() {
            pending = null
            running?.stopNow()
        }

        /** Redraw the notification after a change it shows that the page did not make. */
        fun refresh() {
            val service = running ?: return
            service.session?.let { service.promote(it) }
        }

        /** Mute or Hang up from somewhere other than the notification (picture-in-picture). */
        fun actionIntent(context: Context, mute: Boolean?): PendingIntent {
            val (name, code) = when (mute) {
                true -> ACTION_MUTE to 12
                false -> ACTION_UNMUTE to 13
                null -> ACTION_HANG_UP to 11
            }
            return PendingIntent.getService(
                context, code,
                Intent(context, VoiceService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** Whether the call is muted now, as the page last said. */
        val muted: Boolean get() = running?.session?.muted == true

        /** Created ahead of the first call so its settings exist before anything is posted. */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            // Low importance: the call is already on screen or in your ear. This is where its
            // controls live, not an alert; it must never make a sound of its own.
            val channel = NotificationChannel(CHANNEL, "Voice and calls in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you are in voice, with Mute, Speaker and Hang up."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }
}
