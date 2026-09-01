const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

// Both sections use the same .btn-approve/.btn-deny classes (see requests.html) - each block
// below is scoped to its own section so a click in one never also fires the other's handler
// (confirmed live during the Phase 4 merge: an unscoped querySelectorAll('.btn-approve') matched
// both tables at once, double-firing and posting to the wrong API with a null id).

document.querySelectorAll('#container-requests-section .btn-approve').forEach((button) => {
    button.addEventListener('click', async () => {
        const containerId = button.getAttribute('data-container-id');
        const passwordInput = document.querySelector(`#container-requests-section .approval-password[data-container-id="${containerId}"]`);
        const sudoPassword = passwordInput.value;
        // Best-effort only: clears this field's value, but Jackson already deserialized the
        // password into its own String on the server before this request even completes.
        passwordInput.value = '';
        const response = await fetch(`${basePath}/api/admin/containers/${containerId}/approve`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sudoPassword }),
        });
        if (!response.ok) {
            await window.appDialog.alert(t('general.failedPrefix', await response.text()));
            return;
        }
        window.location.reload();
    });
});

document.querySelectorAll('#container-requests-section .btn-deny').forEach((button) => {
    button.addEventListener('click', async () => {
        const containerId = button.getAttribute('data-container-id');
        if (!await window.appDialog.confirm(t('page.requests.confirmDenyContainer'))) {
            return;
        }
        // /api/requests/... (not /api/admin/...) - denying, unlike approving, is open to any
        // authenticated user for their own requests; the server enforces the ownership check.
        const response = await fetch(`${basePath}/api/requests/containers/${containerId}/deny`, { method: 'POST' });
        if (!response.ok) {
            await window.appDialog.alert(t('general.failedPrefix', await response.text()));
            return;
        }
        window.location.reload();
    });
});

document.querySelectorAll('#user-requests-section .btn-approve').forEach((button) => {
    button.addEventListener('click', async () => {
        const requestId = button.getAttribute('data-request-id');
        // The password column only renders at all when sshApprovalRequired is true (see
        // requests.html) — a request pending only because its container wasn't running doesn't
        // need one, since approving just falls back to the stored sudo secret.
        const passwordInput = document.querySelector(`#user-requests-section .approval-password[data-request-id="${requestId}"]`);
        const sudoPassword = passwordInput ? passwordInput.value : null;
        if (passwordInput) {
            passwordInput.value = '';
        }
        const response = await fetch(`${basePath}/api/admin/container-user-requests/${requestId}/approve`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sudoPassword }),
        });
        if (!response.ok) {
            await window.appDialog.alert(t('general.failedPrefix', await response.text()));
            return;
        }
        window.location.reload();
    });
});

document.querySelectorAll('#user-requests-section .btn-deny').forEach((button) => {
    button.addEventListener('click', async () => {
        const requestId = button.getAttribute('data-request-id');
        if (!await window.appDialog.confirm(t('page.requests.confirmDenyRequest'))) {
            return;
        }
        const response = await fetch(`${basePath}/api/requests/container-user-requests/${requestId}/deny`, { method: 'POST' });
        if (!response.ok) {
            await window.appDialog.alert(t('general.failedPrefix', await response.text()));
            return;
        }
        window.location.reload();
    });
});
