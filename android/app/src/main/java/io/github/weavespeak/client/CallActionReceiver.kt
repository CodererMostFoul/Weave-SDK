package io.github.weavespeak.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * Decline, pressed on a ringing call's notification.
 *
 * A receiver, not an activity: declining must not open the app, and since Android 12 a
 * notification may not open an activity by way of a receiver or service anyway. Not
 * exported, so only this app's own notification can send it.
 *
 * The ring stops at once, and the server is told straight from here, so the caller hears
 * "Call declined" even when Weave was closed and the page is not running to say so (a phone
 * woken by a push). The page is told as well, when it is there, to close its own ring; the
 * server treats a decline it has already had as nothing.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        val action = VoiceBus.CallAction.from(intent) ?: return
        IncomingCall.end(context, action.callId)
        VoiceBus.callAction(action)

        val serverId = action.serverId ?: return
        val path = when {
            action.kind == "huddle" && action.channelId != null ->
                "/api/private-channels/${Uri.encode(action.channelId)}/ring/decline"
            action.kind == "dm" && action.threadId != null ->
                "/api/dm/threads/${Uri.encode(action.threadId)}/call/decline"
            else -> return
        }
        // A server before 0.1.43 named calls by thread only (an id starting "dm:"); its decline
        // goes over the socket, by the page, and there is nothing to send from here.
        if (action.callId.startsWith("dm:")) return
        val pending = goAsync()
        PushRegistry.send(context, serverId, "POST", path, JSONObject().put("callId", action.callId)) {
            pending.finish()
        }
    }

    companion object {
        const val ACTION_DECLINE = "io.github.weavespeak.client.call.DECLINE"
    }
}
