// Voice as the phone around it sees it.
//
// On Android the room is not only a page: while you are in voice, a notification carries
// the call's controls, a Bluetooth headset can mute it, a phone call can put it on hold,
// leaving the app can shrink it to picture-in-picture, and lifting the phone to your ear
// can move the sound to the earpiece. Each of those is a decision about the room's state,
// and each is made here, as a pure function, so the decisions are tested without a phone
// and the room only carries them out (src/room/index.js) through the platform adapter.
//
// The browser and desktop builds answer "not here" to every one of these capabilities
// (src/platform/mobile.js), so nothing in this file changes how they behave.

/**
 * What the call notification should say, or null when there is no call to keep alive.
 *
 * @param {object} s
 * @param {string|null} s.standing      the room being stood in (null: nowhere)
 * @param {string|null} s.roomName      its name, when it is a listed room
 * @param {string|null} s.callWith      the other person, when it is a private call
 * @param {boolean} s.muted
 * @param {boolean} s.video             your camera is on
 * @param {number} s.since              when voice began here, for the notification's timer
 * @param {boolean} s.held              on hold for a phone call
 * @param {boolean} s.mic               the microphone is live (permission granted, track open).
 *   Android 14 will only keep a microphone running behind the lock screen for a service that
 *   was told so after access was granted; the change is what tells it.
 */
export function voiceSessionFor({
    standing = null, roomName = null, callWith = null,
    muted = false, video = false, since = 0, held = false, mic = false,
} = {}) {
    if (!standing) return null;
    const call = Boolean(callWith) || !roomName;
    return {
        title: call ? (callWith || 'Private call') : roomName,
        kind: call ? 'call' : 'room',
        muted: Boolean(muted),
        video: Boolean(video),
        since: Number(since) || 0,
        held: Boolean(held),
        mic: Boolean(mic),
    };
}

/** Whether two answers from voiceSessionFor would draw the same notification. */
export function sameSession(a, b) {
    if (!a || !b) return a === b;
    return a.title === b.title && a.kind === b.kind && a.muted === b.muted
        && a.video === b.video && a.since === b.since && a.held === b.held && a.mic === b.mic;
}

/**
 * Whether leaving the app should shrink it to picture-in-picture rather than hide it.
 * Only with a picture worth keeping on screen: your own camera (which would otherwise have
 * to stop), or somebody's stream you are watching. A voice-only call just goes to the
 * background, where its notification carries it.
 */
export function pipAllowed({ inVoice = false, webcamOn = false, watching = false } = {}) {
    return Boolean(inVoice && (webcamOn || watching));
}

/**
 * What happens to your camera when the app goes out of sight. Kept in picture-in-picture,
 * which is a visible window; paused otherwise, because Android takes a hidden app's camera
 * away regardless, and pausing on purpose means viewers see it stop rather than freeze.
 */
export function cameraOnHide({ pip = false } = {}) {
    return pip ? 'keep' : 'pause';
}

/**
 * Whether lifting the phone to your ear may move the sound to the earpiece. Only in voice,
 * with nothing to look at (no camera of yours, no stream being watched), and only while the
 * app is actually on screen in the full window: a phone in a pocket or face down is never
 * being lifted to anybody's ear.
 */
export function earArmed({
    inVoice = false, webcamOn = false, watching = false, visible = false, pip = false,
} = {}) {
    return Boolean(inVoice && !webcamOn && !watching && visible && !pip);
}

/**
 * A phone call put this one on hold, or gave it back.
 *
 * Hold is silence both ways — muted and deafened — announced to the room through the same
 * setMute everybody already understands. What it must never do is lose what you had before:
 * somebody who was muted before the phone rang is still muted after it. So the state before
 * the hold is kept and handed back, and a second hold, or a resume with nothing held, does
 * nothing.
 *
 * @param {{ held: boolean, before: {muted: boolean, deafened: boolean}|null }} hold
 * @param {'hold'|'resume'} action
 * @param {{ muted: boolean, deafened: boolean }} now  your state as the room shows it
 * @returns {{ hold: object, apply: {muted: boolean, deafened: boolean}|null }}
 */
export function holdTransition(hold = { held: false, before: null }, action, now = {}) {
    if (action === 'hold' && !hold.held) {
        return {
            hold: { held: true, before: { muted: Boolean(now.muted), deafened: Boolean(now.deafened) } },
            apply: { muted: true, deafened: true },
        };
    }
    if (action === 'resume' && hold.held) {
        return {
            hold: { held: false, before: null },
            apply: hold.before ?? { muted: false, deafened: false },
        };
    }
    return { hold, apply: null };
}

/**
 * What a change of network asks of the room. Coming back online, or moving between kinds of
 * network (Wi-Fi to mobile data), means the socket and the media path may both be dead
 * without knowing it: probe the socket, repair the media. Going offline asks nothing — there
 * is nothing to reach — and a report that changes nothing (signal strength) asks nothing.
 *
 * @param {{available: boolean, type: string}|null} before
 * @param {{available: boolean, type: string}} after
 */
export function networkReaction(before, after) {
    if (!after?.available) return { probe: false, repair: false };
    const changed = !before?.available || before.type !== after.type;
    return { probe: changed, repair: changed };
}
