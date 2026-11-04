// weave:// parsing: browser-supplied input, allow-list rules, null for everything else.

import test from 'node:test';
import assert from 'node:assert/strict';

import { parseDeepLink, openRegister } from '../src/server/deeplink.js';

test('the invite link the server mints parses to its parts', () => {
    const got = parseDeepLink('weave://join?server=https%3A%2F%2Fweave-chat-dev.codeine.cloud&code=ab12-cd34');
    assert.deepEqual(got, { verb: 'join', server: 'https://weave-chat-dev.codeine.cloud', code: 'AB12-CD34' });
});

test('a bare host works too, and the code is normalised upward', () => {
    const got = parseDeepLink('weave://join?server=weave.example.com&code=abcd');
    assert.equal(got.server, 'weave.example.com');
    assert.equal(got.code, 'ABCD');
});

test('everything not exactly a join link is null, never a guess', () => {
    for (const bad of [
        null,
        '',
        'not a url',
        'https://join?server=x&code=abcd',            // wrong protocol
        'weave://open?server=x&code=abcd',            // wrong verb
        'weave://join?code=abcd',                     // no server
        'weave://join?server=x',                      // no code
        'weave://join?server=x&code=ab cd',           // whitespace in code
        'weave://join?server=x&code=<script>',        // markup in code
        'weave://join?server=ho<st&code=abcd',        // markup in server
        `weave://join?server=${'x'.repeat(300)}&code=abcd`,
    ]) {
        assert.equal(parseDeepLink(bad), null, JSON.stringify(bad).slice(0, 60));
    }
});

test('an invite opens Register with one repaint, so its code survives to the field', () => {
    // From anywhere else, the hash change is the repaint: asking for another rendered the
    // form twice, and the second render came up without the code the first had consumed.
    const elsewhere = { hash: '#/signin' };
    let repaints = 0;
    openRegister(elsewhere, () => { repaints += 1; });
    assert.equal(elsewhere.hash, '#/register');
    assert.equal(repaints, 0);

    // Already there: setting the same hash fires nothing, so the repaint is asked for.
    const there = { hash: '#/register' };
    openRegister(there, () => { repaints += 1; });
    assert.equal(there.hash, '#/register');
    assert.equal(repaints, 1);
});
