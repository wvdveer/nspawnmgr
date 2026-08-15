const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.getElementById('btn-save-guacamole-password')?.addEventListener('click', async () => {
    const password = document.getElementById('guacamole-password').value;
    const status = document.getElementById('guacamole-password-status');
    const response = await fetch(`${basePath}/api/account/guacamole-password`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password }),
    });
    if (!response.ok) {
        status.style.color = 'var(--error-color)';
        status.textContent = 'Failed: ' + await response.text();
        return;
    }
    status.style.color = 'var(--success-color, inherit)';
    status.textContent = 'Password updated.';
    document.getElementById('guacamole-password').value = '';
});
