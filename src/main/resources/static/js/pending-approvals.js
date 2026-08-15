const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.querySelectorAll('.btn-approve').forEach((button) => {
    button.addEventListener('click', async () => {
        const containerId = button.getAttribute('data-container-id');
        const passwordInput = document.querySelector(`.approval-password[data-container-id="${containerId}"]`);
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
            await window.appDialog.alert('Failed: ' + await response.text());
            return;
        }
        window.location.reload();
    });
});

document.querySelectorAll('.btn-deny').forEach((button) => {
    button.addEventListener('click', async () => {
        const containerId = button.getAttribute('data-container-id');
        if (!await window.appDialog.confirm('Deny this container request?')) {
            return;
        }
        const response = await fetch(`${basePath}/api/admin/containers/${containerId}/deny`, { method: 'POST' });
        if (!response.ok) {
            await window.appDialog.alert('Failed: ' + await response.text());
            return;
        }
        window.location.reload();
    });
});
