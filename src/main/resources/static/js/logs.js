const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

async function loadTail() {
    const content = document.getElementById('log-content');
    const response = await fetch(`${basePath}/api/logs/tail?lines=100`);
    if (!response.ok) {
        content.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    const lines = await response.json();
    content.textContent = lines.join('\n');
}

document.getElementById('btn-show-full')?.addEventListener('click', () => {
    window.location.href = `${basePath}/logs/full`;
});

loadTail();
