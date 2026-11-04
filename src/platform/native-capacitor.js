// The Android bridge: the only file that imports @capacitor/*.
//
// It translates Capacitor's plugins into the same shapes the Electron preload offers
// (tokens, credentials, links, diagnostics), plus the few things only a phone has — a Back
// button, being sent to the background, changing networks. mobile.js decides what the rest
// of the app sees; this file only speaks to the native side.
//
// Outside the Android app — `npm run dev:mobile` in a desktop browser — Capacitor reports
// it is not native and the bridge is null, so the mobile build degrades to browser answers
// exactly as it does in tests.

import { Capacitor, registerPlugin } from '@capacitor/core';
import { App } from '@capacitor/app';

// MainActivity registers the plugin under this name (WeaveNativePlugin.kt).
const WeaveNative = registerPlugin('WeaveNative');

/** A Keystore-backed map, one per file, with the desktop store's calling convention. */
function secretStore(store) {
    return {
        async get(key) {
            const { value } = await WeaveNative.secretGet({ store, key: String(key) });
            return value ?? null;
        },
        async set(key, value) {
            const { ok } = await WeaveNative.secretSet({ store, key: String(key), value: String(value) });
            return ok === true;
        },
        async clear(key) {
            const { ok } = await WeaveNative.secretClear({ store, key: String(key) });
            return ok === true;
        },
    };
}

function createBridge() {
    const tokens = secretStore('tokens');
    const saved = secretStore('credentials');

    // boot() runs again on every sign-out and registers its deep-link handler each time, so
    // the native listener is added once and only the handler it calls is replaced. The URL
    // that launched the app is delivered once, to whichever handler is current first.
    let onLink = null;
    let linkListening = false;
    const launch = App.getLaunchUrl().then((result) => result?.url ?? null).catch(() => null);
    let launchDelivered = false;

    // Back is one handler for the life of the page, for the same reason.
    let onBack = null;
    let backListening = false;

    return {
        tokens,

        // Stored as one JSON value per server, so the file holds the same map the desktop
        // credentials.dat does.
        credentials: {
            async get(serverId) {
                const raw = await saved.get(serverId);
                if (!raw) return null;
                try {
                    const { username, password } = JSON.parse(raw);
                    return { username: String(username ?? ''), password: String(password ?? '') };
                } catch {
                    return null;
                }
            },
            set: (serverId, username, password) => saved.set(serverId,
                JSON.stringify({ username: String(username), password: String(password) })),
            clear: (serverId) => saved.clear(serverId),
        },

        links: {
            onDeepLink(cb) {
                onLink = cb;
                if (!linkListening) {
                    linkListening = true;
                    App.addListener('appUrlOpen', ({ url }) => onLink?.(String(url ?? '')));
                }
                if (!launchDelivered) {
                    launchDelivered = true;
                    launch.then((url) => { if (url) onLink?.(url); });
                }
            },
            /** The URL the app was opened with, if any — so a launch from an invite is not
             *  swallowed by signing straight back in to some other server. */
            launchUrl: () => launch,
        },

        back: {
            onBack(cb) {
                onBack = cb;
                if (backListening) return;
                backListening = true;
                // Any listener at all switches off Capacitor's default, which is to go back in
                // the WebView's history and then exit the app — and exiting ends the call.
                App.addListener('backButton', () => onBack?.());
            },
            minimize: () => App.minimizeApp().catch(() => {}),
        },

        lifecycle: {
            onChange(cb) {
                const handle = App.addListener('appStateChange', ({ isActive }) => cb(Boolean(isActive)));
                return () => { handle.then((h) => h.remove()).catch(() => {}); };
            },
        },

        network: {
            status: () => WeaveNative.networkStatus().catch(() => ({ available: true, type: 'other' })),
            onChange(cb) {
                const handle = WeaveNative.addListener('network', (status) => cb(status));
                return () => { handle.then((h) => h.remove()).catch(() => {}); };
            },
        },

        diagnostics: {
            // The updater log arrives with in-app updates (Phase 4); until then there is none.
            async read() { return null; },
            readAppLog: (maxBytes) => WeaveNative.readLog({ maxBytes: Number(maxBytes) || 128 * 1024 })
                .catch(() => null),
            // A phone has no folder to show anyone.
            async openFolder() {},
        },

        writeLog: (lines) => WeaveNative.log({ lines }).catch(() => {}),

        session: { resumeOnLaunch: true },
    };
}

export const bridge = Capacitor.isNativePlatform() ? createBridge() : null;
