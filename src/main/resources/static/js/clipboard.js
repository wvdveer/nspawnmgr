// Pure, DOM/session-independent clipboard helpers for session.js's Paste-from-browser button and
// setUpClipboardSync - split out into their own file (no dependency on a live Guacamole client,
// container id, or iframe) so they can be unit-tested directly against a real browser's own
// localStorage/navigator.clipboard, see tools/web-test-harness/tests-clipboard.js.
'use strict';

// Wrapped in an IIFE so only window.NspawnmgrClipboard below is actually global - classic <script>
// tags (no type="module" here) share ONE global lexical scope across every script on the page, so a
// top-level const/function in this file would otherwise collide with any same-named top-level
// binding in session.js or any other script - confirmed live: session.js destructuring
// { PASTE_MAX_LENGTH, sendText, readPasteText, writeClipboard } as its own top-level consts threw
// "Identifier 'PASTE_MAX_LENGTH' has already been declared" against this file's own un-wrapped
// top-level const/function declarations of the same names.
(() => {

// Shared "virtual clipboard" the Copy/Paste buttons use as their primary path - plain localStorage,
// not the real OS clipboard, since the Clipboard API is permission-gated (and can be denied outright
// by the browser/site settings - confirmed by user report), while localStorage never prompts and
// always works same-origin. Also means Copy-to-host in one session tab and Paste-from-browser in
// ANOTHER session tab relay through each other even when the real OS clipboard is unreachable.
const CLIPBOARD_STORAGE_KEY = 'nspawnmgr.sessionClipboard';

const PASTE_MAX_LENGTH = 250;

// Characters the X11 keysym space doesn't map 1:1 from their own code point - needed since pasted
// clipboard text routinely contains newlines/tabs, unlike the fixed special-key sequences
// session.js's own sendKeySequence sends.
const PASTE_SPECIAL_KEYSYMS = { '\n': 0xFF0D, '\r': 0xFF0D, '\t': 0xFF09 };

/** ASCII and Latin-1 code points are numerically identical to their own X11 keysym (same
 *  convention KeySequenceService.MAIN_KEY_OPTIONS already relies on for digits/letters) - anything
 *  past that falls back to the X11 Unicode keysym range (0x01000000 + code point), the same scheme
 *  Guacamole's own on-screen keyboard uses for characters it has no dedicated keysym for. */
function charToKeysym(char) {
    if (PASTE_SPECIAL_KEYSYMS[char] !== undefined) {
        return PASTE_SPECIAL_KEYSYMS[char];
    }
    const codepoint = char.codePointAt(0);
    return codepoint <= 0xFF ? codepoint : 0x01000000 + codepoint;
}

/** Presses then releases one plain key at a time (no modifiers) for each character of `text` -
 *  `for...of` walks by Unicode code point, not UTF-16 code unit, so this handles characters outside
 *  the Basic Multilingual Plane correctly too. */
function sendText(client, text) {
    for (const char of text) {
        const keysym = charToKeysym(char);
        client.sendKeyEvent(1, keysym);
        client.sendKeyEvent(0, keysym);
    }
}

/** Prefers the real OS clipboard (freshest - reflects whatever was last copied in ANY app, not just
 *  a Guacamole session) but falls back to the CLIPBOARD_STORAGE_KEY virtual clipboard whenever the
 *  Clipboard API throws, rejects, or simply comes back empty - the read can be denied outright
 *  depending on the browser/site's own clipboard permission, with no way to distinguish "denied"
 *  from "genuinely empty" ahead of time, so both cases fall through the same way. */
async function readPasteText() {
    try {
        const clipboardText = await navigator.clipboard.readText();
        if (clipboardText) {
            return clipboardText;
        }
    } catch (e) {
        // fall through to the virtual clipboard below
    }
    try {
        return localStorage.getItem(CLIPBOARD_STORAGE_KEY) || '';
    } catch (e) {
        return '';
    }
}

/** Writes `text` to the virtual clipboard unconditionally, then makes a best-effort attempt at the
 *  real OS clipboard on top - wrapped so neither a synchronous Permissions Policy throw nor a
 *  rejected promise from that bonus attempt can escape to the caller. session.js's
 *  setUpClipboardSync calls this from inside Guacamole's own client.onclipboard callback, where an
 *  uncaught exception was observed live to wedge the whole session, not just clipboard sync. */
function writeClipboard(text) {
    try {
        localStorage.setItem(CLIPBOARD_STORAGE_KEY, text);
    } catch (e) {
        // ignore - quota exceeded or storage disabled (e.g. private browsing)
    }
    try {
        navigator.clipboard.writeText(text).catch(() => {});
    } catch (e) {
        // ignore - synchronous Permissions Policy denial
    }
}

window.NspawnmgrClipboard = {
    CLIPBOARD_STORAGE_KEY,
    PASTE_MAX_LENGTH,
    charToKeysym,
    sendText,
    readPasteText,
    writeClipboard,
};

})();
