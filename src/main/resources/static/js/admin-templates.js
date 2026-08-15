const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.querySelectorAll('.btn-create-minimal').forEach((button) => {
    button.addEventListener('click', async () => {
        const flavor = button.getAttribute('data-flavor');
        const status = document.getElementById('create-minimal-status');
        const sudoApprovalRequired = document.body.getAttribute('data-sudo-approval-required') === 'true';
        const passwordField = document.getElementById('minimalSudoPassword');
        if (sudoApprovalRequired && (!passwordField || !passwordField.value)) {
            status.textContent = 'Enter the sudo password above first.';
            return;
        }
        button.disabled = true;
        status.textContent = `Downloading ${flavor} minirootfs and installing openssh-server — this can take a while...`;
        const response = await fetch(`${basePath}/api/admin/templates/create-${flavor.replace('-minimal', '')}-minimal`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sudoPassword: passwordField ? passwordField.value : null }),
        });
        if (!response.ok) {
            status.textContent = 'Failed: ' + await response.text();
            button.disabled = false;
            return;
        }
        window.location.reload();
    });
});

document.querySelectorAll('.btn-deactivate').forEach((button) => {
    button.addEventListener('click', async () => {
        const templateId = button.getAttribute('data-template-id');
        const response = await fetch(`${basePath}/api/admin/templates/${templateId}/deactivate`, { method: 'POST' });
        if (!response.ok) {
            await window.appDialog.alert('Failed: ' + await response.text());
            return;
        }
        window.location.reload();
    });
});

document.querySelectorAll('.btn-reactivate').forEach((button) => {
    button.addEventListener('click', async () => {
        const templateId = button.getAttribute('data-template-id');
        const response = await fetch(`${basePath}/api/admin/templates/${templateId}/reactivate`, { method: 'POST' });
        if (!response.ok) {
            await window.appDialog.alert('Failed: ' + await response.text());
            return;
        }
        window.location.reload();
    });
});

document.querySelectorAll('.btn-delete').forEach((button) => {
    button.addEventListener('click', async () => {
        const templateId = button.getAttribute('data-template-id');
        if (!await window.appDialog.confirm('Delete this template? This cannot be undone.')) {
            return;
        }
        const response = await fetch(`${basePath}/api/admin/templates/${templateId}`, { method: 'DELETE' });
        if (!response.ok) {
            await window.appDialog.alert('Failed: ' + await response.text());
            return;
        }
        window.location.reload();
    });
});
