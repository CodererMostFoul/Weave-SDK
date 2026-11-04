// No native bridge: what `#native` means everywhere except the Android build.
//
// package.json maps `#native` here, so the browser and desktop bundles, and every test under
// `node --test`, get a null bridge without knowing the Android one exists. The mobile build
// aliases `#native` to native-capacitor.js instead (vite.config.js). Keeping the switch at
// the module level is what keeps @capacitor/* out of every other bundle entirely, rather
// than shipped and never called.

export const bridge = null;
