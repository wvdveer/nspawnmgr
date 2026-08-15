document.getElementById('discover-btn')?.addEventListener('click', async () => {
    const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
    const response = await fetch(`${basePath}/api/containers/discover`, { method: 'POST' });
    if (!response.ok) {
        await window.appDialog.alert('Discovery failed: ' + await response.text());
        return;
    }
    const found = await response.json();
    await window.appDialog.alert(found.length === 0 ? 'No new machines found.' : `Discovered ${found.length}: ${found.map(c => c.name).join(', ')}`);
    if (found.length > 0) {
        window.location.reload();
    }
});
