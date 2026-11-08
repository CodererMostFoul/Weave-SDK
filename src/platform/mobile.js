// The mobile platform: the Android app, a Capacitor WebView.
//
// It ships blank, exactly like the desktop app, and for the same reason. The page is served
// from the app itself (http://localhost inside the WebView), never by a Weave server, so
// there is no origin to inherit and nothing to probe: the app must always be told where to go.
//
// A pure factory over the browser platform rather than a third hand-written object, so the
// one rule that matters here can be tested without a phone: A NULL BRIDGE IS A SUPPORTED
// STATE, NOT A STUB. The mobile build is run in desktop Chrome during development, and every
// capability then degrades to the browser's own answer — a token held in memory, no saved
// password, no deep links — rather than throwing. A capability is only claimed when the
// bridge actually supplies it, which is the same rule the desktop adapter follows for
// window.weaveNative.

/** Lines held before the log is written: one bridge call per batch, not one per line. */
export const LOG_BATCH_LINES = 50;
/** How long a quiet line waits for company before it is written anyway. */
export const LOG_BATCH_MS = 2_000;

/**
 * What must never reach a log file: the same rules the desktop main process applies before
 * a log leaves the machine (electron/app.js), applied here before a line is even written,
 * because on a phone the page is the only side that sees these values at all.
 */
export function redact(text) {
    return String(text)
        // The scheme word first. Otherwise "Authorization: Bearer abc" redacts only the word
        // "Bearer", which the rule below takes for the value, and the token itself survives.
        .replace(/\bbearer\s+[^\s"',]+/gi, 'Bearer [redacted]')
        .replace(/\b(token|authorization|bearer|password|secret)["'\s:=]+\S+/gi, '$1=[redacted]');
}

/** Present when the bridge says so; otherwise an honest "not here" with the same calls. */
const absent = {
    back: { available: false, onBack() {}, minimize() {} },
    lifecycle: { available: false, onChange() { return () => {}; } },
    network: {
        available: false,
        async status() { return { available: true, type: 'other' }; },
        onChange() { return () => {}; },
    },
    // Signing straight back in at launch is a phone's habit. Without a native token store
    // there is nothing to sign back in with, so a plain browser never tries.
    session: { resumeOnLaunch: false },
    // Where call audio plays is the browser's business everywhere but the Android app.
    callAudio: { available: false, async set() { return null; } },
};

/**
 * @param {object} base    the browser platform, whose answers stand wherever the bridge is silent
 * @param {object|null} bridge  the native side, or null in a plain browser
 * @param {object} [timers]     injected for tests: { schedule(fn, ms) → handle, cancel(handle), now() → Date }
 */
export function createMobilePlatform(base, bridge = null, timers = {}) {
    const schedule = timers.schedule ?? ((fn, ms) => setTimeout(fn, ms));
    const cancel = timers.cancel ?? ((handle) => clearTimeout(handle));
    const now = timers.now ?? (() => new Date());

    // Claimed, never assumed: a member the bridge does not have keeps the browser's answer.
    const offered = (name, fallback) => (bridge?.[name]
        ? { ...bridge[name], available: true }
        : fallback);

    /* ── the log ─────────────────────────────────────────────────────────── */

    let pending = [];
    let timer = null;

    function flush() {
        if (timer !== null) { cancel(timer); timer = null; }
        if (!pending.length) return;
        const lines = pending;
        pending = [];
        try {
            bridge.writeLog(lines);
        } catch {
            // A bridge on its way out. The lines still reach somebody's console.
            for (const line of lines) base.log('info', line);
        }
    }

    function log(level, message) {
        if (typeof bridge?.writeLog !== 'function') {
            base.log(level, message);
            return;
        }
        pending.push(`${now().toISOString()} [${level}] ${redact(message)}`);
        // An error may be the last thing this page ever says — a renderer about to crash, a
        // process about to be killed — so it does not wait for the batch.
        if (level === 'error' || pending.length >= LOG_BATCH_LINES) flush();
        else if (timer === null) timer = schedule(flush, LOG_BATCH_MS);
    }

    const lifecycle = offered('lifecycle', absent.lifecycle);
    // Sent to the background is when Android may kill the process without another word, so
    // whatever is held is written then rather than lost.
    lifecycle.onChange((active) => { if (!active) flush(); });

    /* ── diagnostics ─────────────────────────────────────────────────────── */

    // The native log is written already redacted, but the native side adds lines of its own
    // and a bug report is the moment it leaves the phone — so it is redacted again on read,
    // as the desktop main process does.
    const diagnostics = bridge?.diagnostics
        ? {
            ...bridge.diagnostics,
            available: true,
            async readAppLog(maxBytes) {
                flush();
                const report = await bridge.diagnostics.readAppLog(maxBytes);
                return report ? { ...report, text: redact(report.text ?? '') } : null;
            },
        }
        : base.diagnostics;

    return {
        ...base,
        target: 'mobile',

        // Always chooses. Nothing about the WebView's own origin says anything about which
        // Weave server this person uses, so probing it — what the browser build does — would
        // only ever find the app's own files.
        canChooseServer: true,
        servedByWeave: false,
        defaultOrigin: () => null,

        tokens: offered('tokens', base.tokens),
        credentials: offered('credentials', base.credentials),
        links: offered('links', base.links),
        diagnostics,

        back: offered('back', absent.back),
        lifecycle,
        network: offered('network', absent.network),
        callAudio: offered('callAudio', absent.callAudio),
        session: bridge?.session ? { ...bridge.session } : absent.session,

        log,
        /** Write whatever is held now. For tests, and for anything about to end the page. */
        flushLog: flush,
    };
}
