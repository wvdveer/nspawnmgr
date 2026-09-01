const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

const templateIdSelect = document.getElementById('templateId');
const rdpEnabledCheckbox = document.getElementById('rdpEnabled');
const rdpUnavailableHint = document.getElementById('rdp-unavailable-hint');
const vncEnabledCheckbox = document.getElementById('vncEnabled');
const desktopManagerSelect = document.getElementById('desktopManager');

function syncDesktopManagerEnabled() {
    const enabled = rdpEnabledCheckbox.checked || vncEnabledCheckbox.checked;
    desktopManagerSelect.disabled = !enabled;
    if (!enabled) {
        desktopManagerSelect.value = 'NONE';
    }
}
rdpEnabledCheckbox.addEventListener('change', syncDesktopManagerEnabled);
vncEnabledCheckbox.addEventListener('change', syncDesktopManagerEnabled);

// Flows from the selected template's own "RDP" state (set on the Templates admin page) - not a
// hardcoded package-manager check here, so an admin controls this per-template. Confirmed live:
// arch-minimal defaults it to NOT_CAPABLE, since xrdp/xorgxrdp were dropped from Arch's official
// repos entirely (AUR-only now, which this app has no way to build from) - offering the checkbox
// for it just led to a confusing pacman "target not found" failure partway through creation.
function syncRdpAvailability() {
    const selected = templateIdSelect.selectedOptions[0];
    const rdpCapable = selected ? selected.getAttribute('data-rdp-state') !== 'NOT_CAPABLE' : true;
    rdpEnabledCheckbox.disabled = !rdpCapable;
    rdpUnavailableHint.style.display = rdpCapable ? 'none' : '';
    if (!rdpCapable && rdpEnabledCheckbox.checked) {
        rdpEnabledCheckbox.checked = false;
        syncDesktopManagerEnabled();
    }
}
templateIdSelect.addEventListener('change', syncRdpAvailability);
syncRdpAvailability();

document.getElementById('create-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const status = document.getElementById('status');
    const body = {
        name: document.getElementById('name').value,
        templateId: Number(document.getElementById('templateId').value),
        rdpEnabled: rdpEnabledCheckbox.checked,
        vncEnabled: vncEnabledCheckbox.checked,
        desktopManager: desktopManagerSelect.value,
        description: document.getElementById('description').value,
    };
    const response = await fetch(`${basePath}/api/containers`, {
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
