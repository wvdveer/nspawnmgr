const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const hostId = document.body.getAttribute('data-host-id');

document.getElementById('host-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const status = document.getElementById('status');
    const body = {
        name: document.getElementById('name').value,
        hostname: document.getElementById('hostname').value,
        ownerUsername: document.getElementById('ownerUsername').value,
        sshEnabled: document.getElementById('sshEnabled').checked,
        sshPort: Number(document.getElementById('sshPort').value),
        rdpEnabled: document.getElementById('rdpEnabled').checked,
        rdpPort: Number(document.getElementById('rdpPort').value),
        vncEnabled: document.getElementById('vncEnabled').checked,
        vncPort: Number(document.getElementById('vncPort').value),
    };
    const url = hostId ? `${basePath}/api/admin/hosts/${hostId}` : `${basePath}/api/admin/hosts`;
    const method = hostId ? 'PUT' : 'POST';
    const response = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        status.textContent = 'Error: ' + await response.text();
        return;
    }
    window.location.href = `${basePath}/admin/hosts`;
});
