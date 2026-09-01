const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const isAdmin = document.body.getAttribute('data-is-admin') === 'true';
const selectedFile = new URLSearchParams(window.location.search).get('file');

async function loadMainContent() {
    const content = document.getElementById('log-content');
    const title = document.getElementById('shell-title');
    const downloadLink = document.getElementById('btn-download');
    let url;
    if (selectedFile) {
        title.textContent = t('page.logsFull.logColonFile', selectedFile);
        url = `${basePath}/api/logs/rotated/${encodeURIComponent(selectedFile)}`;
        downloadLink.style.display = 'none';
    } else {
        title.textContent = t('page.logsFull.fullLogCurrent');
        url = `${basePath}/api/logs/current`;
        downloadLink.href = `${basePath}/api/logs/current/download`;
    }
    const response = await fetch(url);
    if (!response.ok) {
        content.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    content.textContent = await response.text();
}

async function loadRotatedList() {
    const tbody = document.getElementById('rotated-log-list');
    const response = await fetch(`${basePath}/api/logs/rotated`);
    if (!response.ok) {
        return;
    }
    const filenames = await response.json();
    tbody.innerHTML = '';
    filenames.forEach((filename) => {
        const row = document.createElement('tr');
        const nameCell = document.createElement('td');
        nameCell.textContent = filename;
        const actionsCell = document.createElement('td');

        const viewButton = document.createElement('button');
        viewButton.className = 'btn-primary';
        viewButton.textContent = t('button.view');
        viewButton.addEventListener('click', () => {
            window.location.href = `${basePath}/logs/full?file=${encodeURIComponent(filename)}`;
        });
        actionsCell.appendChild(viewButton);

        if (isAdmin) {
            const deleteButton = document.createElement('button');
            deleteButton.className = 'btn-danger';
            deleteButton.textContent = t('button.delete');
            deleteButton.addEventListener('click', async () => {
                if (!await window.appDialog.confirm(t('page.logsFull.confirmDelete', filename))) {
                    return;
                }
                const deleteResponse = await fetch(`${basePath}/api/logs/rotated/${encodeURIComponent(filename)}`, { method: 'DELETE' });
                if (!deleteResponse.ok) {
                    await window.appDialog.alert(t('general.failedPrefix', await deleteResponse.text()));
                    return;
                }
                loadRotatedList();
                if (selectedFile === filename) {
                    window.location.href = `${basePath}/logs/full`;
                }
            });
            actionsCell.appendChild(deleteButton);
        }

        row.append(nameCell, actionsCell);
        tbody.appendChild(row);
    });
}

loadMainContent();
loadRotatedList();
