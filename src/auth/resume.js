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
