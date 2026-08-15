const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.getElementById('upload-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const status = document.getElementById('upload-status');
    const fileInput = document.getElementById('file');
    if (!fileInput.files.length) {
        status.textContent = 'Choose a file first.';
        return;
    }
    const formData = new FormData();
    formData.append('packageManager', document.getElementById('packageManager').value);
    formData.append('description', document.getElementById('description').value);
    formData.append('file', fileInput.files[0]);
    status.textContent = 'Uploading...';
    const response = await fetch(`${basePath}/api/admin/packages`, {
        method: 'POST',
        body: formData,
    });
    if (!response.ok) {
        status.textContent = 'Error: ' + await response.text();
        return;
    }
    window.location.reload();
});

document.getElementById('btn-show-auto-cache').addEventListener('click', async () => {
    const status = document.getElementById('auto-cache-status');
    const table = document.getElementById('auto-cache-table');
    const tbody = document.getElementById('auto-cache-tbody');
    const packageManager = document.getElementById('autoCachePackageManager').value;
    tbody.innerHTML = '';
    table.style.display = 'none';
    status.textContent = 'Loading...';
    const response = await fetch(`${basePath}/api/admin/packages/auto-cache?packageManager=${encodeURIComponent(packageManager)}`);
    if (!response.ok) {
        status.textContent = 'Error: ' + await response.text();
        return;
    }
    const files = await response.json();
    if (files.length === 0) {
        status.textContent = 'Nothing cached for ' + packageManager + ' right now.';
        return;
    }
    status.textContent = files.length + ' file(s):';
    for (const f of files) {
        const row = document.createElement('tr');
        const filenameCell = document.createElement('td');
        filenameCell.textContent = f.filename;
        const sizeCell = document.createElement('td');
        sizeCell.textContent = f.sizeBytes;
        row.appendChild(filenameCell);
        row.appendChild(sizeCell);
        tbody.appendChild(row);
    }
    table.style.display = '';
});

document.querySelectorAll('.btn-delete').forEach((button) => {
    button.addEventListener('click', async () => {
        const packageId = button.getAttribute('data-package-id');
        if (!await window.appDialog.confirm('Delete this package from the cache? This cannot be undone.')) {
            return;
        }
        const response = await fetch(`${basePath}/api/admin/packages/${packageId}`, { method: 'DELETE' });
        if (!response.ok) {
            await window.appDialog.alert('Failed: ' + await response.text());
            return;
        }
        window.location.reload();
    });
});
