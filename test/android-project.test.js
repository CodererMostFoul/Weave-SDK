// The Android project, held to what the rest of the app assumes about it.
//
// Most of what can go wrong here is silent until a phone is involved: a versionCode that
// went backwards is an update Android refuses, a missing intent filter is an invite link
// that opens a browser instead, a scheme other than http is a page whose origin changed and
// whose every saved setting disappeared with it. None of that needs a phone to check.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { versionCodeFor } from '../scripts/android-version.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const read = (path) => readFileSync(join(ROOT, path), 'utf8');
const pkg = JSON.parse(read('package.json'));
const gradle = read('android/app/build.gradle');
const manifest = read('android/app/src/main/AndroidManifest.xml');
const capacitor = JSON.parse(read('capacitor.config.json'));

test('versionCode grows exactly when the version does', () => {
    assert.equal(versionCodeFor('0.1.71'), 1071);
    assert.equal(versionCodeFor('1.2.3'), 1_002_003);
    assert.equal(versionCodeFor('0.1.71-beta.1'), 1071, 'a pre-release shares its release\'s code');
    assert.ok(versionCodeFor('0.2.0') > versionCodeFor('0.1.999'));
    assert.ok(versionCodeFor('1.0.0') > versionCodeFor('0.999.999'));
    assert.throws(() => versionCodeFor('0.1.1000'), /under 1000/);
    assert.throws(() => versionCodeFor('0.1000.0'), /under 1000/);
    assert.throws(() => versionCodeFor('one.two'), /MAJOR\.MINOR\.PATCH/);
    assert.doesNotThrow(() => versionCodeFor(pkg.version), 'the current version must be expressible');
});

test('Gradle derives both numbers from package.json with the same formula', () => {
    assert.match(gradle, /new JsonSlurper\(\)\.parse\(file\('\.\.\/\.\.\/package\.json'\)\)\.version/);
    assert.match(gradle, /parts\[0\] \* 1_000_000 \+ parts\[1\] \* 1_000 \+ parts\[2\]/);
    assert.match(gradle, /parts\[1\] >= 1000 \|\| parts\[2\] >= 1000/, 'and refuses what would wrap');
    assert.match(gradle, /versionCode weaveVersionCode/);
    assert.match(gradle, /versionName weaveVersion/);
});

