// Telling a press apart from a scroll, and a tap from a hold.
//
// On a touchscreen a person's menu has no right-click to live behind, so a finger on their
// row has to mean something — but the same finger, moving, is the one scrolling the room
// list, and the list is what people do far more often. So a press only counts while the
// finger stays put, and any real movement hands it back to the scroll it always was.
//
// A mouse is never judged here. It already has right-click for the menu and a drag for an
// administrator's move, and treating its presses as touches would turn every click on a
// name into a menu.
//
// Pure: the controller feeds in what happened, this says what it was.

/** Long enough that a tap is never mistaken for it, short enough not to feel stuck. */
export const LONG_PRESS_MS = 450;

/** How far a finger may wander and still be pressing rather than scrolling. */
export const PRESS_SLOP_PX = 10;

/**
 * @param {object}  p
 * @param {string}  p.pointerType  'touch' | 'pen' | 'mouse'
 * @param {number}  p.elapsedMs    since the finger went down
 * @param {number}  [p.dx]         movement since then, in CSS pixels
 * @param {number}  [p.dy]
 * @param {boolean} [p.released]   the finger has lifted
 * @param {boolean} [p.cancelled]  the platform took the gesture (pointercancel)
 * @returns {'ignore'|'wait'|'long'|'tap'|'cancel'}
 */
export function pressVerdict({
    pointerType, elapsedMs = 0, dx = 0, dy = 0, released = false, cancelled = false,
} = {}) {
    if (pointerType === 'mouse') return 'ignore';
    if (cancelled) return 'cancel';
    // Measured as a distance, not per axis: a diagonal drift of 8 and 8 is a scroll.
    if (Math.hypot(dx, dy) > PRESS_SLOP_PX) return 'cancel';
    if (elapsedMs >= LONG_PRESS_MS) return 'long';
    return released ? 'tap' : 'wait';
}
