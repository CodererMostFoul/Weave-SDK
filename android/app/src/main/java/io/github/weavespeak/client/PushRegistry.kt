package io.github.weavespeak.client

import android.app.Activity
import android.content.Context
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Calls that ring with Weave closed: the phone's side of Web Push, through UnifiedPush.
 *
 * Weave has no push service of its own and wants none: a self-hosted server should not
 * depend on Google or on whoever builds the app. UnifiedPush hands the job to a separate
 * "distributor" app the person chooses — ntfy is the usual one, and can itself be pointed at
 * a server they run. The distributor keeps one connection open for every app that uses it,
 * and wakes Weave (WeavePushService) when a Weave server sends something.
 *
 * One registration per saved server, named by the server's id, so two servers ring
 * independently and signing out of one stops only its calls. What a registration produces
 * (an endpoint URL plus the keys a server encrypts with) is sent to that server from here,
 * with that server's saved session token, rather than by the page: a distributor can hand
 * out a new endpoint at any time, including while Weave is closed, and the new one must still
 * reach the server or the phone silently stops ringing.
 *
 * The server's address is kept here too (the page owns the list of servers; this is only
 * the copy push needs), so a Decline pressed with the app closed can reach it as well.
 */
object PushRegistry {

    private const val PREFS = "weave-push"
    private val io = Executors.newSingleThreadExecutor()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The address of a saved server, as the page last registered it. */
    fun originOf(context: Context, serverId: String): String? = prefs(context).getString("origin:$serverId", null)

    /**
     * Ask for calls from this server while Weave is closed. Uses the distributor already
     * chosen, or the phone's only one; with several installed and none chosen, Android asks.
     * Answers false when there is no distributor at all.
     */
    fun register(activity: Activity, serverId: String, origin: String, vapidKey: String?, done: (Boolean) -> Unit) {
        prefs(activity).edit()
            .putString("origin:$serverId", origin.trimEnd('/'))
            .apply()
        if (UnifiedPush.getDistributors(activity).isEmpty()) {
            note(activity, serverId, "no-distributor")
            done(false)
            return
        }
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (!ok) {
                note(activity, serverId, "no-distributor")
                done(false)
                return@tryUseCurrentOrDefaultDistributor
            }
            try {
                UnifiedPush.register(activity, serverId, "Weave calls", vapidKey?.ifBlank { null })
                done(true)
            } catch (e: Exception) {
                AppLog.write(activity, "warn", "push registration refused: $e")
                note(activity, serverId, "refused")
                done(false)
            }
        }
    }

    /** Signed out of this server: no more calls from it. The server drops its copy on its own. */
    fun unregister(context: Context, serverId: String) {
        try {
            UnifiedPush.unregister(context, serverId)
        } catch (_: Exception) {
            // Never registered; nothing to undo.
        }
        prefs(context).edit()
            .remove("endpoint:$serverId")
            .remove("status:$serverId")
            .apply()
    }

    /** What Settings shows: is there a distributor, which, and is this server receiving. */
    fun status(context: Context, serverId: String?): JSONObject {
        val distributors = UnifiedPush.getDistributors(context).filter { it != context.packageName }
        val chosen = UnifiedPush.getAckDistributor(context)
        return JSONObject().apply {
            put("distributors", distributors.size)
            put("distributor", chosen?.let { labelOf(context, it) } ?: JSONObject.NULL)
            put("registered", serverId != null && prefs(context).contains("endpoint:$serverId"))
            put("state", serverId?.let { prefs(context).getString("status:$serverId", null) } ?: JSONObject.NULL)
        }
    }

    private fun labelOf(context: Context, pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    fun note(context: Context, serverId: String, state: String) {
        prefs(context).edit().putString("status:$serverId", state).apply()
    }

    /** A new endpoint from the distributor: tell the server, with this phone's session for it. */
    fun endpointChanged(context: Context, serverId: String, url: String, p256dh: String?, auth: String?) {
        if (p256dh == null || auth == null) {
            // Web Push without keys cannot be encrypted, and the server refuses to send in clear.
            note(context, serverId, "no-keys")
            return
        }
        prefs(context).edit().putString("endpoint:$serverId", url).apply()
        val body = JSONObject()
            .put("endpoint", url)
            .put("keys", JSONObject().put("p256dh", p256dh).put("auth", auth))
        send(context, serverId, "PUT", "/api/push/subscription", body) { ok ->
            note(context, serverId, if (ok) "on" else "upload-failed")
        }
    }

    /**
     * One request to a saved server, as this phone's signed-in session for it, off the main
     * thread. For the few things that must work with the page closed: the push subscription,
     * and declining a call from its notification.
     */
    fun send(context: Context, serverId: String, method: String, path: String, body: JSONObject?, done: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        io.execute {
            val origin = originOf(app, serverId)
            val token = SecretStore.get(app, "tokens", serverId)
            if (origin == null || token == null) {
                done(false)
                return@execute
            }
            val ok = try {
                val conn = URL(origin + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
            } catch (e: Exception) {
                AppLog.write(app, "warn", "$method $path failed: ${e.javaClass.simpleName}")
                false
            }
            done(ok)
        }
    }
}
