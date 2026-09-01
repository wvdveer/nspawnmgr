(function () {
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

// Fields whose value only means anything if nspawnmgr knows how to install packages via this
// template - disabled (not cleared) when no package manager is selected, same "leave the data
// alone, just stop the admin editing it" posture as create.js's own rdpEnabledCheckbox.disabled.
const PACKAGE_MANAGER_DEPENDENT_FIELD_IDS = [
    'installSshCommand', 'sshPreDownloadPackages',
    'installXrdpCommand', 'xrdpPreDownloadPackages',
    'installVncCommand', 'vncPreDownloadPackages',
    'installGnomeCommand', 'gnomePreDownloadPackages',
    'installKdeStandardCommand', 'kdeStandardPreDownloadPackages',
    'installXfce4Command', 'xfce4PreDownloadPackages',
];
function syncPackageManagerDependentFields() {
    const hasPackageManager = document.getElementById('packageManager').value !== '';
    for (const id of PACKAGE_MANAGER_DEPENDENT_FIELD_IDS) {
        document.getElementById(id).disabled = !hasPackageManager;
    }
}
document.getElementById('packageManager').addEventListener('change', syncPackageManagerDependentFields);
syncPackageManagerDependentFields();

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
        sshState: document.getElementById('sshState').value,
        sshPreDownloadPackages: document.getElementById('sshPreDownloadPackages').value,
        installXrdpCommand: document.getElementById('installXrdpCommand').value,
        rdpState: document.getElementById('rdpState').value,
        xrdpPreDownloadPackages: document.getElementById('xrdpPreDownloadPackages').value,
        installVncCommand: document.getElementById('installVncCommand').value,
        vncState: document.getElementById('vncState').value,
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
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    window.location.href = `${basePath}/admin/templates`;
});
})();
