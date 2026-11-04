// Signing back in when the phone app opens.
//
// The one distinction everything rests on: a server that SAYS the session is over (401,
// 403) is believed and the token goes; a server that cannot be reached says nothing about
// the session, so the token stays for the next launch. Getting that backwards either signs
// people out every time they open the app on a train, or keeps retrying a revoked token.

import test from 'node:test';
import assert from 'node:assert/strict';

import { resumeOutcome, RESUME_TIMEOUT_MS } from '../src/auth/resume.js';
import { createMobilePlatform } from '../src/platform/mobile.js';
import { platform } from '../src/platform/index.js';

test('a token the server recognises goes straight into the room', () => {
    const user = { id: 1, username: 'ada' };
    assert.deepEqual(resumeOutcome({ user }), { kind: 'enter', user, clearToken: false, notice: null });
});

test('a session the server has ended is dropped, and the sign-in screen says so', () => {
    const expired = resumeOutcome({ status: 401, message: 'Authentication required.' });
    assert.equal(expired.kind, 'signin');
    assert.equal(expired.clearToken, true);
    assert.equal(expired.notice, 'Your session has ended. Sign in again.',
        "a 401's own message is written for developers");

    const banned = resumeOutcome({ status: 403, message: 'This account is banned.' });
    assert.equal(banned.clearToken, true);
    assert.equal(banned.notice, 'This account is banned.', "a 403 is the server's to explain");
    assert.ok(resumeOutcome({ status: 403 }).notice, 'and still says something without one');
});

test('an unreachable or failing server keeps the token for next time', () => {
    for (const status of [0, 500, 502, 503, null]) {
        const outcome = resumeOutcome({ status, message: 'Lost contact with the server.' });
        assert.equal(outcome.kind, 'signin', `status ${status}`);
        assert.equal(outcome.clearToken, false, `status ${status} says nothing about the session`);
        assert.ok(outcome.notice, 'and the person is told why they are looking at a sign-in form');
    }
});

test('an answer without a user is not a sign-in', () => {
    assert.equal(resumeOutcome({ user: null, status: null }).kind, 'signin');
    assert.equal(resumeOutcome({ user: 'ada' }).kind, 'signin');
});

test('the wait is bounded well inside the request default', () => {
    assert.ok(RESUME_TIMEOUT_MS > 0 && RESUME_TIMEOUT_MS <= 10_000);
});

test('only a platform with a native token store resumes at all', () => {
    // The browser and desktop builds keep their behaviour: no session property, no resume.
    assert.ok(!platform.session?.resumeOnLaunch, 'the browser build never resumes');
    const base = { log() {}, tokens: {}, credentials: {}, links: {}, diagnostics: {} };
    assert.equal(createMobilePlatform(base, null).session.resumeOnLaunch, false,
        'the mobile build in a desktop browser has nothing to resume with');
});
