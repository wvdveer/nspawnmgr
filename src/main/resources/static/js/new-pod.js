const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.getElementById('create-pod-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const status = document.getElementById('status');
    const body = {
        name: document.getElementById('name').value,
        templateId: Number(document.getElementById('templateId').value),
        rdpEnabled: false,
        vncEnabled: false,
        desktopManager: 'NONE',
        description: document.getElementById('description').value,
        command: document.getElementById('command').value,
    };
    const response = await fetch(`${basePath}/api/containers`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    const created = await response.json();
    window.location.href = `${basePath}/containers/${created.id}`;
});
