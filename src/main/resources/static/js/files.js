const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const containerId = document.body.getAttribute('data-container-id');
// Matches spring.servlet.multipart.max-file-size (application.yml) - keep both in sync.
const MAX_UPLOAD_BYTES = 10 * 1024 * 1024 * 1024;

let currentPath = new URLSearchParams(window.location.search).get('path') || '';

function joinPath(dir, name) {
    return dir ? `${dir}/${name}` : name;
}

function parentPath(path) {
    const idx = path.lastIndexOf('/');
    return idx === -1 ? '' : path.substring(0, idx);
}

function formatBytes(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unit = -1;
    do {
        value /= 1024;
        unit++;
    } while (value >= 1024 && unit < units.length - 1);
    return `${value.toFixed(1)} ${units[unit]}`;
}

function renderBreadcrumb(path) {
    const nav = document.getElementById('breadcrumb');
    nav.innerHTML = '';
    const rootLink = document.createElement('a');
    rootLink.href = '#';
    rootLink.textContent = '/ (root)';
    rootLink.addEventListener('click', (e) => { e.preventDefault(); loadDirectory(''); });
    nav.appendChild(rootLink);
    if (!path) {
        return;
    }
    let built = '';
    path.split('/').forEach((segment) => {
        built = joinPath(built, segment);
        const target = built;
        nav.appendChild(document.createTextNode(' / '));
        const link = document.createElement('a');
        link.href = '#';
        link.textContent = segment;
        link.addEventListener('click', (e) => { e.preventDefault(); loadDirectory(target); });
        nav.appendChild(link);
    });
}

function renderEntries(entries) {
    const tbody = document.getElementById('file-tbody');
    tbody.innerHTML = '';

    if (currentPath) {
        const upRow = document.createElement('tr');
        upRow.className = 'file-row-dir';
        const nameCell = document.createElement('td');
        nameCell.textContent = '.. /';
        upRow.appendChild(nameCell);
        upRow.appendChild(document.createElement('td'));
        upRow.appendChild(document.createElement('td'));
        upRow.addEventListener('click', () => loadDirectory(parentPath(currentPath)));
        tbody.appendChild(upRow);
    }

    entries.forEach((entry) => {
        const row = document.createElement('tr');
        const nameCell = document.createElement('td');
        nameCell.textContent = entry.directory ? entry.name + ' /' : entry.name;
        const sizeCell = document.createElement('td');
        sizeCell.textContent = entry.directory ? '' : formatBytes(entry.sizeBytes);
        const modifiedCell = document.createElement('td');
        modifiedCell.textContent = new Date(entry.mtimeEpochSeconds * 1000).toLocaleString();
        row.append(nameCell, sizeCell, modifiedCell);

        if (entry.directory) {
            row.className = 'file-row-dir';
            row.addEventListener('click', () => loadDirectory(joinPath(currentPath, entry.name)));
        } else {
            row.addEventListener('dblclick', () => downloadFile(entry.name));
        }
        tbody.appendChild(row);
    });
}

function downloadFile(name) {
    const path = joinPath(currentPath, name);
    // Plain navigation, not fetch+blob - lets the browser handle the Content-Disposition
    // attachment header natively.
    window.location.href = `${basePath}/api/containers/${containerId}/files/download?path=${encodeURIComponent(path)}`;
}

async function loadDirectory(path) {
    currentPath = path;
    history.replaceState(null, '', `${basePath}/containers/${containerId}/files?path=${encodeURIComponent(path)}`);
    renderBreadcrumb(path);
    document.getElementById('files-status').textContent = '';
    const response = await fetch(`${basePath}/api/containers/${containerId}/files?path=${encodeURIComponent(path)}`);
    if (!response.ok) {
        document.getElementById('files-status').textContent = 'Failed to list directory: ' + await response.text();
        return;
    }
    renderEntries(await response.json());
}

async function uploadFile(file) {
    if (file.size > MAX_UPLOAD_BYTES) {
        await window.appDialog.alert(`"${file.name}" is too large (${formatBytes(file.size)}). The upload limit is 10GB.`);
        return;
    }
    const formData = new FormData();
    formData.append('file', file);
    const response = await fetch(
        `${basePath}/api/containers/${containerId}/files?path=${encodeURIComponent(currentPath)}`,
        { method: 'POST', body: formData });
    if (!response.ok) {
        await window.appDialog.alert('Upload failed: ' + await response.text());
        return;
    }
    loadDirectory(currentPath);
}

const fileTable = document.getElementById('file-table');
fileTable.addEventListener('dragover', (e) => {
    e.preventDefault();
    fileTable.classList.add('drag-over');
});
fileTable.addEventListener('dragleave', () => fileTable.classList.remove('drag-over'));
fileTable.addEventListener('drop', async (e) => {
    e.preventDefault();
    fileTable.classList.remove('drag-over');
    const file = e.dataTransfer.files[0];
    if (!file) {
        return;
    }
    await uploadFile(file);
});

loadDirectory(currentPath);
