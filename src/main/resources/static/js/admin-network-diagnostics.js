const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const sshApprovalRequired = document.body.getAttribute('data-ssh-approval-required') === 'true';

const FIX_ENDPOINTS = {
    'networkd': 'networkd',
    'host-address': 'host-address',
};

function renderChecks(checks) {
    const list = document.getElementById('check-list');
    list.innerHTML = '';
    checks.forEach((check) => {
        const row = document.createElement('div');
        row.className = 'diag-check';
        row.id = `check-${check.id}`;

        const status = document.createElement('span');
        status.className = `diag-status ${check.status}`;
        status.textContent = check.status;
        row.appendChild(status);

        const body = document.createElement('div');
        body.className = 'diag-body';
        const label = document.createElement('div');
        label.textContent = check.label;
        body.appendChild(label);
        const detail = document.createElement('div');
        detail.className = 'diag-detail';
        detail.textContent = check.detail;
        body.appendChild(detail);
        row.appendChild(body);

        if (check.fixable) {
            // Only in admin-approval mode does the stored sudo secret not exist, so only then do
            // we need one entered fresh here - otherwise the fix falls back to the stored secret
            // automatically, same as every other privileged action in this app.
            let passwordInput = null;
            if (sshApprovalRequired) {
                passwordInput = document.createElement('input');
                passwordInput.type = 'password';
                passwordInput.placeholder = 'sudo password';
                row.appendChild(passwordInput);
            }

            const fixButton = document.createElement('button');
            fixButton.type = 'button';
            fixButton.className = 'btn-primary';
            fixButton.textContent = 'Fix';
            fixButton.addEventListener('click', () => applyFix(check.id, passwordInput));
            row.appendChild(fixButton);
        }

        list.appendChild(row);
    });
}

async function applyFix(checkId, passwordInput) {
    const endpoint = FIX_ENDPOINTS[checkId];
    if (!endpoint) {
        return;
    }
    if (!await window.appDialog.confirm('This changes host configuration (network/firewall). Continue?')) {
        return;
    }
    const sudoPassword = passwordInput ? passwordInput.value : null;
    if (passwordInput) {
        passwordInput.value = '';
    }
    const response = await fetch(`${basePath}/api/admin/network-diagnostics/fix/${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sudoPassword }),
    });
    if (!response.ok) {
        await window.appDialog.alert('Fix failed: ' + await response.text());
        return;
    }
    const updated = await response.json();
    renderChecks(mergeCheck(updated));
}

let lastChecks = [];

function mergeCheck(updated) {
    lastChecks = lastChecks.map((c) => (c.id === updated.id ? updated : c));
    return lastChecks;
}

document.getElementById('run-diagnostics').addEventListener('click', async () => {
    const runStatus = document.getElementById('run-status');
    runStatus.textContent = 'Running...';
    const response = await fetch(`${basePath}/api/admin/network-diagnostics/run`, { method: 'POST' });
    if (!response.ok) {
        runStatus.textContent = 'Failed: ' + await response.text();
        return;
    }
    lastChecks = await response.json();
    runStatus.textContent = '';
    renderChecks(lastChecks);
});
