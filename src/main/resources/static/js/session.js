const containerId = document.body.getAttribute('data-container-id');
const protocol = document.body.getAttribute('data-protocol');
const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const keystrokePanelPlacement = document.body.getAttribute('data-keystroke-panel-placement');

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
}

(async () => {
    const response = await fetch(`${basePath}/api/containers/${containerId}/session/${protocol}`, { method: 'POST' });
    if (!response.ok) {
        document.body.textContent = t('page.session.failedToStart', await response.text());
        return;
    }
    const { url } = await response.json();
    guacFrame.src = url;
    if (keystrokePanelPlacement !== 'NONE') {
        const encodedClientId = url.split('/#/client/')[1].split('?')[0];
        setUpKeystrokeButtonPanel(encodedClientId);
    }
})();
