package io.github.weavespeak.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Everything the page needs from Android that no stock Capacitor plugin gives it, behind one
 * name. The page reaches it only through src/platform/native-capacitor.js.
 *
 *  - secretGet / secretSet / secretClear: tokens and saved sign-ins (SecretStore).
 *  - log / readLog: the app log (AppLog).
 *  - networkStatus and a "network" event: so a reconnect can start the moment the phone moves
 *    between Wi-Fi and mobile data, rather than when the heartbeat next times out.
 *
 * Plugin methods run on Capacitor's plugin thread, not the main thread, so the Keystore and
 * file work here does not stall the UI.
 */
@CapacitorPlugin(name = "WeaveNative")
class WeaveNativePlugin : Plugin() {

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Call audio on the loudspeaker (or a headset) while the page is in voice. See AudioRoute.
    private val route by lazy { AudioRoute(context) }

    override fun load() {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report(connectivity)
            override fun onLost(network: Network) = report(connectivity)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = report(connectivity)
        }
        connectivity.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    override fun handleOnDestroy() {
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

    @PluginMethod
    fun readLog(call: PluginCall) {
        call.resolve(AppLog.tail(context, call.getInt("maxBytes", 64 * 1024) ?: (64 * 1024)))
    }
}
