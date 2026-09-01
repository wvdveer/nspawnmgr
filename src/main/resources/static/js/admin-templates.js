const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.querySelectorAll('.btn-create-minimal').forEach((button) => {
    button.addEventListener('click', async () => {
        const flavor = button.getAttribute('data-flavor');
        const status = document.getElementById('create-minimal-status');
        const sudoApprovalRequired = document.body.getAttribute('data-sudo-approval-required') === 'true';
        const passwordField = document.getElementById('minimalSudoPassword');
        if (sudoApprovalRequired && (!passwordField || !passwordField.value)) {
            status.textContent = t('page.adminTemplates.enterSudoPasswordFirst');
            return;
        }
        button.disabled = true;
        status.textContent = t('page.adminTemplates.downloadingMinimal', flavor);
        const response = await fetch(`${basePath}/api/admin/templates/create-${flavor.replace('-minimal', '')}-minimal`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sudoPassword: passwordField ? passwordField.value : null }),
        });
        if (!response.ok) {
            status.textContent = t('general.failedPrefix', await response.text());
            button.disabled = false;
            return;
        }
        window.location.reload();
    });
});

const podmanSudoApprovalRequired = document.body.getAttribute('data-sudo-approval-required') === 'true';

function podmanSudoPassword() {
    const field = document.getElementById('podmanSudoPassword');
    return field ? field.value : null;
}

const podmanActionStatus = document.getElementById('podman-action-status');

document.getElementById('btn-new-pod')?.addEventListener('click', async () => {
    const pullReference = await window.appDialog.prompt(t('page.adminTemplates.imageToPullPrompt'));
    if (!pullReference) {
        return;
    }
    if (podmanSudoApprovalRequired && !podmanSudoPassword()) {
        podmanActionStatus.textContent = t('page.adminTemplates.enterSudoPasswordFirst');
        return;
    }
    podmanActionStatus.textContent = t('page.adminTemplates.pulling', pullReference);
    const response = await fetch(`${basePath}/api/admin/templates/create-podman-pull`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pullReference, sudoPassword: podmanSudoPassword() }),
    });
    if (!response.ok) {
        podmanActionStatus.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    window.location.reload();
});

