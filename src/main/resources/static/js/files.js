const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const containerId = document.body.getAttribute('data-container-id');
const needsCredentials = document.body.getAttribute('data-needs-credentials') === 'true';
// Matches spring.servlet.multipart.max-file-size (application.yml) - keep both in sync.
const MAX_UPLOAD_BYTES = 10 * 1024 * 1024 * 1024;

let currentPath = new URLSearchParams(window.location.search).get('path') || '';
// The connected account's own home directory (a real, resolved absolute path, e.g. /home/frank) -
// only meaningful when needsCredentials is true, set from the connect endpoint's own response.
// Just a sensible place to land right after connecting (matching where a real SFTP client would
// put you), not a browsing-root boundary - browsing itself is unrestricted, see rootPath below.
let homeDirectory = '';

// MANAGED nspawn/podman: root is the container's own rootfs, tracked as "" (empty string) with
// plain relative paths ("Desktop", "Desktop/notes"). SFTP (QEMU/Host, needsCredentials): browsing
// is genuinely unrestricted (not capped at the connecting account's own home directory - see
// RemoteSftpBrowser's own javadoc), tracked as real absolute paths ("/home/ward/Desktop"), same as
// any real SFTP client. rootPath is each mode's own "can't go any higher than this" sentinel.
const rootPath = needsCredentials ? '/' : '';

function joinPath(dir, name) {
    if (!dir) {
        return name;
    }
    return dir === '/' ? `/${name}` : `${dir}/${name}`;
}

function parentPath(path) {
    const idx = path.lastIndexOf('/');
    if (idx === -1) {
        return rootPath;
    }
    // For an absolute path, stripping back to just before the last "/" leaves "" only when the
    // parent is genuinely "/" itself (e.g. parentPath("/home") - idx is 0) - "" isn't a valid
    // absolute path, so normalize it to the real root sentinel instead.
    return needsCredentials && idx === 0 ? '/' : path.substring(0, idx);
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
    // Genuinely accurate for both modes now: MANAGED's root is the container's own filesystem
    // root, and SFTP browsing is unrestricted (not capped at the connecting account's home
    // directory - see RemoteSftpBrowser's own javadoc), so "/" is a real, reachable destination
    // there too, not a misleading label the way a home-directory cap would have made it.
    rootLink.textContent = t('page.files.rootLabel');
    rootLink.addEventListener('click', (e) => { e.preventDefault(); loadDirectory(rootPath); });
    nav.appendChild(rootLink);
    if (path === rootPath || !path) {
        return;
    }
    let built = rootPath;
    path.split('/').filter(Boolean).forEach((segment) => {
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

    if (currentPath !== rootPath) {
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

function showConnectPanel(message) {
    document.getElementById('browse-panel').style.display = 'none';
    document.getElementById('connect-panel').style.display = '';
    document.getElementById('connect-status').textContent = message || '';
    document.getElementById('connected-username').textContent = '';
}

function showBrowsePanel(username) {
    document.getElementById('connect-panel').style.display = 'none';
    document.getElementById('browse-panel').style.display = '';
    if (needsCredentials) {
        document.getElementById('connected-username').textContent = username;
    }
}

/** True (and re-shows the connect prompt) if the response is a 409 - the session credential is
 *  missing or was rejected by the remote target. Callers should stop handling the response
 *  themselves when this returns true. */
function handleDisconnected(response) {
    if (needsCredentials && response.status === 409) {
        homeDirectory = '';
        showConnectPanel(t('page.files.notConnected'));
        return true;
    }
    return false;
}

/** True (and shows an error dialog) if the response is a 403 - the connected account's own OS
 *  permissions don't allow this path. Expected to happen routinely now that SFTP browsing isn't
 *  capped at the connecting account's home directory (another user's home, a root-owned path).
 *  Reuses the server's own message (already clear - "You do not have permission to ..."), same
 *  as every other error path here rather than composing a separate client-side one. */
async function handlePermissionDenied(response) {
    if (response.status === 403) {
        await window.appDialog.alert(await response.text());
        return true;
    }
    return false;
}

async function loadDirectory(path) {
    currentPath = path;
    history.replaceState(null, '', `${basePath}/containers/${containerId}/files?path=${encodeURIComponent(path)}`);
    renderBreadcrumb(path);
    document.getElementById('files-status').textContent = '';
    const response = await fetch(`${basePath}/api/containers/${containerId}/files?path=${encodeURIComponent(path)}`);
    if (handleDisconnected(response)) {
        return;
    }
    if (await handlePermissionDenied(response)) {
        return;
    }
    if (!response.ok) {
        document.getElementById('files-status').textContent = t('page.files.failedToListDirectory', await response.text());
        return;
    }
    renderEntries(await response.json());
}

async function uploadFile(file) {
    if (file.size > MAX_UPLOAD_BYTES) {
        await window.appDialog.alert(t('page.files.tooLarge', file.name, formatBytes(file.size)));
        return;
    }
    const formData = new FormData();
    formData.append('file', file);
    const response = await fetch(
        `${basePath}/api/containers/${containerId}/files?path=${encodeURIComponent(currentPath)}`,
        { method: 'POST', body: formData });
    if (handleDisconnected(response)) {
        return;
    }
    if (await handlePermissionDenied(response)) {
        return;
    }
    if (!response.ok) {
        await window.appDialog.alert(t('page.files.uploadFailed', await response.text()));
        return;
    }
    loadDirectory(currentPath);
}

async function connect() {
    const username = document.getElementById('connect-username').value;
    const password = document.getElementById('connect-password').value;
    if (!username || !password) {
        document.getElementById('connect-status').textContent = t('page.files.enterUsernameAndPassword');
        return;
    }
    document.getElementById('connect-status').textContent = t('page.files.connecting');
    const response = await fetch(`${basePath}/api/containers/${containerId}/files/connect`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password })
    });
    document.getElementById('connect-password').value = '';
    if (!response.ok) {
        document.getElementById('connect-status').textContent = t('page.files.connectionFailed', await response.text());
        return;
    }
    homeDirectory = (await response.json()).homeDirectory;
    showBrowsePanel(username);
    // currentPath is only genuinely unset ("") on a fresh page load with no bookmarked ?path= -
    // land at the real home directory then, same as any real SFTP client would. A resumed session
    // (reconnecting after a 409) keeps whatever absolute path currentPath already held.
    loadDirectory(currentPath || homeDirectory);
}

async function disconnect() {
    await fetch(`${basePath}/api/containers/${containerId}/files/connect`, { method: 'DELETE' });
    homeDirectory = '';
    showConnectPanel();
}

if (needsCredentials) {
    attachPasswordToggle(document.getElementById('connect-password'));
    document.getElementById('btn-connect').addEventListener('click', connect);
    document.getElementById('btn-disconnect').addEventListener('click', disconnect);
    document.getElementById('connect-password').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            connect();
        }
    });
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

if (!needsCredentials) {
    loadDirectory(currentPath);
}
