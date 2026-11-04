// Words that depend on what the app is running on.
//
// Only the handful of sentences that name a way of operating it — a mouse, a key, an
// operating system — and so are wrong somewhere. "Click anywhere" on a phone, or "Esc to
// close" on one with no keyboard, reads as an app that was not made for it. Everything else
// is the same everywhere and stays where it is written.
//
// The desktop and browser wording is exactly what it was before this file existed; the test
// holds it there, so this file can only ever change what the phone says.

import { TARGET } from './index.js';

const pointer = {
    rememberScope: 'saved to this Windows account only',
    soundBlocked: 'Click anywhere to let sound play.',
    closeHint: 'Esc to close',
    moderateHow: 'right-clicking',
    copyFallback: 'Press Ctrl+C',
};

const phone = {
    rememberScope: 'saved on this phone only',
    soundBlocked: 'Tap anywhere to let sound play.',
    // A phone's way out is its Back button, which needs no hint.
    closeHint: null,
    moderateHow: 'long-pressing',
    copyFallback: 'Long-press to copy',
};

/** @param {'browser'|'desktop'|'mobile'} target */
export function copyFor(target) {
    return target === 'mobile' ? phone : pointer;
}

export const copy = copyFor(TARGET);