test('one application id everywhere, with debug builds installed beside it', () => {
    assert.equal(capacitor.appId, 'io.github.weavespeak.client');
    assert.match(gradle, /applicationId "io\.github\.weavespeak\.client"/);
    assert.match(gradle, /namespace = "io\.github\.weavespeak\.client"/);
    assert.match(gradle, /debug \{[\s\S]*?applicationIdSuffix '\.dev'/);
});

test('release signing comes from outside the repository, or not at all', () => {
    assert.match(gradle, /System\.getProperty\('user\.home'\), '\.weave\/android-signing\.properties'/);
    assert.doesNotMatch(gradle, /storePassword\s+['"]/, 'no password literal in the build');
    for (const rule of ['*.jks', '*.keystore', 'android-signing.properties']) {
        assert.ok(read('.gitignore').split('\n').includes(rule), `.gitignore must list ${rule}`);
    }
});

test('the page is served as http://localhost, the secure context the microphone needs', () => {
    // Changing the scheme changes the origin — and with it localStorage, where every server
    // and preference lives. It is not a setting to change casually.
    assert.equal(capacitor.server.androidScheme, 'http');
    assert.equal(capacitor.webDir, 'dist-mobile');
    assert.match(read('vite.config.js'), /mobile: 'dist-mobile'/, 'and the mobile build writes there');
    assert.equal(capacitor.android.appendUserAgent, 'WeaveAndroid');
});

test('Capacitor never logs plugin calls, because those calls carry tokens and passwords', () => {
    // Its default ("debug") writes every call's arguments to logcat in a debug build —
    // secretSet included, so a session token or a saved password in plain text. Found on the
    // phone, not in review. JSON has no comments, so the reason lives here.
    assert.equal(capacitor.loggingBehavior, 'none');
});

test('Android 10 is the floor', () => {
    assert.match(read('android/variables.gradle'), /minSdkVersion = 29\b/);
});

test('the manifest asks for what voice needs and handles invite links', () => {
    for (const permission of ['INTERNET', 'ACCESS_NETWORK_STATE', 'RECORD_AUDIO', 'MODIFY_AUDIO_SETTINGS', 'CAMERA']) {
        assert.match(manifest, new RegExp(`android\\.permission\\.${permission}"`), permission);
    }
    assert.match(manifest, /<data android:scheme="weave" android:host="join" \/>/);
    assert.match(manifest, /android\.intent\.category\.BROWSABLE/);
    assert.match(manifest, /android:allowBackup="false"/);
    assert.match(manifest, /android:networkSecurityConfig="@xml\/network_security_config"/);
    assert.match(manifest, /android:launchMode="singleTask"/, 'a link reuses the running app and its call');

    // Each missing entry is a rotation, a theme switch or a font change that rebuilds the
    // activity, and the WebView with it, in the middle of a call.
    const changes = /android:configChanges="([^"]+)"/.exec(manifest)[1].split('|');
    for (const change of ['orientation', 'screenSize', 'density', 'uiMode', 'fontScale', 'keyboardHidden']) {
        assert.ok(changes.includes(change), `configChanges must include ${change}`);
    }
});

test('plain http is allowed for LAN servers, with only the system\'s certificate authorities', () => {
    const config = read('android/app/src/main/res/xml/network_security_config.xml');
    assert.match(config, /cleartextTrafficPermitted="true"/);
    assert.match(config, /<certificates src="system" \/>/);
    assert.doesNotMatch(config, /src="user"/, 'a user-installed CA must not be able to read https traffic');
});

test('the native plugin is registered under the name the page calls', () => {
    const dir = 'android/app/src/main/java/io/github/weavespeak/client';
    assert.match(read(`${dir}/WeaveNativePlugin.kt`), /@CapacitorPlugin\(name = "WeaveNative"\)/);
    assert.match(read(`${dir}/MainActivity.kt`), /registerPlugin\(WeaveNativePlugin::class\.java\)/);
    assert.match(read('src/platform/native-capacitor.js'), /registerPlugin\('WeaveNative'\)/);
    // The stores the page may name, and no others.
    assert.match(read(`${dir}/SecretStore.kt`), /val STORES = setOf\("tokens", "credentials"\)/);
});

test('@capacitor/* is imported in exactly one file, and only the mobile build reaches it', () => {
    const walk = (dir) => readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        return statSync(path).isDirectory() ? walk(path) : [path];
    });
    const importers = walk(join(ROOT, 'src'))
        .filter((f) => f.endsWith('.js'))
        .filter((f) => /from '@capacitor\//.test(readFileSync(f, 'utf8')))
        .map((f) => f.slice(ROOT.length).replaceAll('\\', '/'));
    assert.deepEqual(importers, ['src/platform/native-capacitor.js']);

    assert.equal(pkg.imports['#native'], './src/platform/native-none.js',
        'every other build, and every test, gets the null bridge');
    assert.match(read('vite.config.js'),
        /target === 'mobile'\s*\?\s*\{ alias: \{ '#native': fileURLToPath\(new URL\('\.\/src\/platform\/native-capacitor\.js'/);
    for (const name of ['@capacitor/core', '@capacitor/app', '@capacitor/android', '@capacitor/cli']) {
        assert.ok(!pkg.dependencies?.[name], `${name} must not be packaged into the desktop app`);
        assert.ok(pkg.devDependencies?.[name], `${name} is a build tool here`);
    }
});

test('the page draws edge to edge and resizes for the keyboard', () => {
    const viewport = /<meta name="viewport" content="([^"]+)"/.exec(read('index.html'))[1];
    assert.match(viewport, /viewport-fit=cover/);
    assert.match(viewport, /interactive-widget=resizes-content/);
});

test('Gradle\'s Windows launcher keeps its CRLF endings, and binaries stay binary', () => {
    const attributes = read('.gitattributes').split('\n');
    assert.ok(attributes.includes('*.bat text eol=crlf'));
    assert.ok(attributes.includes('*.jar binary'));
});

test('the launcher icon is drawn in the design system\'s colours, with the brand mark\'s strand', () => {
    const res = 'android/app/src/main/res';
    const colors = read(`${res}/values/weave_colors.xml`);
    const tokens = read('src/styles/tokens.css');
    const token = (name) => new RegExp(`--${name}:\s*([^;]+);`).exec(tokens)[1].trim().toLowerCase();
    const color = (name) => new RegExp(`<color name="${name}">(#[0-9A-Fa-f]{6})</color>`).exec(colors)[1].toLowerCase();

    assert.equal(color('weave_accent_hi'), token('accent-hi'));
    assert.equal(color('weave_accent_deep'), token('accent-deep'));
    const voidRgb = token('bg-void-rgb').split(/\s+/).map(Number);
    assert.equal(color('weave_void'), `#${voidRgb.map((v) => v.toString(16).padStart(2, '0')).join('')}`);

    // The strand is the header's brand mark, path for path (src/main.js draws it in SVG).
    const svgPath = /<path d="(M3 14c[^"]+)"/.exec(read('src/main.js'))[1];
    const vectorPath = /android:pathData="([^"]+)"/.exec(read(`${res}/drawable/ic_launcher_foreground.xml`))[1];
    const numbers = (p) => p.match(/-?\d+(?:\.\d+)?/g).map(Number);
    assert.deepEqual(numbers(vectorPath), numbers(svgPath));

    const icon = read(`${res}/mipmap-anydpi-v26/ic_launcher.xml`);
    assert.match(icon, /<monochrome /, 'themed icons on Android 13 need a monochrome layer');
    assert.doesNotMatch(read(`${res}/values/styles.xml`), /@drawable\/splash/, 'no template splash image');
});

