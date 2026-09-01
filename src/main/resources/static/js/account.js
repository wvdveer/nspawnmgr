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
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    status.style.color = 'var(--success-color, inherit)';
    status.textContent = t('page.account.passwordUpdated');
    document.getElementById('guacamole-password').value = '';
});

document.getElementById('btn-save-language')?.addEventListener('click', async () => {
    const language = document.getElementById('account-language').value;
    const status = document.getElementById('language-status');
    const response = await fetch(`${basePath}/api/account/language`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ language }),
    });
    if (!response.ok) {
        status.style.color = 'var(--error-color)';
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    status.style.color = 'var(--success-color, inherit)';
    status.textContent = t('page.account.languageUpdated');
});
