// What Android's Back button means here.
//
// The innermost thing that is open closes, one press at a time: fullscreen, then a dialog,
// then anything Escape already closes (a menu, the drawer, the lightbox, a spotlight). With
// nothing left open the app goes to the background — and it NEVER exits. Leaving the app is
// leaving the call, and a Back press is far too easy to make by accident for that.
//
// Escape is reused rather than re-listed. Every popover in the room already closes on it,
// each in its own handler, so Back sends a synthetic Escape instead of keeping a second list
// of "things that can be open" that would drift from the first. Whether that Escape closed
// anything is read off the DOM itself: the handlers run synchronously inside dispatchEvent,
// so a MutationObserver's queue holds exactly what they changed, and nothing else.

/** The steps, as data, so the order can be tested without a page. */
export function backAction({ fullscreen = false, dialog = false } = {}) {
    if (fullscreen) return 'exit-fullscreen';
    if (dialog) return 'close-dialog';
    return 'escape';
}

/** After the Escape: if nothing on the page changed, nothing was open to close. */
export const afterEscape = (changed) => (changed ? 'handled' : 'minimize');

/** The dialog on top: the last open one in document order. */
function topDialog(doc) {
    const open = doc.querySelectorAll('dialog[open]');
    return open.length ? open[open.length - 1] : null;
}

/**
 * Carry out one press of Back on a real page.
 * @returns {'exit-fullscreen'|'close-dialog'|'handled'|'minimize'} what it did
 */
export function runBack({ doc = document, minimize }) {
    const dialog = topDialog(doc);
    const step = backAction({ fullscreen: Boolean(doc.fullscreenElement), dialog: Boolean(dialog) });

    if (step === 'exit-fullscreen') {
        doc.exitFullscreen?.()?.catch?.(() => {});
        return step;
    }

    if (step === 'close-dialog') {
        // What Escape does to a dialog natively: a cancelable 'cancel', then close unless a
        // handler refused. Settings uses the refusal to step back from a section to the list.
        const cancel = new Event('cancel', { cancelable: true });
        if (dialog.dispatchEvent(cancel)) dialog.close();
        return step;
    }

    const watcher = new MutationObserver(() => {});
    watcher.observe(doc.body, { subtree: true, childList: true, attributes: true });
    // From whatever has focus, so a panel that listens on itself (the DM picker) hears it
    // on the way up to the window.
    const target = doc.activeElement && doc.activeElement !== doc.body ? doc.activeElement : doc.body;
    target.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Escape', code: 'Escape', bubbles: true, cancelable: true,
    }));
    const changed = watcher.takeRecords().length > 0;
    watcher.disconnect();

    const next = afterEscape(changed);
    if (next === 'minimize') minimize();
    return next;
}