test('a chosen file reaches the page as the app\'s own copy, not a link into another app', () => {
    // On newer Android the WebView re-checks a content:// link every time the file is read,
    // and gallery and photo-picker providers refuse the second look: uploads failed with
    // "Failed to fetch", and reading up front with "could not be read". Copied into the app's
    // cache when chosen, the picture no longer depends on the app it came from.
    const chrome = read('android/app/src/main/java/io/github/weavespeak/client/WeaveChromeClient.kt');
    assert.match(chrome, /override fun onShowFileChooser\(/, 'the chooser is ours, not Capacitor\'s');
    assert.match(chrome, /File\(context\.cacheDir, "picked"\)/, 'copies go to the app\'s private cache');
    assert.match(chrome, /Uri\.fromFile\(out\)/, 'and the page is handed the copy');
    assert.match(chrome, /deleteRecursively\(\)/, 'old copies are cleared away');
    assert.match(chrome, /isCaptureEnabled\) \{\s*return super\.onShowFileChooser/,
        'taking a photo with the camera stays on Capacitor\'s path');
});

test('call audio goes to the loudspeaker, unless something is worn, and only while in voice', () => {
    // In call mode Android plays through the earpiece, which made a room nearly inaudible.
    const dir = 'android/app/src/main/java/io/github/weavespeak/client';
    const route = read(`${dir}/AudioRoute.kt`);
    const preference = /PREFERENCE[^=]*= buildList \{([\s\S]*?)\n {8}\}/.exec(route)[1];
    const order = [...preference.matchAll(/TYPE_(\w+)/g)].map((m) => m[1]);
    assert.equal(order.at(-1), 'BUILTIN_SPEAKER', 'the loudspeaker is the default, after anything worn');
    assert.ok(!order.includes('BUILTIN_EARPIECE'), 'and the earpiece is never chosen');
    assert.match(route, /if \(audio\.mode != AudioManager\.MODE_IN_COMMUNICATION\)/, 'only during a call');
    assert.match(route, /registerAudioDeviceCallback/, 'and again when a headset comes or goes');
    assert.match(route, /audio\.isSpeakerphoneOn = false/, 'a speakerphone is not left on after the call');

    const plugin = read(`${dir}/WeaveNativePlugin.kt`);
    assert.match(plugin, /fun callAudio\(call: PluginCall\)/);
    assert.match(plugin, /route\.disengage\(\)/, 'and the plugin lets go when it is destroyed');
    assert.match(read('android/app/src/main/AndroidManifest.xml'), /android\.permission\.MODIFY_AUDIO_SETTINGS/);

    // The room says when it is in voice; standing nowhere and leaving let the route go.
    const room = read('src/room/index.js');
    assert.match(room, /platform\.callAudio\?\.set\(Boolean\(msg\.channel\), prefs\.callOutput \?\? ''\)/);
    assert.ok((room.match(/platform\.callAudio\?\.set\(false\)/g) ?? []).length >= 2, 'on leaving and on teardown');
});

test('a phone lists its own outputs and plays through the one chosen', () => {
    // The WebView lists no outputs to enumerateDevices() and has no setSinkId(), so the
    // Settings picker was empty on every phone. The list and the choice are native.
    const dir = 'android/app/src/main/java/io/github/weavespeak/client';
    const route = read(`${dir}/AudioRoute.kt`);
    const plugin = read(`${dir}/WeaveNativePlugin.kt`);

    assert.match(plugin, /fun audioOutputs\(call: PluginCall\)/);
    assert.match(plugin, /fun chooseAudioOutput\(call: PluginCall\)/);
    assert.match(plugin, /if \(prefer != null\) route\.prefer\(prefer\)/, 'a room starts on the choice');

    // Every kind somebody might pick, the earpiece included, but that only by choice.
    for (const type of ['BUILTIN_SPEAKER', 'BUILTIN_EARPIECE', 'WIRED_HEADSET', 'USB_HEADSET', 'BLUETOOTH_SCO', 'BLE_HEADSET', 'HEARING_AID']) {
        assert.match(route, new RegExp(`AudioDeviceInfo\\.TYPE_${type}\\b`), `${type} is listed`);
    }
    // The choice first when it is connected, else what automatic picks, as with a microphone.
    assert.match(route, /val want = chosen\s*\n\s*\?: PREFERENCE\.firstNotNullOfOrNull/);
    // The stored id must survive a reconnect, which Android's own device id does not.
    assert.ok(!/Output\(\s*device\.id/.test(route), "remembered by kind and name, not by Android's id");
    // Before Android 12 a Bluetooth headset needs its call link started, and one that never
    // brings it up (headphones with no microphone) is given up on rather than asked forever.
    assert.match(route, /audio\.startBluetoothSco\(\)/);
    assert.match(route, /SCO_WAIT_MS/);
    assert.match(route, /audio\.stopBluetoothSco\(\)/, 'and the link is let go of');

    // Leaving a room must not reset the choice: prefer is sent only when it is given.
    const bridge = read('src/platform/native-capacitor.js');
    assert.match(bridge, /prefer === undefined\s*\n?\s*\? \{ on: Boolean\(on\) \}/);
    assert.match(read('src/room/index.js'), /changedKey === 'callOutput'\) platform\.callAudio\?\.choose\?\.\(/);
});
