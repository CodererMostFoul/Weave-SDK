// Pressing on a person with a finger.
//
// The rule being protected is the scroll. The room list is something a thumb drags far more
// often than it opens a menu, so the one failure that matters is a scroll being read as a
// press — a menu popping up under somebody who was only trying to see further down.

import test from 'node:test';
import assert from 'node:assert/strict';

import { pressVerdict, LONG_PRESS_MS, PRESS_SLOP_PX } from '../src/ui/long-press.js';

const touch = (extra) => pressVerdict({ pointerType: 'touch', ...extra });

test('a short press that lifts is a tap; one still down is undecided', () => {
    assert.equal(touch({ elapsedMs: 120, released: true }), 'tap');
    assert.equal(touch({ elapsedMs: 120 }), 'wait');
    assert.equal(touch({ elapsedMs: 0, released: true }), 'tap');
});

test('holding past the threshold is a long press, whether or not it has lifted yet', () => {
    assert.equal(touch({ elapsedMs: LONG_PRESS_MS }), 'long');
    assert.equal(touch({ elapsedMs: LONG_PRESS_MS + 500 }), 'long');
    assert.equal(touch({ elapsedMs: LONG_PRESS_MS - 1 }), 'wait');
    assert.ok(LONG_PRESS_MS >= 400 && LONG_PRESS_MS <= 600, 'long enough to never be a tap, short enough not to feel stuck');
});

test('a finger that moves is scrolling, and stops being a press for good', () => {
    assert.equal(touch({ elapsedMs: 80, dy: PRESS_SLOP_PX + 1 }), 'cancel');
    assert.equal(touch({ elapsedMs: 80, dx: -(PRESS_SLOP_PX + 1) }), 'cancel');
    // Diagonal drift adds up: 8 and 8 is further than 10.
    assert.equal(touch({ elapsedMs: 80, dx: 8, dy: 8 }), 'cancel');
    // A long hold that then drifts is still a scroll — the timer firing does not excuse it.
    assert.equal(touch({ elapsedMs: LONG_PRESS_MS + 10, dy: 30 }), 'cancel');
    // A tremor inside the slop is still a press.
    assert.equal(touch({ elapsedMs: 80, dx: 3, dy: -4, released: true }), 'tap');
});

test('the platform taking the gesture cancels it', () => {
    assert.equal(touch({ elapsedMs: 200, cancelled: true }), 'cancel');
    assert.equal(touch({ elapsedMs: LONG_PRESS_MS + 1, cancelled: true }), 'cancel');
});

test('a mouse is never judged here — it has right-click and drag already', () => {
    assert.equal(pressVerdict({ pointerType: 'mouse', elapsedMs: 900 }), 'ignore');
    assert.equal(pressVerdict({ pointerType: 'mouse', elapsedMs: 10, released: true }), 'ignore');
    // A pen behaves like a finger: no right button worth relying on.
    assert.equal(pressVerdict({ pointerType: 'pen', elapsedMs: 10, released: true }), 'tap');
});
