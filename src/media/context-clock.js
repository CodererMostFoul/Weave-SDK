// Whether an AudioContext that says 'running' is actually rendering.
//
// Its state is not evidence. Measured on a phone (Android 10, WebView 157): when Chromium
// froze the page, both of its AudioContexts stopped rendering — currentTime stood still for
// minutes — while `state` went on reading 'running' and no statechange event fired. The room
// plays every voice through a context while it runs (listen-policy.js), so a context in that
// condition is a room that has gone silent with every indicator saying otherwise. An output
// device failing underneath a context might leave it the same way; that has not been seen,
// but it would be caught here too.
//
// The clock is the evidence. currentTime advances on the audio thread whatever the page's
// main thread is doing, so comparing it against the wall clock between two looks tells a
// rendering context from a stopped one without any help from the context itself. A janky
// main thread does not fool it: a late look sees the clock advanced by just as much.
//
// Pure, so the judgement is tested under `node --test` with time passed in.

/** Too short a gap between looks says nothing: the clock moves in render quanta. */
export const MIN_GAP_MS = 1_500;

/** A rendering context keeps pace with the wall clock; one doing under a tenth of that has stopped. */
export const MIN_PACE = 0.1;

/**
 * A watch on one context. Feed it `sample()` on a timer; it answers whether the context is
 * stalled — says it runs, and does not.
 */
export function createClockWatch({ minGapMs = MIN_GAP_MS, minPace = MIN_PACE } = {}) {
    let last = null;
    let stalled = false;

    return {
        /**
         * @param {object} s
         * @param {string} s.state        the context's own state
         * @param {number} s.currentTime  the context's clock, in seconds
         * @param {number} s.now          the wall clock, in milliseconds
         * @returns {boolean} whether the context is stalled
         */
        sample({ state, currentTime, now }) {
            // A suspended or closed context is honest about not running; the listen policy
            // already hands its voices to the elements, and there is nothing to measure.
            if (state !== 'running' || !Number.isFinite(currentTime)) {
                last = null;
                stalled = false;
                return false;
            }
            if (!last) {
                last = { now, clock: currentTime };
                return stalled;
            }
            const gapMs = now - last.now;
            if (gapMs < minGapMs) return stalled;
            const advancedMs = (currentTime - last.clock) * 1000;
            stalled = advancedMs < gapMs * minPace;
            last = { now, clock: currentTime };
            return stalled;
        },

        /** A new context, or none: what was measured belonged to the old one. */
        reset() {
            last = null;
            stalled = false;
        },

        get stalled() { return stalled; },
    };
}
