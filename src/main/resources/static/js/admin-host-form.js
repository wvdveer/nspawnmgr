const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const hostId = document.body.getAttribute('data-host-id');

// No host id yet on the "New host" page - a share can't be granted until the host row exists, so
// clicks on "Add" just queue a username here instead of calling the API immediately. Applied for
// real (one POST per entry) right after the host is created, before navigating away - see the
// submit handler below.
const pendingShares = [];

function renderPendingShare(username) {
    const li = document.createElement('li');
    const span = document.createElement('span');
    span.textContent = username;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn-remove-share btn-danger';
    button.textContent = t('button.remove');
    button.addEventListener('click', () => {
        const index = pendingShares.indexOf(username);
        if (index !== -1) {
            pendingShares.splice(index, 1);
        }
        li.remove();
    });
    li.appendChild(span);
    li.appendChild(button);
    document.getElementById('share-list').appendChild(li);
}

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
        description: document.getElementById('description').value,
        rdpSecurity: document.getElementById('rdp-security-select').value,
        keystrokeButtonPanelPlacement: document.getElementById('keystroke-panel-placement-select').value,
        clipboardCopyLoggingEnabled: document.getElementById('clipboard-copy-logging-enabled').checked,
    };
    const url = hostId ? `${basePath}/api/admin/hosts/${hostId}` : `${basePath}/api/admin/hosts`;
    const method = hostId ? 'PUT' : 'POST';
    const response = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    const result = await response.json();
    for (const username of pendingShares) {
        const shareResponse = await fetch(`${basePath}/api/containers/${result.id}/shares`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username }),
        });
        if (!shareResponse.ok) {
            status.textContent = t('general.failedPrefix', await shareResponse.text());
            return;
        }
    }
    window.location.href = `${basePath}/containers/${result.id}`;
});

document.getElementById('btn-add-share')?.addEventListener('click', async () => {
    const usernameField = document.getElementById('share-username');
    const username = usernameField.value;
    if (!username) {
        return;
    }
    if (!hostId) {
        pendingShares.push(username);
        renderPendingShare(username);
        usernameField.value = '';
        return;
    }
    const response = await fetch(`${basePath}/api/containers/${hostId}/shares`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.querySelectorAll('.btn-remove-share').forEach((button) => {
    button.addEventListener('click', async () => {
        const userId = button.getAttribute('data-user-id');
        const response = await fetch(`${basePath}/api/containers/${hostId}/shares/${userId}`, { method: 'DELETE' });
        if (!response.ok) {
            await window.appDialog.alert(t('general.failedPrefix', await response.text()));
            return;
        }
        window.location.reload();
    });
});
