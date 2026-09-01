const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

// Best-effort filename-vs-package-manager sanity check, not enforced server-side - a mismatch is
// exactly the kind of easy mistake the package-manager dropdown invites (nothing else ties the two
// together), so this is a confirm-to-proceed warning, not a hard block: an admin's file might
// genuinely just be named unconventionally.
const EXPECTED_EXTENSIONS = {
    APT: ['.deb'],
    DNF: ['.rpm'],
    PACMAN: ['.pkg.tar.zst', '.pkg.tar.xz', '.pkg.tar.gz'],
    APK: ['.apk'],
    ISO: ['.iso'],
};

function filenameLooksWrongForPackageManager(filename, packageManager) {
    const expected = EXPECTED_EXTENSIONS[packageManager];
    if (!expected) {
        return false;
    }
    const lower = filename.toLowerCase();
    return !expected.some((ext) => lower.endsWith(ext));
}

async function confirmExtensionMismatchOrAbort(filename, packageManager) {
    if (!filenameLooksWrongForPackageManager(filename, packageManager)) {
        return true;
    }
    const expectedList = EXPECTED_EXTENSIONS[packageManager].join(' or ');
    return window.appDialog.confirm(t('page.packageForm.extensionMismatchConfirm', filename, packageManager, expectedList));
}

// Matches spring.servlet.multipart.max-file-size (application.yml) - keep both in sync. Same
// pre-flight-rejection pattern files.js already uses, so an obviously-oversized file is rejected
// immediately instead of only after a (possibly very slow) failed upload attempt.
const MAX_UPLOAD_BYTES = 10 * 1024 * 1024 * 1024;

document.getElementById('upload-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const status = document.getElementById('upload-status');
    const progress = document.getElementById('upload-progress');
    const bar = document.getElementById('upload-progress-bar');
    const fileInput = document.getElementById('file');
    if (!fileInput.files.length) {
        status.textContent = t('page.packageForm.chooseFileFirst');
        return;
    }
    if (fileInput.files[0].size > MAX_UPLOAD_BYTES) {
        status.textContent = t('page.packageForm.tooLarge', fileInput.files[0].name);
        return;
    }
    const packageManager = document.getElementById('packageManager').value;
    if (!await confirmExtensionMismatchOrAbort(fileInput.files[0].name, packageManager)) {
        return;
    }
    const formData = new FormData();
    formData.append('packageManager', packageManager);
    formData.append('description', document.getElementById('description').value);
    formData.append('file', fileInput.files[0]);

    document.getElementById('btn-start-upload').disabled = true;
    progress.style.display = '';
    bar.value = 0;
    status.textContent = t('page.packageForm.uploading');

    // XMLHttpRequest, not fetch() - fetch has no upload-progress event, only XHR's
    // xhr.upload.onprogress reports bytes sent to Tomcat as they go.
    const xhr = new XMLHttpRequest();
    xhr.upload.addEventListener('progress', (e) => {
        if (!e.lengthComputable) {
            return;
        }
        const percent = Math.round((e.loaded / e.total) * 100);
        bar.value = percent;
        status.textContent = `${formatBytes(e.loaded)} / ${formatBytes(e.total)} (${percent}%)`;
    });
    xhr.upload.addEventListener('load', () => {
        // All bytes are in Tomcat's hands now, but the server still has to stream them on to the
        // real host over SSH before it responds - no per-byte visibility into that phase from here.
        bar.removeAttribute('value');
        status.textContent = t('page.packageForm.finishing');
    });
    xhr.addEventListener('load', () => {
        if (xhr.status >= 200 && xhr.status < 300) {
            window.location.href = `${basePath}/admin/packages`;
            return;
        }
        document.getElementById('btn-start-upload').disabled = false;
        status.textContent = t('general.failedPrefix', xhr.responseText);
    });
    xhr.addEventListener('error', () => {
        document.getElementById('btn-start-upload').disabled = false;
        status.textContent = t('page.packageForm.uploadFailedGeneric');
    });
    xhr.open('POST', `${basePath}/api/admin/packages`);
    xhr.send(formData);
});

document.getElementById('mode-upload').addEventListener('change', () => setAddMode('upload'));
document.getElementById('mode-download').addEventListener('change', () => setAddMode('download'));

function setAddMode(mode) {
    document.getElementById('upload-form').style.display = mode === 'upload' ? '' : 'none';
    document.getElementById('download-form').style.display = mode === 'download' ? '' : 'none';
    document.getElementById('upload-status').textContent = '';
    if (mode === 'upload') {
        document.getElementById('download-progress').style.display = 'none';
    } else {
        document.getElementById('upload-progress').style.display = 'none';
    }
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

let downloadPollHandle = null;

document.getElementById('download-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const status = document.getElementById('download-status');
    const url = document.getElementById('download-url').value;
    const packageManager = document.getElementById('download-packageManager').value;
    // Mirrors PackageDownloadService.filenameFromUrl()'s own "last path segment" heuristic - close
    // enough for a best-effort warning, doesn't need to be exact.
    const urlFilename = url.split('?')[0].split('/').filter(Boolean).pop() || url;
    if (!await confirmExtensionMismatchOrAbort(urlFilename, packageManager)) {
        return;
    }
    const body = {
        packageManager: packageManager,
        url: url,
        description: document.getElementById('download-description').value,
    };
    const response = await fetch(`${basePath}/api/admin/packages/download`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        document.getElementById('download-progress').style.display = '';
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    const { downloadId } = await response.json();
    status.setAttribute('data-download-id', downloadId);
    document.getElementById('btn-start-download').disabled = true;
    document.getElementById('btn-abort-download').style.display = '';
    document.getElementById('download-progress').style.display = '';
    document.getElementById('download-progress-bar').removeAttribute('value');
    status.textContent = t('page.packageForm.starting');
    downloadPollHandle = setInterval(() => pollDownloadStatus(downloadId), 1000);
});

document.getElementById('btn-abort-download').addEventListener('click', async () => {
    const downloadId = document.getElementById('download-status').getAttribute('data-download-id');
    if (!downloadId || !await window.appDialog.confirm(t('page.packageForm.confirmAbortDownload'))) {
        return;
    }
    await fetch(`${basePath}/api/admin/packages/download/${downloadId}/abort`, { method: 'POST' });
});

async function pollDownloadStatus(downloadId) {
    const status = document.getElementById('download-status');
    const response = await fetch(`${basePath}/api/admin/packages/download/${downloadId}`);
    if (!response.ok) {
        return;
    }
    const result = await response.json();
    const bar = document.getElementById('download-progress-bar');
    if (result.totalBytes) {
        const percent = Math.min(100, Math.round((result.bytesDownloaded / result.totalBytes) * 100));
        bar.value = percent;
        status.textContent = `${formatBytes(result.bytesDownloaded)} / ${formatBytes(result.totalBytes)} (${percent}%)`;
    } else {
        bar.removeAttribute('value');
        status.textContent = t('page.packageForm.downloadedSuffix', formatBytes(result.bytesDownloaded));
    }
    if (result.state === 'RUNNING') {
        return;
    }
    clearInterval(downloadPollHandle);
    downloadPollHandle = null;
    document.getElementById('btn-start-download').disabled = false;
    document.getElementById('btn-abort-download').style.display = 'none';
    if (result.state === 'COMPLETED') {
        window.location.href = `${basePath}/admin/packages`;
        return;
    }
    if (result.state === 'ABORTED') {
        status.textContent = t('js.status.aborted');
        return;
    }
    status.textContent = t('general.failedPrefix', result.errorMessage || t('page.packageForm.downloadFailedDefault'));
}
