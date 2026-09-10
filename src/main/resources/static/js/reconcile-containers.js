document.getElementById('reconcile-btn')?.addEventListener('click', async () => {
    const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
    const response = await fetch(`${basePath}/api/containers/reconcile`, { method: 'POST' });
    if (!response.ok) {
        await window.appDialog.alert(t('page.reconcile.failedPrefix', await response.text()));
        return;
    }
    const corrected = await response.json();
    await window.appDialog.alert(corrected.length === 0 ? t('page.reconcile.noneFound') : t('page.reconcile.found', corrected.length, corrected.map(c => c.name).join(', ')));
    if (corrected.length > 0) {
        window.location.reload();
    }
});
