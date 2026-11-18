// Voice as the phone around it sees it: the notification, picture-in-picture, the camera
// when the app is hidden, hold-to-ear, a phone call's hold, and a change of network. Each
// is a decision about the room's state, made here without a phone.

import test from 'node:test';
import assert from 'node:assert/strict';

import {
    voiceSessionFor, sameSession, pipAllowed, cameraOnHide, earArmed, holdTransition, networkReaction,
} from '../src/room/voice-session.js';

test('no notification unless you are standing in a room', () => {
    assert.equal(voiceSessionFor({ standing: null, roomName: 'Hall' }), null);
    const room = voiceSessionFor({ standing: 'hall', roomName: 'The Great Hall', since: 5 });
    assert.deepEqual(room, { title: 'The Great Hall', kind: 'room', muted: false, video: false, since: 5, held: false, mic: false });
});

test('a private call is named after the person, not the hidden room it happens in', () => {
    const call = voiceSessionFor({ standing: 'sys-1', roomName: null, callWith: 'Kestrel' });
    assert.equal(call.title, 'Kestrel');
    assert.equal(call.kind, 'call');
    // A call room whose other side is not known yet still says what it is.
    assert.equal(voiceSessionFor({ standing: 'sys-1', roomName: null }).title, 'Private call');
});

test('the notification is redrawn only when something it shows changed', () => {
    const a = voiceSessionFor({ standing: 'hall', roomName: 'Hall', since: 1 });
    assert.ok(sameSession(a, { ...a }));
    assert.ok(!sameSession(a, { ...a, muted: true }));
    assert.ok(!sameSession(a, { ...a, held: true }));
    assert.ok(!sameSession(a, { ...a, mic: true }), 'the microphone going live re-promotes the service');
    assert.ok(!sameSession(a, null));
    assert.ok(sameSession(null, null));
});

test('picture-in-picture only when there is a picture worth keeping', () => {
    assert.ok(pipAllowed({ inVoice: true, webcamOn: true }));
    assert.ok(pipAllowed({ inVoice: true, watching: true }));
    assert.ok(!pipAllowed({ inVoice: true }), 'a voice-only call goes to the background with its notification');
    assert.ok(!pipAllowed({ webcamOn: true }), 'not in voice, nothing to keep');
});

test('a hidden app pauses its camera; picture-in-picture keeps it', () => {
    assert.equal(cameraOnHide({ pip: false }), 'pause');
    assert.equal(cameraOnHide({ pip: true }), 'keep');
});

test('hold-to-ear listens only when a lift to the ear is what could be happening', () => {
    const ok = { inVoice: true, visible: true };
    assert.ok(earArmed(ok));
    assert.ok(!earArmed({ ...ok, visible: false }), 'a hidden app is in a pocket or another app');
    assert.ok(!earArmed({ ...ok, webcamOn: true }), 'you are looking at the screen');
    assert.ok(!earArmed({ ...ok, watching: true }), 'you are watching a stream');
    assert.ok(!earArmed({ ...ok, pip: true }));
    assert.ok(!earArmed({ visible: true }), 'not in voice');
});

test('a phone call holds the call silent both ways, and gives back exactly what was there', () => {
    const start = { held: false, before: null };
    const held = holdTransition(start, 'hold', { muted: false, deafened: false });
    assert.deepEqual(held.apply, { muted: true, deafened: true });
    assert.equal(held.hold.held, true);

    const back = holdTransition(held.hold, 'resume', { muted: true, deafened: true });
    assert.deepEqual(back.apply, { muted: false, deafened: false });
    assert.equal(back.hold.held, false);

    // Muted before the phone rang: still muted after it.
    const wasMuted = holdTransition(start, 'hold', { muted: true, deafened: false });
    assert.deepEqual(holdTransition(wasMuted.hold, 'resume', {}).apply, { muted: true, deafened: false });
});

test('holds do not stack, and a resume with nothing held does nothing', () => {
    const held = holdTransition({ held: false, before: null }, 'hold', { muted: false, deafened: false }).hold;
    const again = holdTransition(held, 'hold', { muted: true, deafened: true });
    assert.equal(again.apply, null);
    assert.deepEqual(again.hold.before, { muted: false, deafened: false }, 'the first hold keeps what was really there');
    assert.equal(holdTransition({ held: false, before: null }, 'resume', {}).apply, null);
});

test('a new network is checked at once; going offline or a mere signal change asks nothing', () => {
    const wifi = { available: true, type: 'wifi' };
    const cell = { available: true, type: 'cellular' };
    const none = { available: false, type: 'none' };
    assert.deepEqual(networkReaction(wifi, cell), { probe: true, repair: true });
    assert.deepEqual(networkReaction(none, wifi), { probe: true, repair: true });
    assert.deepEqual(networkReaction(null, wifi), { probe: true, repair: true });
    assert.deepEqual(networkReaction(wifi, none), { probe: false, repair: false });
    assert.deepEqual(networkReaction(wifi, wifi), { probe: false, repair: false });
});
