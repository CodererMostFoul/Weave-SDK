package io.github.weavespeak.client

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

/**
 * Everything the page needs from Android that no stock Capacitor plugin gives it, behind one
 * name. The page reaches it only through src/platform/native-capacitor.js.
 *
 *  - secretGet / secretSet / secretClear: tokens and saved sign-ins (SecretStore).
 *  - log / readLog: the app log (AppLog).
 *  - networkStatus and a "network" event: so a reconnect can start the moment the phone moves
 *    between Wi-Fi and mobile data, rather than when the heartbeat next times out.
 *  - callAudio / audioOutputs / chooseAudioOutput: where call audio plays (AudioRoute).
 *  - voiceSession and a "voiceAction" event: voice that keeps going with the screen off
 *    (VoiceService), and the controls that come back from its notification, a headset or
 *    the system (VoiceBus).
 *  - pip and a "window" event: picture-in-picture during a video call.
 *  - ear: hold-to-ear (EarSensor).
 *  - backgroundStatus / askBackground, notificationStatus / askNotifications: the two
 *    permissions voice in the background depends on.
 *  - ring / endRing and a "callAction" event: the phone ringing for a call (IncomingCall),
 *    and Answer or Decline pressed on it. showOverLock / unlock / fullScreenStatus /
 *    askFullScreen: a call on top of the lock screen.
 *  - pushRegister / pushUnregister / pushStatus: calls that ring with Weave closed
 *    (PushRegistry, WeavePushService).
 *
 * Plugin methods run on Capacitor's plugin thread, not the main thread, so the Keystore and
 * file work here does not stall the UI.
 */
@CapacitorPlugin(
    name = "WeaveNative",
    permissions = [Permission(alias = "notifications", strings = [Manifest.permission.POST_NOTIFICATIONS])],
)
class WeaveNativePlugin : Plugin() {

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Call audio on the loudspeaker (or a headset) while the page is in voice. See AudioRoute.
    private val route by lazy { AudioRoute.of(context) }
    private val ear by lazy { EarSensor(context) }

    override fun load() {
        VoiceBus.listener = { action -> notifyListeners("voiceAction", JSObject().apply { put("action", action) }) }
        VoiceBus.windowListener = { pip, locked ->
            notifyListeners("window", JSObject().apply {
                pip?.let { put("pip", it) }
                locked?.let { put("locked", it) }
            })
        }
        // Retained until the page listens: an Answer that opened the app from cold arrives
        // long before the page has signed back in and subscribed.
        VoiceBus.callListener = { a ->
            notifyListeners("callAction", JSObject().apply {
                put("action", a.action)
                put("callId", a.callId)
                put("kind", a.kind)
                put("threadId", a.threadId)
                put("channelId", a.channelId)
                put("serverId", a.serverId)
            }, true)
        }
        for (held in VoiceBus.takeHeld()) VoiceBus.callAction(held)

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report(connectivity)
            override fun onLost(network: Network) = report(connectivity)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = report(connectivity)
        }
        connectivity.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    // Nobody holds a phone to their ear with the app in the background.
    override fun handleOnStop() {
        ear.arm(false)
    }

