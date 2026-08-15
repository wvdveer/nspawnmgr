const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.querySelectorAll('.btn-approve').forEach((button) => {
    button.addEventListener('click', async () => {
        const requestId = button.getAttribute('data-request-id');
        // The password column only renders at all when sshApprovalRequired is true (see
        // pending-user-requests.html) — a request pending only because its container wasn't
        // running doesn't need one, since approving just falls back to the stored sudo secret.
        const passwordInput = document.querySelector(`.approval-password[data-request-id="${requestId}"]`);
        const sudoPassword = passwordInput ? passwordInput.value : null;
        // Best-effort only: clears this field's value, but Jackson already deserialized the
        // password into its own String on the server before this request even completes.
        if (passwordInput) {
            passwordInput.value = '';
        }
        const response = await fetch(`${basePath}/api/admin/container-user-requests/${requestId}/approve`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sudoPassword }),
        });
        if (!response.ok) {
            await window.appDialog.alert('Failed: ' + await response.text());
            return;
        }
        window.location.reload();
    });
});

document.querySelectorAll('.btn-deny').forEach((button) => {
    button.addEventListener('click', async () => {
        const requestId = button.getAttribute('data-request-id');
        if (!await window.appDialog.confirm('Deny this request?')) {
            return;
        }
        const response = await fetch(`${basePath}/api/admin/container-user-requests/${requestId}/deny`, { method: 'POST' });
        if (!response.ok) {
            await window.appDialog.alert('Failed: ' + await response.text());
            return;
        }
        window.location.reload();
    });
});
