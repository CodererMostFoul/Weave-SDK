// Android's Back button.
//
// The rule that matters most is the one with no branch for it: Back never exits, because
// exiting is leaving the call. Then the order — the innermost open thing closes first — and
// that a dialog which refuses its cancel (settings stepping back to its list) stays open.
//
// The Escape step needs a real page (MutationObserver, KeyboardEvent), so it is exercised in
// the browser rather than here; everything around it is plain data.

import test from 'node:test';
import assert from 'node:assert/strict';

import { backAction, afterEscape, runBack } from '../src/ui/back.js';

test('the innermost open thing closes first', () => {
    assert.equal(backAction({ fullscreen: true, dialog: true }), 'exit-fullscreen');
    assert.equal(backAction({ fullscreen: true, dialog: false }), 'exit-fullscreen');
    assert.equal(backAction({ fullscreen: false, dialog: true }), 'close-dialog');
    assert.equal(backAction({ fullscreen: false, dialog: false }), 'escape');
    assert.equal(backAction(), 'escape');
});

test('Back never exits: with nothing open the app goes to the background', () => {
    const everything = [];
    for (const fullscreen of [true, false]) {
        for (const dialog of [true, false]) everything.push(backAction({ fullscreen, dialog }));
    }
    everything.push(afterEscape(true), afterEscape(false));
    assert.ok(!everything.some((step) => /exit-app|quit|close-app/.test(step)));
    assert.equal(afterEscape(false), 'minimize');
    assert.equal(afterEscape(true), 'handled', 'an Escape that closed something is the whole press');
});

/** Just enough of a document for the fullscreen and dialog steps. */
function fakeDoc({ fullscreen = false, dialogs = [] } = {}) {
    const doc = {
        exited: 0,
        fullscreenElement: fullscreen ? {} : null,
        exitFullscreen() { doc.exited += 1; doc.fullscreenElement = null; return Promise.resolve(); },
        querySelectorAll: (selector) => (selector === 'dialog[open]' ? dialogs.filter((d) => d.open) : []),
    };
    return doc;
}

function fakeDialog({ refuse = false } = {}) {
    const dialog = new EventTarget();
    dialog.open = true;
    dialog.close = () => { dialog.open = false; };
    if (refuse) dialog.addEventListener('cancel', (event) => event.preventDefault());
    return dialog;
}

test('fullscreen is left before anything else is touched', () => {
    const dialog = fakeDialog();
    const doc = fakeDoc({ fullscreen: true, dialogs: [dialog] });
    let minimized = 0;
    assert.equal(runBack({ doc, minimize: () => { minimized += 1; } }), 'exit-fullscreen');
    assert.equal(doc.exited, 1);
    assert.equal(dialog.open, true, 'the dialog waits for the next press');
    assert.equal(minimized, 0);
});

test('the dialog on top is cancelled the way Escape would, and only that one', () => {
    const below = fakeDialog();
    const top = fakeDialog();
    const doc = fakeDoc({ dialogs: [below, top] });
    assert.equal(runBack({ doc, minimize: () => assert.fail('must not minimise') }), 'close-dialog');
    assert.equal(top.open, false);
    assert.equal(below.open, true);
});

test('a dialog that refuses its cancel stays open', () => {
    // Settings does this to step back from a section to the list of sections.
    const dialog = fakeDialog({ refuse: true });
    runBack({ doc: fakeDoc({ dialogs: [dialog] }), minimize: () => assert.fail('must not minimise') });
    assert.equal(dialog.open, true);
});
