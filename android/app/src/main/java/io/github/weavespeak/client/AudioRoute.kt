package io.github.weavespeak.client

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Where call audio plays while this app is in voice: a connected headset if there is one,
 * otherwise the loudspeaker.
 *
 * Chromium puts the phone into call mode (MODE_IN_COMMUNICATION) the moment voice starts,
 * for its echo cancellation, and in call mode Android plays through the earpiece — the quiet
 * speaker you hold to your ear. Weave is a room you sit in, not a call you hold up to your
 * head, so that was nearly inaudible. Chromium also does not keep a route: the spike watched
 * one call move between earpiece and loudspeaker on its own. So the route is set here, and
 * checked again every second and whenever a device comes or goes.
 *
 * Only while engaged, which the page asks for on entering a room and lets go of on leaving
 * one — so another app's call is never rerouted by this one. And only what this class
 * changed is undone, when voice ends or the phone leaves call mode.
 */
class AudioRoute(context: Context) {

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var engaged = false
    // Whether this class changed the route, so release() undoes only its own doing.
    private var forced = false
    private var lastRoute: String? = null

    /** The route call audio is on now, for the log and for anyone asking. */
    var current: String = "off"
        private set

    private val tick = object : Runnable {
        override fun run() {
            apply()
            if (engaged) handler.postDelayed(this, CHECK_MS)
        }
    }

    // After letting go: Chromium leaves call mode a moment after the room does, and a
    // speakerphone switch it left on can only be put back once the call has really ended.
    private var settleTries = 0
    private val settle = object : Runnable {
        override fun run() {
            if (engaged) return
            if (audio.mode == AudioManager.MODE_NORMAL || --settleTries <= 0) {
                release()
                return
            }
            handler.postDelayed(this, SETTLE_MS)
        }
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = apply()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = apply()
    }

    /** What the phone itself says, for the log and for checking this class did what it meant. */
    @Suppress("DEPRECATION")
    fun state(): String {
        val mode = when (audio.mode) {
            AudioManager.MODE_IN_COMMUNICATION -> "call"
            AudioManager.MODE_IN_CALL -> "phone call"
            AudioManager.MODE_NORMAL -> "normal"
            else -> "mode ${audio.mode}"
        }
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.communicationDevice?.let { nameOf(it.type) } ?: "none"
        } else {
            if (audio.isSpeakerphoneOn) "speakerphone on" else "speakerphone off"
        }
        return "$mode, $device"
    }

    fun engage() {
        if (engaged) return
        engaged = true
        handler.removeCallbacks(settle)
        audio.registerAudioDeviceCallback(devices, handler)
        handler.post(tick)
    }

    fun disengage() {
        if (!engaged) return
        engaged = false
        handler.removeCallbacks(tick)
        audio.unregisterAudioDeviceCallback(devices)
        release()
        current = "off"
        settleTries = SETTLE_TRIES
        handler.postDelayed(settle, SETTLE_MS)
    }

    private fun apply() {
        if (!engaged) return
        // Not in call mode (yet, or any more): nothing of ours to hold.
        if (audio.mode != AudioManager.MODE_IN_COMMUNICATION) {
            release()
            current = "waiting"
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) applyModern() else applyLegacy()
    }

    /** Android 12 and later: name the device outright. */
    private fun applyModern() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val available = audio.availableCommunicationDevices
        val want = PREFERENCE.firstNotNullOfOrNull { type -> available.firstOrNull { it.type == type } } ?: return
        if (audio.communicationDevice?.id != want.id && audio.setCommunicationDevice(want)) forced = true
        note(nameOf(want.type))
    }

    /** Android 10 and 11: only the speakerphone switch exists, and a headset is the system's to route. */
    @Suppress("DEPRECATION")
    private fun applyLegacy() {
        val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in LEGACY_HEADSETS }
        if (headset) {
            release()
            note("headset")
            return
        }
        if (!audio.isSpeakerphoneOn) {
            audio.isSpeakerphoneOn = true
            forced = true
            AppLog.write(appContext, "info", "speakerphone switched on for the call")
        }
        note("speaker")
    }

    @Suppress("DEPRECATION")
    private fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (forced) audio.clearCommunicationDevice()
        } else if (forced || (audio.mode == AudioManager.MODE_NORMAL && audio.isSpeakerphoneOn)) {
            // Android 10 and 11 keep the speakerphone switch after a call ends, and the next
            // call — a phone call included — then starts on the loudspeaker. Chromium can leave
            // it on too (the S9 showed it on before Weave had touched it), so once no call is
            // running it goes off, whoever switched it on: outside a call it does nothing else.
            audio.isSpeakerphoneOn = false
            AppLog.write(appContext, "info", "speakerphone switched off")
        }
        forced = false
    }

    /** One log line per change of route, not one a second. */
    private fun note(route: String) {
        current = route
        if (route == lastRoute) return
        lastRoute = route
        AppLog.write(appContext, "info", "call audio on the $route")
    }

    private fun nameOf(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
            "bluetooth headset"
        } else {
            "headset"
        }
    }

    companion object {
        private const val CHECK_MS = 1_000L
        private const val SETTLE_MS = 500L
        private const val SETTLE_TRIES = 20

        /** First found wins: anything worn on the head, then the loudspeaker — never the earpiece. */
        private val PREFERENCE: List<Int> = buildList {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        }

        private val LEGACY_HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
    }
}
