package io.github.weavespeak.client

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Where call audio plays while this app is in voice: the output chosen in Settings when it is
 * connected, otherwise a connected headset if there is one, otherwise the loudspeaker.
 *
 * Chromium puts the phone into call mode (MODE_IN_COMMUNICATION) the moment voice starts,
 * for its echo cancellation, and in call mode Android plays through the earpiece — the quiet
 * speaker you hold to your ear. Weave is a room you sit in, not a call you hold up to your
 * head, so that was nearly inaudible. Chromium also does not keep a route: the spike watched
 * one call move between earpiece and loudspeaker on its own. So the route is set here, and
 * checked again every second and whenever a device comes or goes.
 *
 * A page cannot choose for itself: the WebView lists no outputs to enumerateDevices() and has
 * no setSinkId(), which left the Settings picker permanently empty on a phone. So the outputs
 * are listed here (outputs()) and the choice comes back here (prefer()). The earpiece is never
 * chosen automatically, only when somebody asks for it.
 *
 * Only while engaged, which the page asks for on entering a room and lets go of on leaving
 * one — so another app's call is never rerouted by this one. And only what this class
 * changed is undone, when voice ends or the phone leaves call mode.
 */
class AudioRoute(context: Context) {

    /** One place call audio can go, as the Settings picker lists it. */
    data class Output(val id: String, val label: String, val kind: String)

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var engaged = false
    // Whether this class changed the route, so release() undoes only its own doing.
    private var forced = false
    private var lastRoute: String? = null

    /** Whether the page is in voice, for Settings to say whether a choice applies now. */
    val inVoice: Boolean get() = engaged

    /** The Output id call audio is on now ("off" or "waiting" when there is none). */
    var current: String = "off"
        private set

