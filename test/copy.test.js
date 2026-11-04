// Per-device wording.
//
// Two promises. The phone never tells anyone to click, press Esc, right-click or use Ctrl —
// it has none of those. And the desktop and browser say exactly what they said before
// copy.js existed: this file was added for the phone, and must not have changed anything
// for anyone else.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { copyFor, copy } from '../src/platform/copy.js';

test('the desktop and the browser keep their wording word for word', () => {
    for (const target of ['desktop', 'browser']) {
        assert.deepEqual(copyFor(target), {
            rememberScope: 'saved to this Windows account only',
            soundBlocked: 'Click anywhere to let sound play.',
            closeHint: 'Esc to close',
            moderateHow: 'right-clicking',
            copyFallback: 'Press Ctrl+C',
        }, target);
    }
    assert.equal(copy, copyFor('browser'), 'an unset target is the browser');
});

test('the phone names nothing it does not have', () => {
    const phone = copyFor('mobile');
    assert.deepEqual(Object.keys(phone).sort(), Object.keys(copyFor('desktop')).sort(),
        'every sentence has a phone version');
    const words = Object.values(phone).filter(Boolean).join(' | ');
    for (const banned of [/windows/i, /\bclick/i, /\besc\b/i, /right-click/i, /ctrl/i, /\bkey\b/i]) {
        assert.doesNotMatch(words, banned);
    }
    assert.equal(phone.closeHint, null, 'no hint at all: Back needs none');
});

test('the sentences live in copy.js and nowhere else', () => {
    const src = fileURLToPath(new URL('../src', import.meta.url));
    const walk = (dir) => readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        return statSync(path).isDirectory() ? walk(path) : [path];
    });
    for (const file of walk(src).filter((f) => f.endsWith('.js') && !f.endsWith('copy.js'))) {
        const text = readFileSync(file, 'utf8');
        for (const sentence of ['saved to this Windows account only', 'Click anywhere to let sound play',
            'Esc to close', "'Press Ctrl+C'"]) {
            assert.ok(!text.includes(sentence), `${file} still spells out "${sentence}"`);
        }
    }
});
