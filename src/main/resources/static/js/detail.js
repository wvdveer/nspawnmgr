const containerId = document.body.getAttribute('data-container-id');
const containerName = document.body.getAttribute('data-container-name');
const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

async function post(path) {
    const response = await fetch(`${basePath}/api/containers/${containerId}${path}`, { method: 'POST' });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
}

document.getElementById('btn-start')?.addEventListener('click', () => post('/start'));
document.getElementById('btn-stop')?.addEventListener('click', () => post('/stop'));
document.getElementById('btn-restart')?.addEventListener('click', () => post('/restart'));
document.getElementById('btn-pause')?.addEventListener('click', () => post('/pause'));
document.getElementById('btn-resume')?.addEventListener('click', () => post('/resume'));
document.getElementById('btn-force-stop')?.addEventListener('click', () => post('/force-stop'));

document.getElementById('btn-create-template')?.addEventListener('click', async () => {
    const status = document.getElementById('create-template-status');
    const templateName = document.getElementById('create-template-name').value.trim();
    const description = document.getElementById('create-template-description').value;
    if (!templateName) {
        status.textContent = t('page.detail.enterTemplateNameFirst');
        return;
    }
    status.textContent = t('js.status.creating');
    const response = await fetch(`${basePath}/api/containers/${containerId}/create-template`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ templateName, description }),
    });
    if (!response.ok) {
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    status.textContent = t('page.detail.templateCreated', templateName);
});

document.getElementById('btn-take-ownership')?.addEventListener('click', async () => {
    if (!await window.appDialog.confirm(t('page.detail.confirmTakeOwnership'))) {
        return;
    }
    post('/take-ownership');
});

document.getElementById('btn-delete-host')?.addEventListener('click', async () => {
    if (!await window.appDialog.confirm(t('page.detail.confirmDeleteHost'))) {
        return;
    }
    const response = await fetch(`${basePath}/api/admin/hosts/${containerId}`, { method: 'DELETE' });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.href = `${basePath}/`;
});

