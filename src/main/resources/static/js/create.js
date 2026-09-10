const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

const templateIdSelect = document.getElementById('templateId');
const rdpEnabledCheckbox = document.getElementById('rdpEnabled');
const rdpUnavailableHint = document.getElementById('rdp-unavailable-hint');
const vncEnabledCheckbox = document.getElementById('vncEnabled');
const vpnCapableCheckbox = document.getElementById('vpnCapable');
const desktopManagerSelect = document.getElementById('desktopManager');

function syncDesktopManagerEnabled() {
    const enabled = rdpEnabledCheckbox.checked || vncEnabledCheckbox.checked;
    desktopManagerSelect.disabled = !enabled;
    if (!enabled) {
        desktopManagerSelect.value = 'NONE_X11';
    }
    syncWaylandOptionsAvailability();
}

// Wayland options (tagged data-wayland, rather than string-matching option values) are disabled/
// deselected whenever either of two things is true:
// - RDP is checked - RDP stays X11-only (xrdp/xorgxrdp has no Wayland story in this app).
//   ProvisioningService.createPending silently downgrades rdpEnabled the same way server-side.
// - the selected template is APT-based - confirmed live (way1/debian-minimal, yoga, 2026-09-02):
//   Debian bookworm's own `cage` build (0.1.4, the only version bookworm's repos carry) is too
//   old to advertise the virtual-pointer protocol `wayvnc` needs for interactive input, so
//   NONE_WAYLAND doesn't work there at all. ProvisioningService.createPending silently downgrades
//   desktopManager the same way server-side.
function syncWaylandOptionsAvailability() {
    const rdpChecked = rdpEnabledCheckbox.checked;
    const templateIsApt = templateIdSelect.selectedOptions[0]?.dataset.packageManager === 'APT';
    const waylandUnavailable = rdpChecked || templateIsApt;
    const selected = desktopManagerSelect.selectedOptions[0];
    const currentlyWayland = selected?.dataset.wayland === 'true';
    if (waylandUnavailable && currentlyWayland) {
        desktopManagerSelect.value = 'NONE_X11';
    }
    for (const option of desktopManagerSelect.options) {
        if (option.dataset.wayland === 'true') {
            option.disabled = waylandUnavailable;
        }
    }
}
desktopManagerSelect.addEventListener('change', syncWaylandOptionsAvailability);
rdpEnabledCheckbox.addEventListener('change', syncDesktopManagerEnabled);
vncEnabledCheckbox.addEventListener('change', syncDesktopManagerEnabled);
syncWaylandOptionsAvailability();

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
    syncWaylandOptionsAvailability();
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
        vpnCapable: vpnCapableCheckbox.checked,
        desktopManager: desktopManagerSelect.value,
        description: document.getElementById('description').value,
        keystrokeButtonPanelPlacement: document.getElementById('keystrokeButtonPanelPlacement').value,
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
