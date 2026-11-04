// The Android app's platform adapter.
//
// Two promises are tested here. The app always chooses its server, because the page it runs
// is served by the app itself and says nothing about which Weave server anyone uses. And a
// missing native bridge degrades to the browser's answers instead of breaking: the mobile
// build has to run in desktop Chrome while it is being developed, and a capability that
// throws when absent turns "no shell here" into an error somebody has to look at.

import test from 'node:test';
import assert from 'node:assert/strict';

import { createMobilePlatform, redact, LOG_BATCH_LINES } from '../src/platform/mobile.js';

/** A stand-in for the browser platform, with every answer recognisable by identity. */
function fakeBase() {
    const logged = [];
    return {
        logged,
        target: 'browser',
        version: '9.9.9',
        canChooseServer: false,
        servedByWeave: null,
        defaultOrigin: () => 'http://localhost',
        tokens: { available: false, get: async () => null },
        credentials: { available: false, get: async () => null },
        links: { available: false, onDeepLink() {} },
        diagnostics: { available: false, read: async () => null },
        updates: { available: false },
        hotkeys: { available: false },
        power: { available: false, idleSeconds: null },
        isOnline: () => true,
        log(level, message) { logged.push([level, message]); },
    };
}

test('with no bridge the app still ships blank and asks for a server', () => {
    const base = fakeBase();
    const mobile = createMobilePlatform(base, null);

    assert.equal(mobile.target, 'mobile');
    assert.equal(mobile.canChooseServer, true, 'there is nothing to inherit, so it must ask');
    assert.equal(mobile.servedByWeave, false);
    // The WebView's own origin is the app's files, never a server — probing it would be
    // the browser build's dead end all over again.
    assert.equal(mobile.defaultOrigin(), null);
    assert.equal(mobile.version, '9.9.9', 'the rest of the identity is inherited');
});

test('with no bridge every native capability is the browser answer, not a stub', () => {
    const base = fakeBase();
    const mobile = createMobilePlatform(base);

    for (const name of ['tokens', 'credentials', 'links', 'diagnostics', 'updates', 'hotkeys', 'power']) {
        assert.equal(mobile[name], base[name], `${name} falls back to the browser's`);
        assert.equal(mobile[name].available, false, `${name} is not claimed`);
    }
    mobile.log('warn', 'hello');
    assert.deepEqual(base.logged, [['warn', 'hello']], 'the console path still works');
});

test('a capability is claimed only when the bridge supplies it', async () => {
    const base = fakeBase();
    const held = new Map();
    const bridge = {
        tokens: {
            get: async (id) => held.get(id) ?? null,
            set: async (id, value) => { held.set(id, value); return true; },
        },
    };
    const mobile = createMobilePlatform(base, bridge);

    assert.equal(mobile.tokens.available, true);
    await mobile.tokens.set('s1', 'abc');
    assert.equal(await mobile.tokens.get('s1'), 'abc', 'calls reach the native store');
    // Present on the bridge or absent from it — nothing in between.
    assert.equal(mobile.credentials, base.credentials);
    assert.equal(mobile.links.available, false);
});

/** Timers a test can run by hand: the log batches on a timer, and a test must not wait. */
function manualTimers() {
    const queued = [];
    return {
        queued,
        schedule(fn) { queued.push(fn); return queued.length; },
        cancel(handle) { queued[handle - 1] = null; },
        now: () => new Date('2026-10-25T12:00:00.000Z'),
        run() { for (const fn of queued.splice(0)) fn?.(); },
    };
}

test('page lines reach the native log in batches, not one bridge call each', () => {
    const base = fakeBase();
    const timers = manualTimers();
    const writes = [];
    const mobile = createMobilePlatform(base, { writeLog: (lines) => writes.push(lines) }, timers);

    mobile.log('info', 'joined a room');
    mobile.log('warn', 42);
    assert.equal(writes.length, 0, 'held until the batch is due');
    assert.equal(timers.queued.length, 1, 'one timer for the batch, not one per line');

    timers.run();
    assert.deepEqual(writes, [[
        '2026-10-25T12:00:00.000Z [info] joined a room',
        '2026-10-25T12:00:00.000Z [warn] 42',
    ]]);
    assert.equal(base.logged.length, 0, 'the console is the fallback, not a copy');
});

test('an error is written at once: it may be the last thing the page says', () => {
    const timers = manualTimers();
    const writes = [];
    const mobile = createMobilePlatform(fakeBase(), { writeLog: (lines) => writes.push(lines) }, timers);

    mobile.log('info', 'before');
    mobile.log('error', 'renderer about to go');
    assert.equal(writes.length, 1);
    assert.equal(writes[0].length, 2, 'and takes whatever was waiting with it');
    timers.run();
    assert.equal(writes.length, 1, 'the cancelled timer writes nothing more');
});