document.getElementById('btn-add-ssh-credential')?.addEventListener('click', async () => {
    const accountName = document.getElementById('ssh-credential-account').value;
    const privateKeyPem = document.getElementById('ssh-credential-key').value;
    const response = await fetch(`${basePath}/api/containers/${containerId}/credentials/ssh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ accountName, privateKeyPem }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.getElementById('btn-enable-ssh')?.addEventListener('click', () => post('/access/ssh'));
document.getElementById('btn-enable-rdp')?.addEventListener('click', () => post('/access/rdp'));
document.getElementById('btn-enable-vnc')?.addEventListener('click', () => post('/access/vnc'));

async function deleteAccess(protocol) {
    const response = await fetch(`${basePath}/api/containers/${containerId}/access/${protocol}`, { method: 'DELETE' });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
}
document.getElementById('btn-disable-ssh')?.addEventListener('click', () => deleteAccess('ssh'));
document.getElementById('btn-disable-rdp')?.addEventListener('click', () => deleteAccess('rdp'));
document.getElementById('btn-disable-vnc')?.addEventListener('click', () => deleteAccess('vnc'));

document.getElementById('btn-session-ssh')?.addEventListener('click', () => {
    window.location.href = `${basePath}/containers/${containerName}/session/ssh`;
});
document.getElementById('btn-session-rdp')?.addEventListener('click', () => {
    window.location.href = `${basePath}/containers/${containerName}/session/rdp`;
});
document.getElementById('btn-session-vnc')?.addEventListener('click', () => {
    window.location.href = `${basePath}/containers/${containerName}/session/vnc`;
});

// Reloads once the container's state changes (e.g. BOOTING -> RUNNING) so the Connect buttons and
// State line update without the admin having to manually refresh.
const initialContainerState = document.body.getAttribute('data-container-state');
setInterval(async () => {
    const response = await fetch(`${basePath}/api/containers/${containerId}/status`);
    if (!response.ok) {
        return;
    }
    const status = await response.json();
    if (status.state !== initialContainerState) {
        window.location.reload();
    }
}, 10000);

document.getElementById('btn-delete')?.addEventListener('click', async () => {
    if (!await window.appDialog.confirm(t('page.detail.confirmDeleteContainer'))) {
        return;
    }
    const response = await fetch(`${basePath}/api/containers/${containerId}`, { method: 'DELETE' });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.href = `${basePath}/`;
});

document.getElementById('btn-save-description')?.addEventListener('click', async () => {
    const description = document.getElementById('description').value;
    const response = await fetch(`${basePath}/api/containers/${containerId}/description`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ description }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.getElementById('btn-save-pod-command')?.addEventListener('click', async () => {
    const command = document.getElementById('pod-command').value;
    const button = document.getElementById('btn-save-pod-command');
    button.disabled = true;
    const response = await fetch(`${basePath}/api/containers/${containerId}/pod-command`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ command }),
    });
    if (!response.ok) {
        button.disabled = false;
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.getElementById('btn-save-package-manager')?.addEventListener('click', async () => {
    const packageManager = document.getElementById('package-manager-select').value;
    const response = await fetch(`${basePath}/api/containers/${containerId}/package-manager`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ packageManager }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.getElementById('btn-add-share')?.addEventListener('click', async () => {
    const username = document.getElementById('share-username').value;
    const response = await fetch(`${basePath}/api/containers/${containerId}/shares`, {
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
        const response = await fetch(`${basePath}/api/containers/${containerId}/shares/${userId}`, { method: 'DELETE' });
        if (!response.ok) {
            await window.appDialog.alert(t('general.failedPrefix', await response.text()));
            return;
        }
        window.location.reload();
    });
});

document.getElementById('btn-add-port-mapping')?.addEventListener('click', async () => {
    const hostPort = parseInt(document.getElementById('port-mapping-host-port').value, 10);
    const containerPort = parseInt(document.getElementById('port-mapping-container-port').value, 10);
    const protocol = document.getElementById('port-mapping-protocol').value;
    const response = await fetch(`${basePath}/api/containers/${containerId}/port-mappings`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ hostPort, containerPort, protocol }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.querySelectorAll('.btn-remove-port-mapping').forEach((button) => {
    button.addEventListener('click', async () => {
        const mappingId = button.getAttribute('data-mapping-id');
        const response = await fetch(`${basePath}/api/containers/${containerId}/port-mappings/${mappingId}`, { method: 'DELETE' });
        if (!response.ok) {
            await window.appDialog.alert(t('general.failedPrefix', await response.text()));
            return;
        }
        window.location.reload();
    });
});

document.getElementById('btn-save-rdp-security')?.addEventListener('click', async () => {
    const security = document.getElementById('rdp-security-select').value;
    const response = await fetch(`${basePath}/api/containers/${containerId}/rdp-security`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ security }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.getElementById('btn-save-pam-auth')?.addEventListener('click', async () => {
    const source = document.getElementById('pam-auth-source-select').value;
    const services = [...document.querySelectorAll('.pam-auth-service-checkbox:checked')].map(cb => cb.value);
    const response = await fetch(`${basePath}/api/containers/${containerId}/pam-auth`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ source, services }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.getElementById('btn-save-outbound')?.addEventListener('click', async () => {
    const enabled = document.getElementById('outbound-enabled').checked;
    const response = await fetch(`${basePath}/api/containers/${containerId}/outbound`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.getElementById('btn-add-outbound-allowlist')?.addEventListener('click', async () => {
    const destinationHost = document.getElementById('outbound-allowlist-host').value;
    const destinationPort = parseInt(document.getElementById('outbound-allowlist-port').value, 10);
    const protocol = document.getElementById('outbound-allowlist-protocol').value;
    const response = await fetch(`${basePath}/api/containers/${containerId}/outbound/allowlist`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ destinationHost, destinationPort, protocol }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.querySelectorAll('.btn-remove-outbound-allowlist').forEach((button) => {
    button.addEventListener('click', async () => {
        const entryId = button.getAttribute('data-entry-id');
        const response = await fetch(`${basePath}/api/containers/${containerId}/outbound/allowlist/${entryId}`, { method: 'DELETE' });
        if (!response.ok) {
            await window.appDialog.alert(t('general.failedPrefix', await response.text()));
            return;
        }
        window.location.reload();
    });
});

const containerUserList = document.getElementById('container-user-list');
if (containerUserList) {
    attachPasswordToggle(document.getElementById('new-container-user-password'));

    async function renderUsers() {
        const response = await fetch(`${basePath}/api/containers/${containerId}/users`);
        if (!response.ok) {
            containerUserList.innerHTML = '';
            document.getElementById('container-users-status').textContent = t('general.failedPrefix', await response.text());
            return;
        }
        const usernames = await response.json();
        containerUserList.innerHTML = '';
        usernames.forEach((username) => {
            const li = document.createElement('li');
            const isPrimary = username === containerUserList.dataset.primaryAccount;
            const label = document.createElement('span');
            label.textContent = username + (isPrimary ? t('page.detail.primarySuffix') : '');
            const passwordInput = document.createElement('input');
            passwordInput.type = 'password';
            passwordInput.placeholder = t('page.detail.newPasswordPlaceholder');
            const changeButton = document.createElement('button');
            changeButton.className = 'btn-primary';
            changeButton.textContent = t('button.changePassword');
            changeButton.addEventListener('click', async () => {
                const password = passwordInput.value;
                passwordInput.value = '';
                const changeResponse = await fetch(`${basePath}/api/containers/${containerId}/users/${username}/password`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ password }),
                });
                if (!changeResponse.ok) {
                    await window.appDialog.alert(t('general.failedPrefix', await changeResponse.text()));
                    return;
                }
                const result = await changeResponse.json();
                showUsersStatus(result.pending
                    ? t('page.detail.passwordChangePending', username)
                    : t('page.detail.passwordChanged', username));
                renderUsers();
            });
            const primaryButton = document.createElement('button');
            primaryButton.className = 'btn-primary';
            primaryButton.textContent = t('button.makePrimary');
            primaryButton.disabled = isPrimary;
            primaryButton.addEventListener('click', async () => {
                const primaryResponse = await fetch(`${basePath}/api/containers/${containerId}/primary-account`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ accountName: username }),
                });
                if (!primaryResponse.ok) {
                    await window.appDialog.alert(t('general.failedPrefix', await primaryResponse.text()));
                    return;
                }
                const result = await primaryResponse.json();
                if (!result.pending) {
                    containerUserList.dataset.primaryAccount = username;
                }
                showUsersStatus(result.pending
                    ? t('page.detail.primarySwitchPending', username)
                    : t('page.detail.primarySwitched', username));
                renderUsers();
            });
            li.append(label, passwordInput, changeButton, primaryButton);
            attachPasswordToggle(passwordInput);
            containerUserList.appendChild(li);
        });
    }

    function showUsersStatus(message) {
        document.getElementById('container-users-status').textContent = message;
    }

    document.getElementById('btn-add-container-user')?.addEventListener('click', async () => {
        const usernameInput = document.getElementById('new-container-username');
        const passwordInput = document.getElementById('new-container-user-password');
        const username = usernameInput.value;
        const password = passwordInput.value;
        const response = await fetch(`${basePath}/api/containers/${containerId}/users`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password }),
        });
        if (!response.ok) {
            await window.appDialog.alert(t('general.failedPrefix', await response.text()));
            return;
        }
        usernameInput.value = '';
        passwordInput.value = '';
        const result = await response.json();
        showUsersStatus(result.pending
            ? t('page.detail.userAddPending', username)
            : t('page.detail.userAdded', username));
        renderUsers();
    });

    renderUsers();
}

document.getElementById('btn-install-package')?.addEventListener('click', async () => {
    const select = document.getElementById('install-package-select');
    const output = document.getElementById('install-package-output');
    const button = document.getElementById('btn-install-package');
    const cachedPackageId = select.value;
    button.disabled = true;
    output.textContent = t('js.status.installing');
    const response = await fetch(`${basePath}/api/containers/${containerId}/packages/${cachedPackageId}/install`, {
        method: 'POST',
    });
    button.disabled = false;
    if (!response.ok) {
        output.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    const result = await response.json();
    output.textContent = t('page.detail.installOutput', result.exitCode, result.stdout, result.stderr);
});

document.getElementById('btn-mount-iso')?.addEventListener('click', () => {
    const isoId = document.getElementById('mount-iso-select').value;
    post(`/iso/${isoId}/mount`);
});

document.getElementById('btn-eject-iso')?.addEventListener('click', async () => {
    const response = await fetch(`${basePath}/api/containers/${containerId}/iso`, { method: 'DELETE' });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

const bootAutoStartCheckbox = document.getElementById('boot-auto-start');
const bootRequiresField = document.getElementById('boot-requires-field');
function syncBootRequiresFieldVisibility() {
    if (bootAutoStartCheckbox && bootRequiresField) {
        bootRequiresField.style.display = bootAutoStartCheckbox.checked ? '' : 'none';
    }
}
syncBootRequiresFieldVisibility();
bootAutoStartCheckbox?.addEventListener('change', syncBootRequiresFieldVisibility);

document.getElementById('btn-save-boot-settings')?.addEventListener('click', async () => {
    const autoStart = document.getElementById('boot-auto-start').checked;
    const requiresContainerName = document.getElementById('boot-requires-select').value || null;
    const response = await fetch(`${basePath}/api/containers/${containerId}/boot-settings`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ autoStart, requiresContainerName }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});
