const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const sshApprovalRequired = document.body.getAttribute('data-ssh-approval-required') === 'true';

const FIX_ENDPOINTS = {
    'networkd': 'networkd',
    'host-address': 'host-address',
    'podman': 'podman',
    'qemu': 'qemu',
    'podman-network': 'podman-network',
    'qemu-bridge': 'qemu-bridge',
};

function renderChecks(checks) {
    const list = document.getElementById('check-list');
    list.innerHTML = '';
    checks.forEach((check) => {
        const card = document.createElement('div');
        card.className = 'machine-card diag-check';
        card.id = `check-${check.id}`;

        const header = document.createElement('div');
        header.className = 'machine-card-header';
        const label = document.createElement('span');
        label.className = 'machine-card-name';
        label.textContent = check.label;
        header.appendChild(label);
        const status = document.createElement('span');
        status.className = `diag-status ${check.status}`;
        status.textContent = t('diag.status.' + check.status);
        header.appendChild(status);
        card.appendChild(header);

        const detail = document.createElement('p');
        detail.className = 'machine-card-meta diag-detail';
        detail.textContent = check.detail;
        card.appendChild(detail);
        if (check.log) {
            const log = document.createElement('pre');
            log.className = 'diag-log';
            log.textContent = check.log;
            card.appendChild(log);
        }

        if (check.fixable) {
            const footer = document.createElement('div');
            footer.className = 'machine-card-footer';
            // Only in admin-approval mode does the stored sudo secret not exist, so only then do
            // we need one entered fresh here - otherwise the fix falls back to the stored secret
            // automatically, same as every other privileged action in this app.
            let passwordInput = null;
            if (sshApprovalRequired) {
                passwordInput = document.createElement('input');
                passwordInput.type = 'password';
                passwordInput.placeholder = t('page.diagnostics.sudoPasswordPlaceholder');
                footer.appendChild(passwordInput);
            }

            const fixButton = document.createElement('button');
            fixButton.type = 'button';
            fixButton.className = 'btn-primary';
            fixButton.textContent = t('button.fix');
            fixButton.addEventListener('click', () => applyFix(check.id, passwordInput, fixButton, card));
            footer.appendChild(fixButton);
            card.appendChild(footer);
        }

        list.appendChild(card);
    });
}

async function applyFix(checkId, passwordInput, fixButton, card) {
    const endpoint = FIX_ENDPOINTS[checkId];
    if (!endpoint) {
        return;
    }
    if (!await window.appDialog.confirm(t('page.diagnostics.confirmFix'))) {
        return;
    }
    const sudoPassword = passwordInput ? passwordInput.value : null;
    if (passwordInput) {
        passwordInput.value = '';
    }
    // A fix (e.g. apt-get install) can take real time - show something immediately rather than
    // leaving the button as the only sign anything's happening for up to several minutes. Replaced
    // wholesale once the real result (with its own log, if any) comes back and re-renders the row.
    fixButton.disabled = true;
    const runningLog = document.createElement('pre');
    runningLog.className = 'diag-log';
    runningLog.textContent = t('js.status.running');
    card.appendChild(runningLog);
    const response = await fetch(`${basePath}/api/admin/network-diagnostics/fix/${endpoint}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sudoPassword }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('page.diagnostics.fixFailedPrefix', await response.text()));
        fixButton.disabled = false;
        runningLog.remove();
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

async function runDiagnostics() {
    const runStatus = document.getElementById('run-status');
    runStatus.textContent = t('js.status.running');
    const response = await fetch(`${basePath}/api/admin/network-diagnostics/run`, { method: 'POST' });
    if (!response.ok) {
        runStatus.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    lastChecks = await response.json();
    runStatus.textContent = '';
    renderChecks(lastChecks);
}

runDiagnostics();
