// Whether a context that says 'running' is rendering.
//
// The case this exists for was measured on a phone: Chromium froze the page, the context's
// currentTime stood still for minutes, and its state never stopped reading 'running'. The
// room trusted the state, kept every <audio> element muted, and went silent.

import test from 'node:test';
import assert from 'node:assert/strict';

import { createClockWatch, MIN_GAP_MS } from '../src/media/context-clock.js';

/** Drive one watch through [wall ms, context seconds] pairs; returns each verdict. */
function run(watch, steps, state = 'running') {
    return steps.map(([now, currentTime]) => watch.sample({ state, currentTime, now }));
}

test('a clock that keeps pace with the wall is rendering', () => {
    const watch = createClockWatch();
    assert.deepEqual(run(watch, [[0, 10], [2000, 12], [4000, 14], [6000, 16]]), [false, false, false, false]);
});

test('a running context whose clock stands still is stalled', () => {
    // The phone's numbers: 165 s on the clock at every look, two seconds apart.
    const watch = createClockWatch();
    const verdicts = run(watch, [[0, 165], [2000, 165], [4000, 165]]);
    assert.deepEqual(verdicts, [false, true, true]);
    assert.equal(watch.stalled, true);
});

test('and is believed again the moment the clock moves', () => {
    const watch = createClockWatch();
    run(watch, [[0, 165], [2000, 165]]);
    assert.equal(watch.sample({ state: 'running', currentTime: 167, now: 4000 }), false);
});

test('a late look is not a stall: the audio thread does not wait for the main one', () => {
    // A main thread blocked for nine seconds finds the clock nine seconds on. Mistaking jank
    // for a dead context would unmute every element and play the room twice.
    const watch = createClockWatch();
    assert.deepEqual(run(watch, [[0, 10], [9000, 19]]), [false, false]);
});

test('looks closer together than a render quantum can resolve decide nothing', () => {
    const watch = createClockWatch();
    run(watch, [[0, 10], [2000, 10]]);
    assert.equal(watch.stalled, true);
    // Too soon to say anything new, either way: the verdict stands.
    assert.equal(watch.sample({ state: 'running', currentTime: 10, now: 2000 + MIN_GAP_MS - 1 }), true);
});

test('a slow trickle is still a stall', () => {
    // A tenth of real time is not a context playing anybody's voice.
    const watch = createClockWatch();
    assert.deepEqual(run(watch, [[0, 10], [2000, 10.05]]), [false, true]);
});

test('a context that admits it is not running is not judged', () => {
    // Suspended and closed contexts are honest, and the listen policy already hands their
    // voices to the elements. Measuring one would only invent a stall.
    const watch = createClockWatch();
    run(watch, [[0, 10], [2000, 10]]);
    assert.equal(watch.stalled, true);
    assert.equal(watch.sample({ state: 'suspended', currentTime: 10, now: 4000 }), false);
    // And when it runs again, it is measured afresh rather than against the old reading.
    assert.equal(watch.sample({ state: 'running', currentTime: 10, now: 6000 }), false);
    assert.equal(watch.sample({ state: 'running', currentTime: 12, now: 8000 }), false);
});

test('reset forgets the old context entirely', () => {
    const watch = createClockWatch();
    run(watch, [[0, 10], [2000, 10]]);
    watch.reset();
    assert.equal(watch.stalled, false);
    assert.equal(watch.sample({ state: 'running', currentTime: 0, now: 3000 }), false);
});

test('no clock to read is not a stall', () => {
    const watch = createClockWatch();
    assert.equal(watch.sample({ state: 'running', currentTime: Number.NaN, now: 0 }), false);
    assert.equal(watch.sample({ state: 'running', currentTime: undefined, now: 2000 }), false);
});
