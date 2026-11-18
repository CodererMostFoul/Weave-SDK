package io.github.weavespeak.client

import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Where a Weave server's pushes arrive, with or without the app open.
 *
 * The distributor (ntfy, usually) wakes this service; the message has already been
 * decrypted by the UnifiedPush library with keys that never left this phone, so the
 * distributor and anyone between it and the server saw only ciphertext. Each `instance` is
 * a saved server's id (PushRegistry).
 *
 * Three kinds of message (docs/push.md on the server):
 *  - ring:        somebody is calling, or a huddle you belong to started — ring the phone;
 *  - ring_cancel: it was answered elsewhere, declined, or given up on — stop ringing;
 *  - missed:      it rang and nobody answered — say so, with Call back or Join.
 *
 * Everything here works with no page at all: ringing is the phone's own notification
 * (IncomingCall), and answering opens the app on the call.
 */
class WeavePushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        PushRegistry.endpointChanged(this, instance, endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) {
            // A message the library could not decrypt is not from the server we registered
            // with (or was tampered with on the way). Nothing in it can be believed.
            AppLog.write(this, "warn", "a push arrived that could not be decrypted; ignored")
            return
        }
        val json = try {
            JSONObject(String(message.content, Charsets.UTF_8))
        } catch (_: Exception) {
            return
        }
        val callId = json.optString("callId").ifEmpty { return }
        val call = json.optJSONObject("call") ?: JSONObject()
        val from = json.optJSONObject("from")
        val who = from?.optString("displayName")?.ifEmpty { null } ?: from?.optString("username")?.ifEmpty { null } ?: "Someone"
        val huddle = call.optString("type") == "huddle"
        val room = call.optString("name").ifEmpty { "a private room" }
        when (json.optString("kind")) {
            "ring" -> IncomingCall.ring(
                this,
                IncomingCall.Ring(
                    callId = callId,
                    title = if (huddle) room else who,
                    subtitle = if (huddle) "$who started a huddle" else "is calling you",
                    kind = if (huddle) "huddle" else "dm",
                    threadId = call.optString("threadId").ifEmpty { null },
                    channelId = call.optString("channelId").ifEmpty { null },
                    serverId = instance,
                    expiresAt = json.optLong("expiresAt", 0L),
                ),
            )
            "ring_cancel" -> IncomingCall.end(this, callId)
            "missed" -> {
                IncomingCall.end(this, callId)
                MissedCall.show(
                    this, callId, instance,
                    if (huddle) "huddle" else "dm",
                    if (huddle) "You missed a huddle in $room" else "Missed call from $who",
                    if (huddle) "$who started it" else "Private call",
                    call.optString("threadId").ifEmpty { null },
                    call.optString("channelId").ifEmpty { null },
                )
            }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        AppLog.write(this, "warn", "push registration failed: $reason")
        PushRegistry.note(this, instance, "failed:$reason")
    }

    override fun onUnregistered(instance: String) {
        AppLog.write(this, "info", "push registration removed by the distributor")
        PushRegistry.note(this, instance, "unregistered")
    }
}
