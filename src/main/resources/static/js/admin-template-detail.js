(function () {
const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const templateId = document.body.getAttribute('data-template-id');
const sudoApprovalRequired = document.body.getAttribute('data-sudo-approval-required') === 'true';
const status = document.getElementById('template-action-status');

function sudoPassword() {
    const field = document.getElementById('convertSudoPassword');
    return field ? field.value : null;
}

document.getElementById('btn-deactivate')?.addEventListener('click', async () => {
    const response = await fetch(`${basePath}/api/admin/templates/${templateId}/deactivate`, { method: 'POST' });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

document.getElementById('btn-reactivate')?.addEventListener('click', async () => {
    const response = await fetch(`${basePath}/api/admin/templates/${templateId}/reactivate`, { method: 'POST' });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
});

async function convert(endpointSuffix) {
    const newName = await window.appDialog.prompt(t('page.templateDetail.newTemplateNamePrompt'));
    if (!newName) {
        return;
    }
    if (sudoApprovalRequired && !sudoPassword()) {
        status.textContent = t('page.templateDetail.enterSudoPasswordFirst');
        return;
    }
    status.textContent = t('page.templateDetail.creatingX', newName);
    const response = await fetch(`${basePath}/api/admin/templates/${templateId}/${endpointSuffix}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ newName, sudoPassword: sudoPassword() }),
    });
    if (!response.ok) {
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    window.location.reload();
}

document.getElementById('btn-convert-to-podman')?.addEventListener('click', () => convert('convert-to-podman'));
document.getElementById('btn-convert-to-nspawn')?.addEventListener('click', () => convert('convert-to-nspawn'));

document.getElementById('btn-delete')?.addEventListener('click', async () => {
    if (!await window.appDialog.confirm(t('page.templateDetail.confirmDeleteTemplate'))) {
        return;
    }
    const response = await fetch(`${basePath}/api/admin/templates/${templateId}`, { method: 'DELETE' });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.href = `${basePath}/admin/templates`;
});
})();
