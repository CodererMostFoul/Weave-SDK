package io.github.weavespeak.client

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The call registered with Android's own call system (Telecom), through Jetpack Core-Telecom.
 *
 * What that buys over DirectCallSession: a Bluetooth headset's, a car's or a watch's call
 * buttons reach the call (they talk to Telecom, never to an app); a phone call arriving
 * puts this one on hold the way the phone's own calls are, and gives it back after; and the
 * system knows a call is in progress (the status-bar call chip, Do Not Disturb, other apps'
 * audio focus).
 *
 * The price is that Telecom also routes the audio. Android's guidance is that a Telecom call
 * must choose its speaker or headset through requestEndpointChange and never through
 * AudioManager.setCommunicationDevice — and Chromium calls exactly that when the microphone
 * opens. So while this session is live AudioRoute routes through it (EndpointRouter), and
 * whether Chromium's own pick then fights Telecom is the on-device check that decides
 * whether this is switched on (enabled()).
 *
 * Every Telecom failure falls back to DirectCallSession rather than ending voice: losing the
 * headset buttons is a lesser thing than losing the call.
 */
class TelecomCallSession(context: Context) : CallSession, AudioRoute.EndpointRouter {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var control: CallControlScope? = null
    private var fallback: CallSession? = null
    private var session: VoiceService.Session? = null
    private var reportedMute: Boolean? = null

    override var endpoints: List<CallEndpointCompat> = emptyList()
        private set
    override var current: CallEndpointCompat? = null
        private set

    override fun start(session: VoiceService.Session) {
        this.session = session
        val manager = CallsManager(app)
        try {
            manager.registerAppWithTelecom(CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
        } catch (e: Exception) {
            fallBack("Telecom registration refused: $e")
            return
        }
        scope.launch {
            try {
                manager.addCall(
                    attributes(session),
                    onAnswer = { /* never offered: this call is already ours and going */ },
                    onDisconnect = { VoiceBus.say(VoiceBus.HANG_UP) },
                    onSetActive = { VoiceBus.say(VoiceBus.RESUME) },
                    onSetInactive = { VoiceBus.say(VoiceBus.HOLD) },
                ) {
                    control = this
                    launch {
                        val result = setActive()
                        if (result is CallControlResult.Error) {
                            AppLog.write(app, "warn", "Telecom would not make the call active (${result.errorCode})")
                        }
                    }
                    launch {
                        availableEndpoints.collect {
                            endpoints = it
                            AudioRoute.of(app).routeChanged()
                        }
                    }
                    launch {
                        currentCallEndpoint.collect {
                            current = it
                            AudioRoute.of(app).routeChanged()
                            VoiceService.refresh()
                        }
                    }
                    launch {
                        // Mute from a headset or a watch. The first value is only the state
                        // Telecom starts in, not anybody pressing anything, so it is not said.
                        isMuted.collect { muted ->
                            val before = reportedMute
                            reportedMute = muted
                            if (before != null && muted != before) {
                                VoiceBus.say(if (muted) VoiceBus.MUTE else VoiceBus.UNMUTE)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                fallBack("Telecom would not add the call: $e")
            }
        }
        AudioRoute.of(app).router = this
        AppLog.write(app, "info", "voice registered with Telecom")
    }

    override fun update(session: VoiceService.Session) {
        this.session = session
        fallback?.update(session)
    }

    override fun end() {
        val route = AudioRoute.of(app)
        if (route.router === this) route.router = null
        fallback?.end()
        fallback = null
        val call = control
        control = null
        if (call == null) {
            scope.cancel()
            return
        }
        scope.launch {
            try {
                call.disconnect(DisconnectCause(DisconnectCause.LOCAL))
            } catch (_: Exception) {
                // Already gone on Telecom's side; there is nothing left to tell it.
            }
            scope.cancel()
        }
    }

    override fun request(endpoint: CallEndpointCompat) {
        val call = control ?: return
        scope.launch {
            val result = call.requestEndpointChange(endpoint)
            if (result is CallControlResult.Error) {
                AppLog.write(app, "warn", "Telecom would not move the call to ${endpoint.name} (${result.errorCode})")
            }
        }
    }

    private fun fallBack(why: String) {
        AppLog.write(app, "warn", "$why; using the direct call session")
        val route = AudioRoute.of(app)
        if (route.router === this) route.router = null
        control = null
        val s = session ?: return
        fallback = DirectCallSession(app).also { it.start(s) }
    }

    private fun attributes(s: VoiceService.Session) = CallAttributesCompat(
        displayName = s.title,
        // Telecom wants an address for every call; this one is never dialled, only shown.
        address = Uri.parse("weave:${Uri.encode(s.title)}"),
        direction = CallAttributesCompat.DIRECTION_OUTGOING,
        callType = if (s.video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
        callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
    )

    companion object {
        private const val PREFS = "weave-native"
        private const val KEY = "telecom"

        /**
         * Off until it has been proven on a phone: see the class comment for what is being
         * checked. Stored per install so it can be switched while testing without a rebuild.
         */
        fun enabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        }
    }
}
