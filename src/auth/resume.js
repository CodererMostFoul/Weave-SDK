// Signing straight back in when the app opens.
//
// A phone app that asks for a password every time it is opened — which, on Android, is
// every time the system reclaimed it in the background — is one nobody keeps. So the
// Android app tries the saved session token first. The server is asked who the token
// belongs to, and the answer decides everything:
//
//   it names somebody     → straight into the room, as if they had just signed in
//   401 / 403             → that session is over (signed out elsewhere, expired, banned):
//                           the token is dropped and the sign-in screen says why
//   anything else         → the server could not be reached, or failed. That says nothing
//                           about the session, so the token is KEPT for the next launch
//                           and the sign-in screen says what happened
//
// Only the mobile build does this (platform.session.resumeOnLaunch). Desktop behaviour is
// unchanged: it fills in a saved sign-in rather than skipping it.

/** Long enough for a phone waking on mobile data; short enough not to feel like a hang. */
export const RESUME_TIMEOUT_MS = 8_000;

/** Where the "Drop me straight in" choice from the last sign-in is kept, per server. */
export const RESUME_AUTOJOIN_KEY = 'resume.autoJoin';

/**
 * Whether arriving puts you in voice.
 *
 * On a computer it is the person's own choice ("Drop me straight in"). On a phone it is
 * never: standing in a room is being in voice, and on Android an open microphone is what
 * puts the whole phone into call mode — the volume keys set call volume, other apps' sound
 * goes quiet, a Bluetooth headset drops to its call profile. Opening Weave to read a message
 * must not do that, any more than opening WhatsApp starts a call. So a phone arrives on the
 * conversation, and voice starts when somebody taps a room or answers a call.
 *
 * Arriving only: a reconnect while in voice goes back to the room it stood in, whatever this
 * says (net/link.js tracks where the session stands).
 *
 * @param {{ target?: string, chosen?: boolean }} arrival
 */
export function arriveInVoice({ target = 'browser', chosen = true } = {}) {
    if (target === 'mobile') return false;
    return chosen !== false;
}

/**
 * What the room should say about voice when this device arrives standing nowhere.
 *
 * Usually nothing. But a phone opened while the same account is talking from a computer is
 * the moment somebody may want to carry the conversation out of the door, and the room
 * already has a way to offer exactly that: the "You are in voice on another device" notice
 * with Join voice here. So when another of this account's connections stands in a room,
 * say so, naming that room.
 *
 * @param {{ selfCid?: string|null, selfUserId?: string|null, peers?: Iterable<object> }} roster
 * @returns {{ state: 'elsewhere', roomId: string } | null}
 */
export function arrivalVoiceState({ selfCid = null, selfUserId = null, peers = [] } = {}) {
    if (!selfUserId) return null;
    for (const peer of peers) {
        if (peer?.userId === selfUserId && peer.cid !== selfCid && peer.channelId) {
            return { state: 'elsewhere', roomId: peer.channelId };
        }
    }
    return null;
}

/**
 * @param {{ user?: object|null, status?: number|null, message?: string|null }} result
 *   `user` from a successful GET /api/me, or the failed request's status (0 = no answer)
 */
export function resumeOutcome({ user = null, status = null, message = null } = {}) {
    if (user && typeof user === 'object') return { kind: 'enter', user, clearToken: false, notice: null };

    if (status === 401 || status === 403) {
        return {
            kind: 'signin',
            clearToken: true,
            // A 403 is the server's to explain (a ban says so); a 401 only ever means the
            // token stopped working, and its wording is written for developers.
            notice: (status === 403 && message) || 'Your session has ended. Sign in again.',
        };
    }

    return {
        kind: 'signin',
        clearToken: false,
        notice: 'Could not reach the server to sign you back in. Sign in once it answers.',
    };
}
