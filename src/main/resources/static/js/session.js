const containerId = document.body.getAttribute('data-container-id');
const protocol = document.body.getAttribute('data-protocol');
const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

(async () => {
    const response = await fetch(`${basePath}/api/containers/${containerId}/session/${protocol}`, { method: 'POST' });
    if (!response.ok) {
        document.body.textContent = 'Failed to start session: ' + await response.text();
        return;
    }
    const { url } = await response.json();
    document.getElementById('guac-frame').src = url;
})();
