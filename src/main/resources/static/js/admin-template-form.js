const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const templateId = document.body.getAttribute('data-template-id');

// Best-effort suggestions only, from TEMPLATES_DIR/<backend>'s already-present .tar.gz files - if
// this fails (no host reachable, empty directory, etc.) sourcePath stays a plain text field the
// admin can still type into, so no error handling beyond "leave the datalist empty" is needed.
async function refreshSourcePathOptions() {
    const datalist = document.getElementById('sourcePathOptions');
    datalist.innerHTML = '';
    const backend = document.getElementById('backend').value;
    try {
        const response = await fetch(`${basePath}/api/admin/templates/available-source-files?backend=${encodeURIComponent(backend)}`);
        if (!response.ok) {
            return;
        }
        const names = await response.json();
        for (const name of names) {
            const option = document.createElement('option');
            option.value = name;
            datalist.appendChild(option);
        }
    } catch (e) {
        // Network/host unreachable - leave the datalist empty, see comment above.
    }
}
document.getElementById('backend').addEventListener('change', refreshSourcePathOptions);
refreshSourcePathOptions();

document.getElementById('template-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const status = document.getElementById('status');
    const body = {
        name: document.getElementById('name').value,
        description: document.getElementById('description').value,
        sourcePath: document.getElementById('sourcePath').value,
        backend: document.getElementById('backend').value,
        packageManager: document.getElementById('packageManager').value,
        installSshCommand: document.getElementById('installSshCommand').value,
        sshPreinstalled: document.getElementById('sshPreinstalled').checked,
        sshPreDownloadPackages: document.getElementById('sshPreDownloadPackages').value,
        installXrdpCommand: document.getElementById('installXrdpCommand').value,
        rdpCapable: document.getElementById('rdpCapable').checked,
        xrdpPreDownloadPackages: document.getElementById('xrdpPreDownloadPackages').value,
        installVncCommand: document.getElementById('installVncCommand').value,
        vncCapable: document.getElementById('vncCapable').checked,
        vncPreDownloadPackages: document.getElementById('vncPreDownloadPackages').value,
        vncXstartupTemplate: document.getElementById('vncXstartupTemplate').value,
        vncProcessNamePattern: document.getElementById('vncProcessNamePattern').value,
        installGnomeCommand: document.getElementById('installGnomeCommand').value,
        gnomePreDownloadPackages: document.getElementById('gnomePreDownloadPackages').value,
        installKdeStandardCommand: document.getElementById('installKdeStandardCommand').value,
        kdeStandardPreDownloadPackages: document.getElementById('kdeStandardPreDownloadPackages').value,
        installXfce4Command: document.getElementById('installXfce4Command').value,
        xfce4PreDownloadPackages: document.getElementById('xfce4PreDownloadPackages').value,
        privateUsersMode: document.getElementById('privateUsersMode').value,
        active: document.getElementById('active').checked,
    };
    const url = templateId ? `${basePath}/api/admin/templates/${templateId}` : `${basePath}/api/admin/templates`;
    const method = templateId ? 'PUT' : 'POST';
    const response = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        status.textContent = 'Error: ' + await response.text();
        return;
    }
    window.location.href = `${basePath}/admin/templates`;
});
