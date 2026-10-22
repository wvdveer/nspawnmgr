// Wrapped in an IIFE so none of this file's own top-level declarations are global - classic <script>
// tags (no type="module" here) share ONE global lexical scope across every script on the page, so an
// un-wrapped top-level const/function here could collide with a same-named one in clipboard.js or any
// other script (see that file's own matching comment for the exact bug this caused live).
(() => {

const containerId = document.body.getAttribute('data-container-id');
const protocol = document.body.getAttribute('data-protocol');
const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const keystrokePanelPlacement = document.body.getAttribute('data-keystroke-panel-placement');
const clipboardCopyLoggingEnabled = document.body.getAttribute('data-clipboard-copy-logging-enabled') === 'true';
const { PASTE_MAX_LENGTH, sendText, readPasteText, writeClipboard } = window.NspawnmgrClipboard;

// A plain click on an <iframe> does NOT reliably transfer keyboard focus into it - confirmed
// live: the click lands, document.activeElement stays <body>, and every keystroke is silently
// dropped (no console error, since nothing inside the iframe ever sees the event to fail on).
// Guacamole's own client only listens for key events once ITS document has focus, so this must be
// forced explicitly rather than relying on the browser's native click-to-focus iframe behavior.
const guacFrame = document.getElementById('guac-frame');
guacFrame.addEventListener('load', () => guacFrame.focus());
document.body.addEventListener('click', () => guacFrame.focus());

// Fixed X11 keysyms for the 4 modifier checkboxes a key sequence can combine - only the main key
// (KeySequence.mainKeysym) is admin-selectable, see KeySequenceService.MAIN_KEY_OPTIONS.
const MODIFIER_KEYSYMS = { ctrl: 0xFFE3, alt: 0xFFE9, shift: 0xFFE1, superKey: 0xFFEB };

/**
 * Reaches into the embedded, stock Guacamole webapp's own AngularJS app to find the live
 * Guacamole.Client for this session - proven live: guacamole.war runs same-origin (see
 * GuacamoleSessionService.buildSessionUrl's own javadoc), so its iframe's contentWindow is
 * reachable, and angular.element(...).injector().get('guacClientManager') is the same technique
 * Guacamole's own browser console debugging docs use. encodedClientId is the exact
 * "<connectionId>\0c\0<dataSource>" (base64url) identifier baked into the session URL's own
 * #/client/<id> hash - the same key guacClientManager.getManagedClients() itself uses, so this
 * finds *this* session's client specifically rather than guessing "the first/only one".
 */
function findGuacamoleClient(encodedClientId) {
    const frameWindow = guacFrame.contentWindow;
    if (!frameWindow || !frameWindow.angular) {
        return null;
    }
    try {
        const managedClients = frameWindow.angular.element(frameWindow.document.body)
            .injector().get('guacClientManager').getManagedClients();
        const managedClient = managedClients[encodedClientId];
        return managedClient ? managedClient.client : null;
    } catch (e) {
        return null;
    }
}

/** Presses every modifier then the main key, then releases in reverse order - real keyboards
 *  report modifiers down first and up last, and xrdp/vnc-side shortcut handling relies on that
 *  order to recognize the combination at all. */
function sendKeySequence(client, sequence) {
    const keysyms = ['ctrl', 'alt', 'shift', 'superKey']
        .filter((modifier) => sequence[modifier])
        .map((modifier) => MODIFIER_KEYSYMS[modifier]);
    keysyms.push(sequence.mainKeysym);
    keysyms.forEach((keysym) => client.sendKeyEvent(1, keysym));
    keysyms.slice().reverse().forEach((keysym) => client.sendKeyEvent(0, keysym));
}

/** Types whatever readPasteText() resolves into the live Guacamole client as synthetic keystrokes.
 *  Capped at PASTE_MAX_LENGTH - this goes in one key-event pair per character, so there's no sane
 *  way to "paste" megabytes of clipboard text this way, unlike a real clipboard-paste operation.
 *  navigator.clipboard.readText() runs directly inside this click handler (via readPasteText) so it
 *  has the user-gesture a browser requires before it allows the read at all. */
function addPasteFromBrowserButton(panel, status, encodedClientId) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = t('page.session.pasteFromBrowser');
    button.addEventListener('click', async () => {
        const text = await readPasteText();
        if (typeof text !== 'string' || text.length === 0 || text.length > PASTE_MAX_LENGTH) {
            status.textContent = t('page.session.pasteFromBrowserInvalid', PASTE_MAX_LENGTH);
            return;
        }
        const client = findGuacamoleClient(encodedClientId);
        if (!client) {
            status.textContent = t('page.session.keystrokePanelNotConnectedYet');
            return;
        }
        status.textContent = '';
        sendText(client, text);
        guacFrame.focus();
    });
    panel.appendChild(button);
}

async function setUpKeystrokeButtonPanel(encodedClientId) {
    const panel = document.getElementById('keystroke-button-panel');
    const status = document.getElementById('keystroke-button-panel-status');
    const response = await fetch(`${basePath}/api/key-sequences`);
    if (!response.ok) {
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    const sequences = await response.json();
    sequences.forEach((sequence) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = sequence.label;
        button.addEventListener('click', () => {
            const client = findGuacamoleClient(encodedClientId);
            if (!client) {
                status.textContent = t('page.session.keystrokePanelNotConnectedYet');
                return;
            }
            status.textContent = '';
            sendKeySequence(client, sequence);
            guacFrame.focus();
        });
        panel.appendChild(button);
    });
    addPasteFromBrowserButton(panel, status, encodedClientId);
}

// The embedded, stock Guacamole webapp already tries to sync the remote session's clipboard to the
// browser's own clipboard automatically on client.onclipboard - but since it runs same-origin
// inside our <iframe> without clipboard-write permission delegated to it, that write silently fails.
// Wiring the same handler from here instead runs the actual navigator.clipboard.writeText() call in
// the TOP-level page, which isn't subject to that iframe restriction. Independent of the Keystroke
// Button Panel's placement/visibility - this has no button, it just runs in the background.
function setUpClipboardSync(encodedClientId) {
    const maxAttempts = 120; // ~60s at 500ms - the client doesn't exist until the webapp inside the
                              // iframe finishes connecting; give up quietly if that never happens.
    let attempts = 0;
    const intervalId = setInterval(() => {
        attempts++;
        const client = findGuacamoleClient(encodedClientId);
        if (client) {
            clearInterval(intervalId);
            client.onclipboard = (stream, mimetype) => {
                if (!mimetype.startsWith('text/')) {
                    return;
                }
                const reader = new guacFrame.contentWindow.Guacamole.StringReader(stream);
                let text = '';
                reader.ontext = (chunk) => { text += chunk; };
                reader.onend = () => {
                    // No click triggered this (it fires whenever the REMOTE side announces new
                    // clipboard content, e.g. a Ctrl+C inside the session) - writeClipboard's
                    // localStorage-first, best-effort-OS-clipboard-on-top shape (see clipboard.js)
                    // is exactly what keeps that safe to call unconditionally from here.
                    writeClipboard(text);
                    // Audit trail (opt-in, per-container - see Container.clipboardCopyLoggingEnabled's
                    // own javadoc on why this defaults off): gated client-side, not just by the
                    // server's own authoritative check in ContainerApiController.logClipboardCopy, so
                    // the copied text isn't sent over the wire at all when this is disabled - truncated
                    // client-side too, so a very large remote clipboard isn't shipped in full for no
                    // reason either way. Fire-and-forget: a logging failure must not block or throw
                    // into clipboard sync.
                    if (clipboardCopyLoggingEnabled) {
                        fetch(`${basePath}/api/containers/${containerId}/session/clipboard-copy`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ text: text.slice(0, 250) }),
                        }).catch(() => {});
                    }
                };
            };
        } else if (attempts >= maxAttempts) {
            clearInterval(intervalId);
        }
    }, 500);
}

(async () => {
    const response = await fetch(`${basePath}/api/containers/${containerId}/session/${protocol}`, { method: 'POST' });
    if (!response.ok) {
        document.body.textContent = t('page.session.failedToStart', await response.text());
        return;
    }
    const { url } = await response.json();
    guacFrame.src = url;
    const encodedClientId = url.split('/#/client/')[1].split('?')[0];
    setUpClipboardSync(encodedClientId);
    if (keystrokePanelPlacement !== 'NONE') {
        setUpKeystrokeButtonPanel(encodedClientId);
    }
})();

})();
