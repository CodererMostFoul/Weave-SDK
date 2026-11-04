// The design system, enforced.
//
// A token layer that is merely documented decays: someone drops a hex code in at 1 a.m. and
// nothing objects, and a year later there are four slightly different greys and no way to
// change the accent colour. These tests are what make the rule in tokens.css real.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = fileURLToPath(new URL('../src/styles', import.meta.url));

const read = (name) => readFileSync(join(DIR, name), 'utf8');
const files = readdirSync(DIR).filter((f) => f.endsWith('.css'));
const components = files.filter((f) => f !== 'tokens.css');

/** Strip comments so prose about a rule is never mistaken for the rule. */
const code = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '');

test('every layer exists and is imported exactly once, in dependency order', () => {
    const entry = readFileSync(fileURLToPath(new URL('../src/styles.css', import.meta.url)), 'utf8');
    const order = [...entry.matchAll(/@import\s+"\.\/styles\/([\w-]+\.css)"/g)].map((m) => m[1]);
    assert.deepEqual(order, ['tokens.css', 'base.css', 'auth.css', 'shell.css'],
        'tokens must load first; nothing can use a variable defined after it');
    for (const f of files) assert.ok(order.includes(f), `${f} exists but is never imported`);
});

test('no colour literal outside tokens.css', () => {
    for (const file of components) {
        const hex = code(read(file)).match(/#[0-9a-fA-F]{3,8}\b/g) ?? [];
        assert.deepEqual(hex, [], `${file} contains a raw colour — add a token instead`);

        // A bare rgb()/hsl() with numbers is the same offence wearing a different hat.
        // rgb(var(--x) / 0.2) is the sanctioned form and passes.
        const fn = code(read(file)).match(/\b(?:rgba?|hsla?)\(\s*\d/g) ?? [];
        assert.deepEqual(fn, [], `${file} contains a raw colour function — derive it from a token`);
    }
});

test('no raw z-index outside the ladder', () => {
    for (const file of components) {
        const bad = [...code(read(file)).matchAll(/z-index:\s*([^;]+);/g)]
            .map((m) => m[1].trim())
            .filter((v) => !v.startsWith('var(--z-'));
        assert.deepEqual(bad, [], `${file} sets a z-index that is not a named rung`);
    }
});

test('only base.css may suppress the focus outline', () => {
    // The failure this prevents actually happened: `.composer-input:focus { outline: none }`
    // out-specifies the global :focus-visible rule, so the ring silently disappeared for
    // keyboard users while every automated check still passed.
    for (const file of components.filter((f) => f !== 'base.css')) {
        const bad = code(read(file)).match(/outline(?:-style)?:\s*none/g) ?? [];
        assert.deepEqual(bad, [], `${file} removes a focus outline; move the styling to the wrapper`);
    }

    const base = code(read('base.css'));
    assert.match(base, /:focus\s*\{\s*outline:\s*none/, 'base must clear the default ring');
    assert.match(base, /:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--focus-line\)/,
        'and put a token-driven one back for keyboard use');
});

test('the ladder is ordered, so a rung is never above the one that should cover it', () => {
    const tokens = read('tokens.css');
    const rungs = ['--z-base', '--z-raised', '--z-sticky', '--z-popover', '--z-menu', '--z-modal', '--z-toast', '--z-drag'];
    const values = rungs.map((name) => {
        const m = new RegExp(`${name}:\\s*(-?\\d+)`).exec(tokens);
        assert.ok(m, `${name} is missing from the ladder`);
        return Number(m[1]);
    });
    for (let i = 1; i < values.length; i++) {
        assert.ok(values[i] > values[i - 1], `${rungs[i]} must sit above ${rungs[i - 1]}`);
    }
    assert.ok(Number(/--z-below:\s*(-?\d+)/.exec(tokens)[1]) < 0, 'the background belongs behind everything');
});

test('every token a component references is actually defined', () => {
    // A typo in a var() name is silent: the property just does not apply, and the element
    // renders with whatever it inherited. This is the only thing that catches it.
    const defined = new Set([...read('tokens.css').matchAll(/^\s*(--[\w-]+):/gm)].map((m) => m[1]));
    const missing = new Set();

    for (const file of files) {
        const css = code(read(file));
        for (const m of css.matchAll(/var\(\s*(--[\w-]+)\s*(?:,|\))/g)) {
            // A component may define its own local custom property inline (--av, --author);
            // those are set in markup rather than in the token file.
            // Set inline in markup rather than in the token file: a per-person colour,
            // a per-room hue. They are data, not design decisions.
            // And Capacitor's safe-area insets, which the Android shell sets on the root at
            // runtime and which are absent — falling back to env() — everywhere else.
            if (!defined.has(m[1]) && !['--av', '--author', '--card-hue'].includes(m[1])
                && !m[1].startsWith('--safe-area-inset-')) {
                missing.add(`${file}: ${m[1]}`);
            }
        }
    }
    assert.deepEqual([...missing], []);
});

test('spacing and radius are scales rather than a pile of numbers', () => {
    const tokens = read('tokens.css');
    const space = [...tokens.matchAll(/--s-(\d):\s*(\d+)px/g)].map((m) => [Number(m[1]), Number(m[2])]);
    assert.ok(space.length >= 8, 'the spacing scale should cover the range actually used');
    for (const [, px] of space) assert.equal(px % 4, 0, 'every step is a multiple of the 4px base');
    for (let i = 1; i < space.length; i++) {
        assert.ok(space[i][1] > space[i - 1][1], 'the scale must increase');
    }

    const radius = [...tokens.matchAll(/--r-(\d):\s*(\d+)px/g)].map((m) => Number(m[2]));
    for (let i = 1; i < radius.length; i++) assert.ok(radius[i] > radius[i - 1]);
});

test('the shell constrains itself rather than the page', () => {
    const shell = code(read('shell.css'));
    const base = code(read('base.css'));

    assert.match(base, /body\s*\{[^}]*overflow:\s*hidden/,
        'the page itself must never scroll — a column that overflows should show its own bar');
    assert.match(shell, /\.app-shell\s*\{[^}]*height:\s*100dvh/);

    // Grid and flex children default to min-height: auto, which lets a long list push its
    // parent taller instead of scrolling. Every column has to opt out explicitly.
    assert.match(shell, /\.app-body\s*>\s*\*\s*\{[^}]*min-height:\s*0/);
    assert.match(shell, /\.app-body\s*\{[^}]*grid-template-columns:\s*var\(--rail-w\)\s*var\(--sidebar-w\)\s*minmax\(0,\s*1fr\)/);

    // The rows that make the composer stay put while the timeline scrolls — and since
    // the media phase, the stage row between the header and the messages.
    assert.match(shell, /\.room\s*\{[^}]*grid-template-rows:\s*auto fit-content\(62%\) minmax\(0,\s*1fr\) auto auto/);
    // The regression this pins: children must be PINNED to rows, because hidden slots
    // otherwise shift the timeline up a row and the composer off the screen.
    assert.match(shell, /\.room\s*>\s*\.composer-wrap\s*\{\s*grid-row:\s*5/);
});

test('narrow windows drop whole columns instead of squeezing them', () => {
    // A 140px member list is not a member list. Each breakpoint must remove a column from
    // the grid template as well as hiding it, or the space stays reserved and empty.
    const shell = code(read('shell.css'));
    const queries = [...shell.matchAll(/@media \(max-width: (\d+)px\)\s*\{([\s\S]*?)\n\}/g)];
    assert.ok(queries.length >= 3, 'expected breakpoints for members, sidebar and rail');

    const widths = queries.map((q) => Number(q[1]));
    for (let i = 1; i < widths.length; i++) {
        assert.ok(widths[i] < widths[i - 1], 'breakpoints must read largest to smallest');
    }
    for (const [, , body] of queries) {
        assert.match(body, /display:\s*none/, 'each breakpoint hides something');
        assert.match(body, /grid-template-columns/, 'and reclaims its column from the grid');
    }
});

/* ── phones and touch ─────────────────────────────────────────────────────── */

/** The body of the first `@media <query> {` block, up to its closing brace at column 0. */
function mediaBlock(css, query) {
    const at = css.indexOf(`@media ${query} {`);
    assert.ok(at >= 0, `expected a block for ${query}`);
    const end = css.indexOf('\n}', at);
    return { at, body: css.slice(at, end) };
}

test('touch has its own tokens: a fingertip-sized target and the dock it sits in', () => {
    const tokens = read('tokens.css');
    assert.match(tokens, /--tap-min:\s*44px/, 'the platforms agree on 44px; anything smaller is a coin-toss');
    assert.match(tokens, /--dock-h:\s*\d+px/);
});

test('the room states its column, so nothing inside can push it wider than its panel', () => {
    // Without this the implicit column grew to the header's one-line topic and the right of
    // every message was cut off below about 860px.
    assert.match(code(read('shell.css')), /\.room\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/);
});

test('a phone gets one column, a dock for its voice controls, and the rail in the drawer', () => {
    const { body } = mediaBlock(code(read('shell.css')), '(max-width: 480px)');
    assert.match(body, /\.app-body\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/);
    assert.match(body, /\.rail\s*\{\s*display:\s*none/);
    // The dock is the closed sidebar showing only the self bar — the same DOM, never a copy.
    assert.match(body, /\.app-shell:not\(\.drawer-open\) \.sidebar > :not\(#selfBarSlot\)\s*\{\s*display:\s*none/);
    assert.match(body, /\.app-shell\.drawer-open \.rail\s*\{[^}]*position:\s*fixed/);
    // The keyboard takes the space the dock was using.
    assert.match(body, /:has\(\.composer-input:focus\) \.sidebar\s*\{\s*display:\s*none/);
});

test('nothing waits for a hover a touchscreen cannot make', () => {
    const shell = code(read('shell.css'));
    const { at, body } = mediaBlock(shell, '(hover: none)');
    // Each hover-only control, and the hover rule it replaces. The reveal has to come AFTER
    // that rule: both have the same specificity, and the later one wins — a reveal placed
    // above it would be overruled without a word.
    for (const [reveal, hoverRule] of [
        ['.room-chat', '.room-line:hover .room-chat'],
        ['.msg-gutter-time', '.msg.continued:hover .msg-gutter-time'],
        ['.preview-dismiss', '.msg:hover .preview-dismiss'],
        ['.adm-actions', '.adm-row:hover .adm-actions'],
        ['.bubble-label', '.bubble:hover .bubble-label'],
        ['.watch-btn', '.tile:hover .watch-btn'],
        ['.stream-pill', '.tile:hover .stream-pill'],
        ['.reaction.add', '.msg:hover .reaction.add'],
    ]) {
        assert.ok(body.includes(reveal), `${reveal} is revealed on touch`);
        const hover = shell.indexOf(hoverRule);
        assert.ok(hover >= 0, `the hover rule for ${reveal} still exists`);
        assert.ok(at > hover, `the touch reveal for ${reveal} must come after ${hoverRule}`);
    }
});

test('a coarse pointer gets fingertip targets and pickers that fit the screen', () => {
    const { body } = mediaBlock(code(read('shell.css')), '(pointer: coarse)');
    assert.match(body, /\.round-btn[^{]*\{[^}]*min-height:\s*var\(--tap-min\)/);
    assert.match(body, /\.emoji-pop\s*\{[^}]*auto-fill/);
    assert.match(body, /\.react-pick\s*\{[^}]*flex-wrap:\s*wrap/);
    assert.match(body, /\.stream-pill\s*\{[^}]*overflow-x:\s*auto/);
});

test('the narrow settings rules outrank the settings rules declared after them', () => {
    // The previous 720px block was a dead letter: `.settings-nav { display: none }` sat ABOVE
    // the base `.settings-nav` rule, lost to it at equal specificity, and never hid anything.
    // Every settings selector in the block must now carry something the base rule does not.
    const { body } = mediaBlock(code(read('shell.css')), '(max-width: 720px)');
    const bare = [...body.matchAll(/^\s*(\.settings[\w-]*)\s*\{/gm)].map((m) => m[1]);
    assert.deepEqual(bare, [], 'a bare .settings selector here loses to the base rule below it');
    assert.match(body, /\.settings\[data-pane="panel"\] \.settings-nav\s*\{\s*display:\s*none/);
    assert.match(body, /\.settings\[data-pane="nav"\] \.settings-panel\s*\{\s*display:\s*none/);
});

test("the phone's own bars are kept clear through the safe tokens, and only through them", () => {
    const tokens = code(read('tokens.css'));
    for (const side of ['top', 'right', 'bottom', 'left']) {
        // Capacitor's measured value first: in the spike, env() read 0 inside the WebView
        // while Capacitor's variables held the real 29px and 48px.
        assert.match(tokens,
            new RegExp(`--safe-${side}: var\\(--safe-area-inset-${side}, env\\(safe-area-inset-${side}, 0px\\)\\);`),
            `--safe-${side}`);
    }
    for (const file of components) {
        assert.doesNotMatch(code(read(file)), /env\(safe-area-inset|--safe-area-inset-/,
            `${file} should pad by the --safe-* tokens, not read the insets itself`);
    }

    const pad = 'padding: var(--safe-top) var(--safe-right) var(--safe-bottom) var(--safe-left);';
    // The body of the first rule whose selector is exactly this one.
    const rule = (css, selector) => {
        const at = css.search(new RegExp(`(?:^|\\n)${selector.replaceAll('.', '\\.')} \\{`));
        return at < 0 ? '' : css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
    };
    assert.ok(rule(code(read('shell.css')), '.app-shell').includes(pad), 'the room');
    assert.ok(rule(code(read('auth.css')), '.app').includes(pad), 'the sign-in screen');
    assert.match(rule(code(read('shell.css')), '.update-bar'), /padding-bottom: var\(--safe-bottom\)/);
    assert.match(rule(code(read('shell.css')), '.peer-menu.as-sheet'), /var\(--safe-bottom\)/,
        'a sheet along the bottom clears the gesture bar');
});

test("a bubble's card is a sheet on a phone, and a rebuilt card does not make a second entrance", () => {
    const css = code(read('shell.css'));
    const body = (selector) => {
        const at = css.indexOf(`\n${selector} {`);
        return at < 0 ? '' : css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
    };
    const sheet = body('.bubble-popup.as-sheet');
    assert.match(sheet, /bottom: 0;/, 'pinned to the bottom, so it rides above the keyboard');
    assert.match(sheet, /padding-bottom: var\(--safe-bottom\)/, 'and clear of the gesture bar');
    assert.match(body('.bubble-popup.settled'), /animation: none;/);
    // Equal specificity, so the order is what makes "settled" win over both entrances.
    assert.ok(css.indexOf('\n.bubble-popup.settled {') > css.indexOf('\n.bubble-popup.as-sheet {'));
    assert.ok(css.indexOf('\n.bubble-popup.settled {') > css.indexOf('\n.bubble-popup {'));
});
