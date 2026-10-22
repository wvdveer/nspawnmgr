// Exercises the pure helpers in clipboard.js (session.js's Paste-from-browser button and
// setUpClipboardSync - see that file's own header comment) directly, as real function calls against
// this browser's own localStorage - no live Guacamole session or container needed to drive, unlike
// every other suite here. Loaded via an absolute path since clipboard.js is a plain static resource
// of the nspawnmgr webapp, not something this separate test-harness webapp ships itself - reachable
// because both are deployed into the same Tomcat instance (see harness.js's own header comment).
function assertEqual(actual, expected, desc) {
    if (actual === expected) {
        window.harness.pass(desc);
    } else {
        window.harness.fail(desc, `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
    }
}

// navigator.clipboard is normally a read-only accessor inherited from Navigator.prototype -
// shadowing it on the instance with a configurable own property lets this suite stub
// readText/writeText without touching the user's REAL OS clipboard, and `delete` cleanly unshadows
// the prototype's own getter again afterward rather than trying to reconstruct it.
function stubClipboard(impl) {
    Object.defineProperty(navigator, 'clipboard', { value: impl, configurable: true });
}
function restoreClipboard() {
    delete navigator.clipboard;
}

async function runClipboardTests() {
    const h = window.harness;
    h.section('clipboard: module loaded');

    const c = window.NspawnmgrClipboard;
    if (!c) {
        h.fail('window.NspawnmgrClipboard is defined', 'clipboard.js did not load - check the <script> tag in index.html');
        return;
    }
    h.pass('window.NspawnmgrClipboard is defined');

    h.section('clipboard: charToKeysym');
    assertEqual(c.charToKeysym('A'), 0x41, "'A' maps to its own ASCII code point (0x41)");
    assertEqual(c.charToKeysym(' '), 0x20, "space maps to 0x20");
    assertEqual(c.charToKeysym('\n'), 0xFF0D, "newline maps to the X11 Return keysym (0xFF0D), not 0x0A");
    assertEqual(c.charToKeysym('\t'), 0xFF09, "tab maps to the X11 Tab keysym (0xFF09), not 0x09");
    assertEqual(c.charToKeysym('é'), 0xe9, "Latin-1 'é' maps to its own code point (0xE9)");
    assertEqual(c.charToKeysym('€'), 0x010020ac, "'€' (U+20AC, past Latin-1) maps to the X11 Unicode keysym range (0x01000000 + code point)");
    assertEqual(c.charToKeysym('😀'), 0x0101f600, "an astral character (U+1F600 😀, surrogate pair) maps via its full code point, not a lone surrogate");

    h.section('clipboard: sendText');
    await h.step('presses then releases each character in order, including special keysyms', () => {
        const calls = [];
        const fakeClient = { sendKeyEvent: (pressed, keysym) => calls.push([pressed, keysym]) };
        c.sendText(fakeClient, 'Hi\n');
        const expected = [
            [1, 0x48], [0, 0x48], // H
            [1, 0x69], [0, 0x69], // i
            [1, 0xFF0D], [0, 0xFF0D], // Return
        ];
        assertEqual(JSON.stringify(calls), JSON.stringify(expected), 'sendKeyEvent call sequence matches press/release per character');
    });

    h.section('clipboard: readPasteText / writeClipboard round-trip');
    localStorage.removeItem(c.CLIPBOARD_STORAGE_KEY);
    try {
        await h.step('readPasteText prefers a real, non-empty OS clipboard over the virtual one', async () => {
            localStorage.setItem(c.CLIPBOARD_STORAGE_KEY, 'from-storage');
            stubClipboard({ readText: async () => 'from-clipboard' });
            const text = await c.readPasteText();
            assertEqual(text, 'from-clipboard', 'resolved text came from the (stubbed) real clipboard');
        });

        await h.step('readPasteText falls back to the virtual clipboard when the real read rejects', async () => {
            localStorage.setItem(c.CLIPBOARD_STORAGE_KEY, 'from-storage');
            stubClipboard({ readText: async () => { throw new DOMException('denied', 'NotAllowedError'); } });
            const text = await c.readPasteText();
            assertEqual(text, 'from-storage', 'resolved text came from localStorage after the clipboard read was denied');
        });

        await h.step('readPasteText falls back to the virtual clipboard when the real read resolves empty', async () => {
            localStorage.setItem(c.CLIPBOARD_STORAGE_KEY, 'from-storage-2');
            stubClipboard({ readText: async () => '' });
            const text = await c.readPasteText();
            assertEqual(text, 'from-storage-2', 'resolved text came from localStorage after an empty clipboard read');
        });

        await h.step('readPasteText resolves empty when both the real and virtual clipboard are empty', async () => {
            localStorage.removeItem(c.CLIPBOARD_STORAGE_KEY);
            stubClipboard({ readText: async () => '' });
            const text = await c.readPasteText();
            assertEqual(text, '', 'resolved to an empty string rather than throwing');
        });

        await h.step('writeClipboard writes to localStorage and attempts the real clipboard on top', async () => {
            const writeTextCalls = [];
            stubClipboard({ writeText: async (text) => { writeTextCalls.push(text); } });
            c.writeClipboard('hello-world');
            assertEqual(localStorage.getItem(c.CLIPBOARD_STORAGE_KEY), 'hello-world', 'localStorage was updated');
            // writeText is async/fire-and-forget from writeClipboard's point of view - give its
            // microtask a tick to run before asserting on it.
            await Promise.resolve();
            assertEqual(JSON.stringify(writeTextCalls), JSON.stringify(['hello-world']), 'the (stubbed) real clipboard write was also attempted with the same text');
        });

        await h.step('writeClipboard still saves to localStorage even if the real clipboard write throws synchronously', () => {
            stubClipboard({
                writeText: () => { throw new DOMException('denied', 'NotAllowedError'); },
            });
            let threw = false;
            try {
                c.writeClipboard('still-saved');
            } catch (e) {
                threw = true;
            }
            assertEqual(threw, false, 'writeClipboard did not propagate the synchronous throw - this is the exact failure mode that was observed to wedge a live Guacamole session');
            assertEqual(localStorage.getItem(c.CLIPBOARD_STORAGE_KEY), 'still-saved', 'localStorage was still updated despite the clipboard write failing');
        });

        await h.step('writeClipboard does not throw even if localStorage itself is unavailable', () => {
            const originalSetItem = Storage.prototype.setItem;
            Storage.prototype.setItem = () => { throw new DOMException('quota exceeded', 'QuotaExceededError'); };
            stubClipboard({ writeText: async () => {} });
            let threw = false;
            try {
                c.writeClipboard('irrelevant');
            } catch (e) {
                threw = true;
            } finally {
                Storage.prototype.setItem = originalSetItem;
            }
            assertEqual(threw, false, 'writeClipboard did not propagate a localStorage write failure either');
        });
    } finally {
        restoreClipboard();
        localStorage.removeItem(c.CLIPBOARD_STORAGE_KEY);
    }
}