    /** What the person chose in Settings: an Output id, or "" for automatic. */
    var preferred: String = ""
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
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = changed()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = changed()
    }

    // Android 10 and 11 only: a Bluetooth headset carries call audio once its SCO link is up,
    // which this class must start itself, and which a headset without a microphone never brings
    // up. So the link's state is followed, and a link that does not come up is given up on.
    private var scoOurs = false
    private var scoAskedAt = 0L
    private var scoGaveUp = false
    private var scoUp = false
    private val scoState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
            scoUp = state == AudioManager.SCO_AUDIO_STATE_CONNECTED
            apply()
        }
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
            audio.communicationDevice?.let { outputOf(it)?.id } ?: "none"
        } else {
            (if (audio.isSpeakerphoneOn) "speakerphone on" else "speakerphone off") +
                (if (scoUp) ", bluetooth link up" else "")
        }
        return "$mode, $device"
    }

    /**
     * Every place call audio can go right now. The loudspeaker and the earpiece are always
     * there; a headset is listed while it is connected, by its own name when it has one.
     */
    @Suppress("DEPRECATION")
    fun outputs(): List<Output> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audio.availableCommunicationDevices.mapNotNull { outputOf(it) }.distinctBy { it.id }.sortedBy { ORDER.indexOf(it.kind) }
        }
        val all = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Before Android 12 there is no way to name the earpiece over a plugged-in headset:
        // with the speakerphone off, the system picks the headset. So it is not offered then.
        val wired = all.any { kindOf(it.type) == KIND_WIRED || kindOf(it.type) == KIND_USB }
        return all.mapNotNull { outputOf(it) }
            .filter { !(wired && it.kind == KIND_EARPIECE) }
            .distinctBy { it.id }
            .sortedBy { ORDER.indexOf(it.kind) }
    }

    /** The person chose in Settings. "" is automatic. Applied at once while in voice. */
    fun prefer(id: String) {
        if (id == preferred) return
        preferred = id
        scoGaveUp = false
        AppLog.write(appContext, "info", "call audio output chosen: ${id.ifEmpty { "automatic" }}")
        apply()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun engage() {
        if (engaged) return
        engaged = true
        handler.removeCallbacks(settle)
        audio.registerAudioDeviceCallback(devices, handler)
        // A protected system broadcast, and only before Android 12, where no flag is asked for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            appContext.registerReceiver(scoState, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        }
        handler.post(tick)
    }

    fun disengage() {
        if (!engaged) return
        engaged = false
        handler.removeCallbacks(tick)
        audio.unregisterAudioDeviceCallback(devices)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { appContext.unregisterReceiver(scoState) }
        }
        release()
        current = "off"
        lastRoute = null
        settleTries = SETTLE_TRIES
        handler.postDelayed(settle, SETTLE_MS)
    }

    /** A device came or went: a headset that failed to link before may work now. */
    private fun changed() {
        scoGaveUp = false
        apply()
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
        // The choice when it is connected; otherwise what automatic would pick, as for a
        // chosen microphone that has gone away. The choice is kept for when it comes back.
        val chosen = if (preferred.isEmpty()) null else available.firstOrNull { outputOf(it)?.id == preferred }
        val want = chosen
            ?: PREFERENCE.firstNotNullOfOrNull { type -> available.firstOrNull { it.type == type } }
            ?: return
        if (audio.communicationDevice?.id != want.id && audio.setCommunicationDevice(want)) forced = true
        note(outputOf(want) ?: return)
    }

    /**
     * Android 10 and 11: there is only the speakerphone switch, and a Bluetooth link this class
     * starts itself. With both off, the system plays through a plugged-in headset if there is
     * one and the earpiece if not.
     */
    @Suppress("DEPRECATION")
    private fun applyLegacy() {
        val all = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val listed = all.mapNotNull { d -> outputOf(d) }
        val chosen = if (preferred.isEmpty()) null else listed.firstOrNull { it.id == preferred }
        val bluetooth = listed.firstOrNull { it.kind == KIND_BLUETOOTH }
        val worn = listed.firstOrNull { it.kind == KIND_WIRED || it.kind == KIND_USB }
        val speaker = listed.firstOrNull { it.kind == KIND_SPEAKER } ?: Output("speaker", "Loudspeaker", KIND_SPEAKER)

        val want = when {
            chosen != null && !(chosen.kind == KIND_BLUETOOTH && scoGaveUp) -> chosen
            worn != null -> worn
            bluetooth != null && !scoGaveUp -> bluetooth
            else -> speaker
        }
        when (want.kind) {
            KIND_SPEAKER -> {
                stopSco()
                speakerphone(true)
            }
            KIND_BLUETOOTH -> {
                speakerphone(false)
                if (!startSco()) return apply()
            }
            // The earpiece, or a plugged-in headset: both switches off, and the system routes.
            else -> {
                stopSco()
                speakerphone(false)
            }
        }
        note(want)
    }

    @Suppress("DEPRECATION")
    private fun speakerphone(on: Boolean) {
        if (audio.isSpeakerphoneOn == on) return
        audio.isSpeakerphoneOn = on
        if (on) forced = true
        AppLog.write(appContext, "info", if (on) "speakerphone switched on for the call" else "speakerphone switched off for the call")
    }

    /** Asks for the Bluetooth link once; false once it has had its chance and not come up. */
    @Suppress("DEPRECATION")
    private fun startSco(): Boolean {
        if (scoUp) return true
        if (!scoOurs) {
            audio.startBluetoothSco()
            audio.isBluetoothScoOn = true
            scoOurs = true
            scoAskedAt = SystemClock.elapsedRealtime()
            AppLog.write(appContext, "info", "bluetooth link asked for")
            return true
        }
        if (SystemClock.elapsedRealtime() - scoAskedAt < SCO_WAIT_MS) return true
        // Headphones with no microphone list a call link and never bring it up.
        AppLog.write(appContext, "warn", "bluetooth link did not come up; leaving the headset to media")
        stopSco()
        scoGaveUp = true
        return false
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        if (!scoOurs) return
        audio.isBluetoothScoOn = false
        audio.stopBluetoothSco()
        scoOurs = false
        scoUp = false
    }

    @Suppress("DEPRECATION")
    private fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (forced) audio.clearCommunicationDevice()
        } else {
            stopSco()
            if (forced || (audio.mode == AudioManager.MODE_NORMAL && audio.isSpeakerphoneOn)) {
                // Android 10 and 11 keep the speakerphone switch after a call ends, and the next
                // call — a phone call included — then starts on the loudspeaker. Chromium can leave
                // it on too (the S9 showed it on before Weave had touched it), so once no call is
                // running it goes off, whoever switched it on: outside a call it does nothing else.
                audio.isSpeakerphoneOn = false
                AppLog.write(appContext, "info", "speakerphone switched off")
            }
        }
        forced = false
    }

    /** One log line per change of route, not one a second. */
    private fun note(route: Output) {
        current = route.id
        if (route.id == lastRoute) return
        lastRoute = route.id
        AppLog.write(appContext, "info", "call audio on the ${route.label}")
    }

    private fun kindOf(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> KIND_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> KIND_EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> KIND_WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET -> KIND_USB
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> KIND_BLUETOOTH
        AudioDeviceInfo.TYPE_HEARING_AID -> KIND_HEARING_AID
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) KIND_BLUETOOTH else null
    }

    /**
     * How a device is listed and remembered. Built-in ones by what they are; a headset by its
     * own name when it gives one, so two Bluetooth headsets are told apart. The id is what
     * Settings stores, so it must come out the same each time the headset connects: Android's
     * own device id does not, it is new on every connection.
     */
    private fun outputOf(device: AudioDeviceInfo): Output? {
        val kind = kindOf(device.type) ?: return null
        return when (kind) {
            KIND_SPEAKER -> Output("speaker", "Loudspeaker", kind)
            KIND_EARPIECE -> Output("earpiece", "Earpiece (hold the phone to your ear)", kind)
            else -> {
                val generic = when (kind) {
                    KIND_WIRED -> "Wired headset"
                    KIND_USB -> "USB headset"
                    KIND_BLUETOOTH -> "Bluetooth headset"
                    else -> "Hearing aid"
                }
                // A wired socket reports the phone's own model as its name, which says nothing.
                val name = device.productName?.toString()?.trim().orEmpty()
                val named = name.isNotEmpty() && kind != KIND_WIRED && !name.equals(Build.MODEL, ignoreCase = true)
                if (named) Output("$kind:$name", "$name ($generic)", kind) else Output(kind, generic, kind)
            }
        }
    }

    companion object {
        private const val CHECK_MS = 1_000L
        private const val SETTLE_MS = 500L
        private const val SETTLE_TRIES = 20
        private const val SCO_WAIT_MS = 6_000L

        const val KIND_SPEAKER = "speaker"
        const val KIND_EARPIECE = "earpiece"
        const val KIND_WIRED = "wired"
        const val KIND_USB = "usb"
        const val KIND_BLUETOOTH = "bluetooth"
        const val KIND_HEARING_AID = "hearing-aid"

        /** The order the picker lists them in: the phone's own first, then anything connected. */
        private val ORDER = listOf(KIND_SPEAKER, KIND_EARPIECE, KIND_WIRED, KIND_USB, KIND_BLUETOOTH, KIND_HEARING_AID)

        /** Automatic, first found wins: anything worn on the head, then the loudspeaker — never the earpiece. */
        private val PREFERENCE: List<Int> = buildList {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        }
    }
}