    override fun handleOnDestroy() {
        VoiceBus.listener = null
        VoiceBus.windowListener = null
        VoiceBus.callListener = null
        ear.arm(false)
        route.disengage()
        val callback = networkCallback ?: return
        networkCallback = null
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Already gone; nothing to undo.
        }
    }

    private var lastReported: String? = null

    private fun report(connectivity: ConnectivityManager) {
        val status = statusOf(connectivity)
        // onCapabilitiesChanged fires for signal-strength changes too. Only a change the page
        // could act on — up, down, or a different kind of link — is worth waking it for.
        val key = "${status.getBoolean("available")}/${status.getString("type")}"
        if (key == lastReported) return
        lastReported = key
        notifyListeners("network", status)
    }

    private fun statusOf(connectivity: ConnectivityManager): JSObject {
        val caps = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val available = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return JSObject().apply {
            put("available", available)
            put("type", type)
        }
    }

    @PluginMethod
    fun networkStatus(call: PluginCall) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        call.resolve(statusOf(connectivity))
    }

    /** The store name and key every secret call needs, or null after rejecting the call. */
    private fun target(call: PluginCall): Pair<String, String>? {
        val store = call.getString("store")
        val key = call.getString("key")
        if (store == null || store !in SecretStore.STORES) {
            call.reject("unknown store")
            return null
        }
        if (key.isNullOrEmpty()) {
            call.reject("key is required")
            return null
        }
        return store to key
    }

    @PluginMethod
    fun secretGet(call: PluginCall) {
        val (store, key) = target(call) ?: return
        call.resolve(JSObject().apply { put("value", SecretStore.get(context, store, key)) })
    }

    @PluginMethod
    fun secretSet(call: PluginCall) {
        val (store, key) = target(call) ?: return
        val value = call.getString("value")
        if (value == null) {
            call.reject("value is required")
            return
        }
        call.resolve(JSObject().apply { put("ok", SecretStore.set(context, store, key, value)) })
    }

    @PluginMethod
    fun secretClear(call: PluginCall) {
        val (store, key) = target(call) ?: return
        call.resolve(JSObject().apply { put("ok", SecretStore.clear(context, store, key)) })
    }

    @PluginMethod
    fun log(call: PluginCall) {
        val array = call.getArray("lines")
        val lines = if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }
        AppLog.append(context, lines)
        call.resolve()
    }

    /**
     * The page is in voice (on: true) or has left it (on: false). Answers with the route.
     * prefer, when given, is the output chosen in Settings, so a room starts on it.
     */
    @PluginMethod
    fun callAudio(call: PluginCall) {
        val on = call.getBoolean("on") ?: false
        val prefer = call.getString("prefer")
        // AudioManager is a main-thread affair, and plugin methods arrive on another thread.
        bridge.executeOnMainThread {
            if (prefer != null) route.prefer(prefer)
            if (on) route.engage() else route.disengage()
            call.resolve(JSObject().apply {
                put("route", route.current)
                put("phone", route.state())
            })
        }
    }

    /** Every place call audio can go now, for the Settings picker, and which one it is on. */
    @PluginMethod
    fun audioOutputs(call: PluginCall) {
        bridge.executeOnMainThread {
            val list = JSArray()
            for (output in route.outputs()) {
                list.put(JSObject().apply {
                    put("id", output.id)
                    put("label", output.label)
                    put("kind", output.kind)
                })
            }
            call.resolve(JSObject().apply {
                put("outputs", list)
                put("current", route.current)
                put("chosen", route.preferred)
                put("inVoice", route.inVoice)
            })
        }
    }

    /** The output chosen in Settings; "" is automatic. Applied at once while in voice. */
    @PluginMethod
    fun chooseAudioOutput(call: PluginCall) {
        val id = call.getString("id") ?: ""
        bridge.executeOnMainThread {
            route.prefer(id)
            call.resolve(JSObject().apply { put("route", route.current) })
        }
    }

    /**
     * The page is in voice (on: true, with what the notification should say) or has left it.
     * Starting only works from the foreground, which is where joining voice happens; a start
     * Android refuses is answered with ok: false and the call simply goes on in front.
     */
    @PluginMethod
    fun voiceSession(call: PluginCall) {
        val on = call.getBoolean("on") ?: false
        bridge.executeOnMainThread {
            // The page carrying the call must not be frozen when the screen goes off, which
            // the service alone does not prevent. See WeaveWebView.
            (bridge.webView as? WeaveWebView)?.holdVisible(on)
            if (!on) {
                VoiceService.end()
                call.resolve(JSObject().apply { put("ok", true) })
                return@executeOnMainThread
            }
            val session = VoiceService.Session(
                title = call.getString("title")?.take(80)?.ifBlank { null } ?: "Weave",
                kind = if (call.getString("kind") == "call") "call" else "room",
                muted = call.getBoolean("muted") ?: false,
                video = call.getBoolean("video") ?: false,
                // Read as a long from the raw data: a millisecond timestamp arrives as one, and
                // PluginCall.getDouble answers null for it, which restarted the notification's
                // timer at every update (a mute, a camera) instead of counting from the start.
                since = call.data.optLong("since", 0L).takeIf { it > 0 } ?: System.currentTimeMillis(),
                held = call.getBoolean("held") ?: false,
            )
            val ok = try {
                VoiceService.show(context, session)
                true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException and friends: started from the
                // background. Said, not thrown: there is nothing for the page to do about it.
                AppLog.write(context, "warn", "voice service not started: $e")
                false
            }
            // Picture-in-picture's Mute button names the state it would change to.
            (activity as? MainActivity)?.applyPipParams()
            call.resolve(JSObject().apply { put("ok", ok) })
        }
    }

    /** Whether leaving the app now should shrink it to picture-in-picture. */
    @PluginMethod
    fun pip(call: PluginCall) {
        VoiceBus.pipAllowed = call.getBoolean("allowed") ?: false
        bridge.executeOnMainThread {
            (activity as? MainActivity)?.applyPipParams()
            call.resolve(JSObject().apply { put("available", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) })
        }
    }

    /** Hold-to-ear may listen now (in voice, camera off, app on screen) or must not. */
    @PluginMethod
    fun ear(call: PluginCall) {
        val armed = call.getBoolean("armed") ?: false
        bridge.executeOnMainThread {
            ear.arm(armed)
            call.resolve(JSObject().apply { put("available", ear.available) })
        }
    }

    /**
     * Whether Telecom (Android's call system) carries voice on this install. Off until proven
     * on a phone; switchable while testing without a rebuild. Takes effect on the next join.
     */
    @PluginMethod
    fun telecom(call: PluginCall) {
        call.getBoolean("on")?.let { TelecomCallSession.setEnabled(context, it) }
        call.resolve(JSObject().apply { put("on", TelecomCallSession.enabled(context)) })
    }

    /** Whether the system's battery optimisation leaves Weave alone in the background. */
    @PluginMethod
    fun backgroundStatus(call: PluginCall) {
        val power = context.getSystemService(PowerManager::class.java)
        call.resolve(JSObject().apply { put("allowed", power.isIgnoringBatteryOptimizations(context.packageName)) })
    }

    /**
     * Ask to be left out of battery optimisation. Samsung and others put apps they consider
     * idle to sleep regardless of a foreground service; this is the one switch that stops it.
     */
    @SuppressLint("BatteryLife")
    @PluginMethod
    fun askBackground(call: PluginCall) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        try {
            activity.startActivity(intent)
            call.resolve(JSObject().apply { put("asked", true) })
        } catch (e: Exception) {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            call.resolve(JSObject().apply { put("asked", false) })
        }
    }

    /** Whether notifications may show: the call's controls in the background live in one. */
    @PluginMethod
    fun notificationStatus(call: PluginCall) {
        call.resolve(JSObject().apply { put("allowed", NotificationManagerCompat.from(context).areNotificationsEnabled()) })
    }

    @PluginMethod
    fun askNotifications(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || getPermissionState("notifications") == PermissionState.GRANTED) {
            notificationStatus(call)
            return
        }
        requestPermissionForAlias("notifications", call, "notificationsAnswered")
    }

    @PermissionCallback
    private fun notificationsAnswered(call: PluginCall) {
        notificationStatus(call)
    }

    /** Ring for a call: the caller (or huddle) by name, until expiresAt. Ignores a repeat. */
    @PluginMethod
    fun ring(call: PluginCall) {
        val callId = call.getString("callId")
        if (callId.isNullOrEmpty()) {
            call.reject("callId is required")
            return
        }
        val ring = IncomingCall.Ring(
            callId = callId,
            title = call.getString("title")?.take(80)?.ifBlank { null } ?: "Someone",
            subtitle = call.getString("subtitle")?.take(120) ?: "is calling you",
            kind = if (call.getString("kind") == "huddle") "huddle" else "dm",
            threadId = call.getString("threadId"),
            channelId = call.getString("channelId"),
            serverId = call.getString("serverId"),
            expiresAt = call.data.optLong("expiresAt", 0L),
        )
        val rang = IncomingCall.ring(context, ring)
        call.resolve(JSObject().apply { put("ringing", rang) })
    }

    /** Stop ringing for a call, whatever ended it. */
    @PluginMethod
    fun endRing(call: PluginCall) {
        call.getString("callId")?.let { IncomingCall.end(context, it) }
        call.resolve()
    }

    /** Whether the window may sit on top of the lock screen: only while a call rings or runs. */
    @PluginMethod
    fun showOverLock(call: PluginCall) {
        val on = call.getBoolean("on") ?: false
        bridge.executeOnMainThread {
            (activity as? MainActivity)?.showOverLock(on)
            call.resolve()
        }
    }

    @PluginMethod
    fun unlock(call: PluginCall) {
        bridge.executeOnMainThread {
            (activity as? MainActivity)?.unlock()
            call.resolve()
        }
    }

    /** Android 14+: whether calls may fill the screen over the lock screen. */
    @PluginMethod
    fun fullScreenStatus(call: PluginCall) {
        call.resolve(JSObject().apply { put("allowed", IncomingCall.canFillScreen(context)) })
    }

    @PluginMethod
    fun askFullScreen(call: PluginCall) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
                )
            } catch (_: Exception) {
                // No settings screen on this build; nothing else to offer.
            }
        }
        fullScreenStatus(call)
    }

    /** Ask for this server's calls while Weave is closed. Answers whether a distributor took it. */
    @PluginMethod
    fun pushRegister(call: PluginCall) {
        val serverId = call.getString("serverId")
        val origin = call.getString("origin")
        if (serverId.isNullOrEmpty() || origin.isNullOrEmpty()) {
            call.reject("serverId and origin are required")
            return
        }
        bridge.executeOnMainThread {
            PushRegistry.register(activity, serverId, origin, call.getString("vapidKey")) { ok ->
                call.resolve(PushRegistry.status(context, serverId).put("asked", ok).let { JSObject.fromJSONObject(it) })
            }
        }
    }

    @PluginMethod
    fun pushUnregister(call: PluginCall) {
        call.getString("serverId")?.let { PushRegistry.unregister(context, it) }
        call.resolve()
    }

    @PluginMethod
    fun pushStatus(call: PluginCall) {
        call.resolve(JSObject.fromJSONObject(PushRegistry.status(context, call.getString("serverId"))))
    }

    @PluginMethod
    fun readLog(call: PluginCall) {
        call.resolve(AppLog.tail(context, call.getInt("maxBytes", 64 * 1024) ?: (64 * 1024)))
    }
}
