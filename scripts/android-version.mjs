// Android's two version numbers, derived from package.json.
//
// versionName is the version string itself. versionCode is the integer Android compares to
// decide whether an APK is an update, and it must only ever grow: an APK with a lower code
// than the installed one is refused outright. MAJOR * 1,000,000 + MINOR * 1,000 + PATCH grows
// exactly when semver does — provided minor and patch stay under 1000, which is checked
// rather than trusted, because 0.1.1000 would quietly become 0.2.0's code.
//
// android/app/build.gradle computes the same thing in Groovy (Gradle cannot import this),
// and test/android-project.test.js holds the two formulas together.
//
// Run directly, it prints both for the current package.json:  node scripts/android-version.mjs

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export function versionCodeFor(version) {
    const match = /^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/.exec(String(version));
    if (!match) throw new Error(`"${version}" is not MAJOR.MINOR.PATCH`);
    const [major, minor, patch] = match.slice(1).map(Number);
    if (minor >= 1000 || patch >= 1000) {
        throw new Error(`minor and patch must stay under 1000 for versionCode to keep growing ("${version}")`);
    }
    return major * 1_000_000 + minor * 1_000 + patch;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
    const { version } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
    console.log(`versionName ${version}\nversionCode ${versionCodeFor(version)}`);
}