test('a full batch is written without waiting for the timer', () => {
    const writes = [];
    const mobile = createMobilePlatform(fakeBase(), { writeLog: (lines) => writes.push(lines) }, manualTimers());
    for (let i = 0; i < LOG_BATCH_LINES; i++) mobile.log('info', `line ${i}`);
    assert.equal(writes.length, 1);
    assert.equal(writes[0].length, LOG_BATCH_LINES);
});

test('secrets never reach the log file', () => {
    const writes = [];
    const mobile = createMobilePlatform(fakeBase(), { writeLog: (lines) => writes.push(...lines) }, manualTimers());
    mobile.log('error', 'retrying with token=abc.def.ghi');
    mobile.log('error', 'Authorization: Bearer abc123');
    mobile.log('error', '{"password":"hunter22"}');
    const text = writes.join('\n');
    for (const secret of ['abc.def.ghi', 'abc123', 'hunter22']) {
        assert.ok(!text.includes(secret), `${secret} was written`);
    }
    assert.match(text, /token=\[redacted\]/);
    assert.equal(redact('nothing to hide here'), 'nothing to hide here');
});

test('going to the background writes what is held, since Android may kill the app next', () => {
    let onChange = null;
    const writes = [];
    const mobile = createMobilePlatform(fakeBase(), {
        writeLog: (lines) => writes.push(lines),
        lifecycle: { onChange(cb) { onChange = cb; return () => {}; } },
    }, manualTimers());

    mobile.log('info', 'in a call');
    onChange(true);
    assert.equal(writes.length, 0, 'coming back to the front changes nothing');
    onChange(false);
    assert.equal(writes.length, 1);
    assert.equal(mobile.lifecycle.available, true);
});

test('a bridge that throws on write sends the lines to the console instead', () => {
    const base = fakeBase();
    const mobile = createMobilePlatform(base, { writeLog() { throw new Error('bridge gone'); } }, manualTimers());
    mobile.log('error', 'still heard');
    assert.deepEqual(base.logged, [['info', '2026-10-25T12:00:00.000Z [error] still heard']]);
});

test('with no bridge, the phone-only capabilities exist and say they are absent', async () => {
    const mobile = createMobilePlatform(fakeBase(), null);
    for (const name of ['back', 'lifecycle', 'network']) {
        assert.equal(mobile[name].available, false, `${name} is not claimed`);
    }
    // Callable regardless, so nothing that uses them has to ask first.
    mobile.back.onBack(() => {});
    assert.equal(typeof mobile.lifecycle.onChange(() => {}), 'function');
    assert.equal(typeof mobile.network.onChange(() => {}), 'function');
    assert.deepEqual(await mobile.network.status(), { available: true, type: 'other' });
    // Signing back in at launch needs a token that survived the launch: a browser has none.
    assert.equal(mobile.session.resumeOnLaunch, false);
});

test('signing back in at launch is the bridge\'s to offer', () => {
    const mobile = createMobilePlatform(fakeBase(), { session: { resumeOnLaunch: true } });
    assert.equal(mobile.session.resumeOnLaunch, true);
});

test('a bug report\'s copy of the log is redacted again on its way out', async () => {
    const writes = [];
    const mobile = createMobilePlatform(fakeBase(), {
        writeLog: (lines) => writes.push(lines),
        diagnostics: {
            read: async () => null,
            readAppLog: async () => ({ path: '/data/app.log', text: '[native] secret: s3cr3t', truncated: false }),
            openFolder: async () => {},
        },
    }, manualTimers());

    mobile.log('info', 'held');
    const report = await mobile.diagnostics.readAppLog(4096);
    assert.equal(mobile.diagnostics.available, true);
    assert.ok(!report.text.includes('s3cr3t'));
    assert.equal(writes.length, 1, 'what was held is written first, so the report includes it');
});

test('the mobile build selects the mobile adapter, and only the mobile build', async () => {
    globalThis.__WEAVE_TARGET__ = 'mobile';
    try {
        const mod = await import('../src/platform/index.js?target=mobile');
        assert.equal(mod.TARGET, 'mobile');
        assert.equal(mod.isMobile, true);
        assert.equal(mod.isDesktop, false);
        // The reason isBrowser is named rather than derived: "not desktop" would call the
        // phone app a browser, and the browser is the build that inherits its server.
        assert.equal(mod.isBrowser, false);
        assert.equal(mod.platform.target, 'mobile');
        assert.equal(mod.platform.canChooseServer, true);
        assert.equal(mod.platform.defaultOrigin(), null);
    } finally {
        delete globalThis.__WEAVE_TARGET__;
    }

    const browser = await import('../src/platform/index.js?target=browser');
    assert.equal(browser.isBrowser, true, 'an unset target is still the browser build');
    assert.equal(browser.isMobile, false);
    assert.equal(browser.platform.target, 'browser');
});
