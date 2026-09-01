const containerId = document.body.getAttribute('data-container-id');
const protocol = document.body.getAttribute('data-protocol');
const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

// A plain click on an <iframe> does NOT reliably transfer keyboard focus into it - confirmed
// live: the click lands, document.activeElement stays <body>, and every keystroke is silently
// dropped (no console error, since nothing inside the iframe ever sees the event to fail on).
// Guacamole's own client only listens for key events once ITS document has focus, so this must be
// forced explicitly rather than relying on the browser's native click-to-focus iframe behavior.
const guacFrame = document.getElementById('guac-frame');
guacFrame.addEventListener('load', () => guacFrame.focus());
document.body.addEventListener('click', () => guacFrame.focus());

(async () => {
    const response = await fetch(`${basePath}/api/containers/${containerId}/session/${protocol}`, { method: 'POST' });
    if (!response.ok) {
        document.body.textContent = t('page.session.failedToStart', await response.text());
        return;
    }
    const { url } = await response.json();
    guacFrame.src = url;
})();
