package io.github.weavespeak.client

import android.os.Handler
import android.os.Looper

/**
 * The one way native call controls reach the page.
 *
 * Mute in the notification, Hang up on a Bluetooth headset, the system putting the call on
 * hold for a phone call: each is said here as a short action name, and WeaveNativePlugin
 * passes it to the page as a "voiceAction" event. The page then does exactly what its own
 * button does (src/room/index.js). There is no second implementation of muting or leaving
 * on this side to drift from the first.
 *
 * Kept as a plain object rather than a bound service because everything involved lives in
 * the one process, and the page is the only listener there will ever be.
 */
object VoiceBus {

    /** Actions the page understands; anything else is a programming error, not a message. */
    const val MUTE = "mute"
    const val UNMUTE = "unmute"
    const val HANG_UP = "hangup"
    const val HOLD = "hold"
    const val RESUME = "resume"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: ((String) -> Unit)? = null

    /** Said on the main thread, whoever says it, so listeners never race each other. */
    fun say(action: String) {
        main.post { listener?.invoke(action) }
    }

    /**
     * Whether leaving the app now should shrink it to picture-in-picture rather than hide it:
     * the page sets this (voice with a picture worth keeping), MainActivity acts on it.
     */
    @Volatile
    var pipAllowed = false

    /** The window changed in a way the page cannot see for itself: picture-in-picture on/off. */
    @Volatile
    var windowListener: ((pip: Boolean) -> Unit)? = null

    fun window(pip: Boolean) {
        main.post { windowListener?.invoke(pip) }
    }
}
