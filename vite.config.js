import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

// Three build targets from one source tree.
//
//   browser  — served by a Weave server itself, so the server address is its own origin
//   desktop  — an Electron shell that ships blank and is pointed at a server
//   mobile   — the Android app: a Capacitor WebView that, like desktop, ships blank. Its page
//              is served from the app itself (http://localhost), never by a Weave server
//
// The difference is a build-time constant rather than a runtime check, so the browser
// bundle does not carry server-management UI it can never use, and the desktop bundle
// does not carry an origin assumption that is false for it.
//
// The target is selected with vite's own `--mode` flag rather than an environment variable,
// because `WEAVE_TARGET=desktop npm run build` is not a thing that works on Windows: npm
// runs scripts through cmd, which has no inline environment prefix. The variable still
// works when it is set, for anyone who prefers it.
export default defineConfig(({ mode }) => {
    const target = process.env.WEAVE_TARGET
        ?? (mode === 'desktop' || mode === 'mobile' ? mode : 'browser');
    const outDir = { desktop: 'dist-electron/renderer', mobile: 'dist-mobile' }[target] ?? 'dist';

    return {
        base: './',
        define: {
            __WEAVE_TARGET__: JSON.stringify(target),
            __WEAVE_VERSION__: JSON.stringify(process.env.npm_package_version ?? '0.0.0'),
        },
        // The one place the Android bridge is chosen. Every other build, and every test,
        // resolves `#native` through package.json to the null bridge, so @capacitor/* never
        // enters their bundles.
        resolve: target === 'mobile'
            ? { alias: { '#native': fileURLToPath(new URL('./src/platform/native-capacitor.js', import.meta.url)) } }
            : {},
        build: {
            outDir,
            emptyOutDir: true,
            target: 'chrome120',
            sourcemap: true,
        },
        server: {
            port: 5173,
            // During development the client runs on its own origin and talks to a real server,
            // which is the same cross-origin path the desktop build uses in production. That
            // is deliberate: it exercises CORS in development rather than discovering it later.
            strictPort: true,
        },
    };
});
