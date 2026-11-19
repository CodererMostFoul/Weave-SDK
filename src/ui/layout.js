// Where the app takes the shape of a phone.
//
// One string, because three places decide by it and they must agree: the stylesheet's
// phone block (one column, the rail in the drawer, the controls docked along the bottom),
// a person's menu opening as a sheet (room/peer-actions.js), and a bubble's card doing the
// same (room/bubbles.js). shell.css cannot import it, so test/tokens.test.js holds the CSS
// to this exact text.
//
// Width alone was the old rule, at 480px, and it decided by the wrong thing. A phone is a
// phone at any width: a large one, a foldable, or any phone with its display size turned
// down reports more than 480px to the page, and those got the narrow-window layout of a
// computer instead — the rail along the side and the profile and voice controls folded
// away with the sidebar. A finger as the main pointer is what makes a phone; 860px is
// where the side column comes back, so the shape stays the same up to there. A computer
// window narrowed below 480px still takes the phone shape, as before.

export const PHONE_LAYOUT = '(max-width: 480px), (pointer: coarse) and (max-width: 860px)';
