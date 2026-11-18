package io.github.weavespeak.client

import android.content.Intent
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

    /**
     * The window changed in a way the page cannot see for itself: picture-in-picture on or
     * off, and whether it is showing on top of the lock screen. Null is "unchanged".
     */
    @Volatile
    var windowListener: ((pip: Boolean?, locked: Boolean?) -> Unit)? = null

    fun window(pip: Boolean? = null, locked: Boolean? = null) {
        main.post { windowListener?.invoke(pip, locked) }
    }

    /** Answer, Decline, or a tap on a ringing call, from its notification. */
    data class CallAction(
        val action: String,       // "accept" | "decline" | "show"
        val callId: String,
        val kind: String,
        val threadId: String?,
        val channelId: String?,
        val serverId: String?,
    ) {
        companion object {
            fun from(intent: Intent?): CallAction? {
                intent ?: return null
                return CallAction(
                    action = intent.getStringExtra(IncomingCall.EXTRA_ACTION) ?: return null,
                    callId = intent.getStringExtra(IncomingCall.EXTRA_CALL_ID) ?: return null,
                    kind = intent.getStringExtra(IncomingCall.EXTRA_KIND) ?: "dm",
                    threadId = intent.getStringExtra(IncomingCall.EXTRA_THREAD),
                    channelId = intent.getStringExtra(IncomingCall.EXTRA_CHANNEL),
                    serverId = intent.getStringExtra(IncomingCall.EXTRA_SERVER),
                )
            }
        }
    }

    @Volatile
    var callListener: ((CallAction) -> Unit)? = null

    // An Answer that opened the app from cold arrives before the page has signed back in and
    // is listening. It waits here, and the page collects it once it can act on it.
    private val held = mutableListOf<CallAction>()

    fun callAction(action: CallAction) {
        main.post {
            val listen = callListener
            if (listen != null) listen(action) else synchronized(held) { held.add(action) }
        }
    }

    /** Everything that arrived while nobody was listening, once. */
    fun takeHeld(): List<CallAction> = synchronized(held) { held.toList().also { held.clear() } }
}
