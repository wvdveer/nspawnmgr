const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.querySelectorAll('.btn-delete').forEach((button) => {
    button.addEventListener('click', async () => {
        const hostId = button.getAttribute('data-host-id');
        if (!await window.appDialog.confirm('Delete this host? This cannot be undone.')) {
            return;
        }
        const response = await fetch(`${basePath}/api/admin/hosts/${hostId}`, { method: 'DELETE' });
        if (!response.ok) {
            await window.appDialog.alert('Failed: ' + await response.text());
            return;
        }
        window.location.reload();
    });
});
