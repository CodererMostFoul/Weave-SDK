package io.github.weavespeak.client

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * The call's standing with the rest of the phone, for as long as voice lasts.
 *
 * Three things a call app owes the system, and that a web page cannot do for itself:
 *  - audio focus, so music from another app pauses while you talk and carries on after;
 *  - stepping aside for a phone call: held (silent both ways) while it is answered, and
 *    back when it ends;
 *  - headset buttons: one press mutes or unmutes, a long press hangs up.
 *
 * Each of them only says what happened (VoiceBus). The page decides what that means, through
 * the same code its own buttons run.
 *
 * Two implementations. TelecomCallSession registers the call with Android's own call system,
 * which does all three itself and adds what nothing else can reach: a Bluetooth headset's or
 * car's call buttons, a watch, the system's hold-for-a-phone-call. DirectCallSession does
 * what an app can do on its own, and is what runs when Telecom is unavailable or turned off.
 */
interface CallSession {
    fun start(session: VoiceService.Session)
    fun update(session: VoiceService.Session)
    fun end()

    companion object {
        /**
         * Telecom while it is switched on for this phone (see TelecomCallSession.enabled),
         * otherwise the direct one. A Telecom call that fails to register falls back by itself.
         */
        fun create(context: Context): CallSession =
            if (TelecomCallSession.enabled(context)) TelecomCallSession(context) else DirectCallSession(context)
    }
}

/** The call's link to the phone without Android's call system. See CallSession. */
class DirectCallSession(context: Context) : CallSession {

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var focus: AudioFocusRequest? = null
    private var media: MediaSession? = null
    private var muted = false
    private var held = false

    // Transient, not permanent: a music app that loses focus for good stops and stays
    // stopped, where one that loses it for a while picks up again when the call lets go.
    private val focusRequest: AudioFocusRequest
        get() = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ /* the mode watch below decides holds */ }, handler)
            .build()

    /**
     * A phone call is read from the audio mode, not from focus. Focus is lost for all sorts
     * of short things — a navigation prompt, another app's notification — and holding the
     * call for each would mute somebody mid-sentence. MODE_IN_CALL is a cellular call that
     * has been answered, and nothing else. A ringing one (MODE_RINGTONE) does not hold: the
     * call goes on until the person chooses to answer, as in any calling app.
     */
    private val watch = object : Runnable {
        override fun run() {
            val inPhoneCall = audio.mode == AudioManager.MODE_IN_CALL
            if (inPhoneCall && !held) {
                held = true
                AppLog.write(app, "info", "a phone call was answered; holding voice")
                VoiceBus.say(VoiceBus.HOLD)
            } else if (!inPhoneCall && held) {
                held = false
                // The phone call left the audio mode at NORMAL, and nothing will put it back:
                // Chromium switched call mode on once, when the microphone opened. Without it
                // echo cancellation is weaker and call audio may play on the earpiece.
                if (audio.mode == AudioManager.MODE_NORMAL) audio.mode = AudioManager.MODE_IN_COMMUNICATION
                AppLog.write(app, "info", "the phone call ended; resuming voice")
                VoiceBus.say(VoiceBus.RESUME)
            }
            handler.postDelayed(this, WATCH_MS)
        }
    }

    override fun start(session: VoiceService.Session) {
        muted = session.muted
        val request = focusRequest
        if (audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = request
        media = mediaSession()
        handler.postDelayed(watch, WATCH_MS)
    }

    override fun update(session: VoiceService.Session) {
        muted = session.muted
    }

    override fun end() {
        handler.removeCallbacks(watch)
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        media?.run {
            isActive = false
            release()
        }
        media = null
    }

    /**
     * A wired headset's button arrives as a media button, and Android sends those to the
     * media session that is playing. So the call holds one, marked playing, for its length.
     * It posts no media notification, so nothing appears in the media controls.
     */
    private fun mediaSession(): MediaSession = MediaSession(app, "weave-voice").apply {
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
        setCallback(object : MediaSession.Callback() {
            private var downAt = 0L

            override fun onMediaButtonEvent(intent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (key.keyCode != KeyEvent.KEYCODE_HEADSETHOOK && key.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                    return false
                }
                when (key.action) {
                    KeyEvent.ACTION_DOWN -> if (key.repeatCount == 0) downAt = SystemClock.uptimeMillis()
                    KeyEvent.ACTION_UP -> {
                        val long = SystemClock.uptimeMillis() - downAt >= LONG_PRESS_MS
                        VoiceBus.say(
                            when {
                                long -> VoiceBus.HANG_UP
                                muted -> VoiceBus.UNMUTE
                                else -> VoiceBus.MUTE
                            },
                        )
                    }
                }
                return true
            }
        }, handler)
        isActive = true
    }

    companion object {
        private const val WATCH_MS = 1_000L
        private const val LONG_PRESS_MS = 700L
    }
}
