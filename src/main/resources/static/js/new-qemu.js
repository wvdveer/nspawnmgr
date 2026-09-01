const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

function setDiskSourceMode(mode) {
    document.getElementById('diskSizeGb-field').style.display = mode === 'empty' ? '' : 'none';
    document.getElementById('templateId-field').style.display = mode === 'template' ? '' : 'none';
    document.getElementById('diskSizeGb').required = mode === 'empty';
}

document.getElementById('mode-empty').addEventListener('change', () => setDiskSourceMode('empty'));
document.getElementById('mode-template').addEventListener('change', () => setDiskSourceMode('template'));

document.getElementById('create-qemu-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const status = document.getElementById('status');
    const isoSelect = document.getElementById('isoPackageId');
    const templated = document.getElementById('mode-template').checked;
    const body = {
        name: document.getElementById('name').value,
        diskSizeGb: templated ? null : Number(document.getElementById('diskSizeGb').value),
        templateId: templated ? Number(document.getElementById('templateId').value) : null,
        isoPackageId: (isoSelect && isoSelect.value) ? Number(isoSelect.value) : null,
        description: document.getElementById('description').value,
        cpuModel: document.getElementById('cpuModel').value,
        cpuCount: Number(document.getElementById('cpuCount').value),
        memoryMb: Number(document.getElementById('memoryMb').value),
        nicModel: document.getElementById('nicModel').value,
        pointerDevice: document.getElementById('pointerDevice').value,
    };
    const response = await fetch(`${basePath}/api/containers/qemu`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    const created = await response.json();
    window.location.href = `${basePath}/containers/${created.id}`;
});
