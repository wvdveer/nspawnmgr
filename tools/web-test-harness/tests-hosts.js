// Drives the Hosts feature: admin create at /admin/hosts/new, and edit/delete from the host's own
// /containers/{id} detail page. Hosts show up directly on the main Machines grid (/) alongside
// nspawn/podman/QEMU machines, badged HOST - there is no separate hosts-only listing page.
// Requires an already-signed-in ADMIN session (see tests-auth.js's own comment on how that role
// gets assigned on a fresh dev-stack DB) - ownerUsername is set to that same account, since
// HostService requires the owner to already exist (have logged in at least once), and the
// signed-in admin is the one account guaranteed to satisfy that.
async function runHostTests(ownerUsername) {
    const h = window.harness;
    const stamp = Date.now();

    h.section('hosts: admin create via the real form (SSH + VNC, RDP off)');

    const hostName = `harness-host-${stamp}`;
    const win = h.openApp('nspawnmgr', `${location.origin}/nspawnmgr/admin/hosts/new`);
    let hostDetailPath;
    await h.step('new-host form renders', async () => {
        await h.waitForReload(win);
        await h.waitForSelector(win, '#name', { timeout: 8000 });
        h.assertContains(h.bodyText(win), 'New host', 'form heading visible');
    });

    await h.step('submitting creates the host and lands on its detail page', async () => {
        h.fill(win, '#name', hostName);
        h.fill(win, '#hostname', '203.0.113.10');
        h.fill(win, '#ownerUsername', ownerUsername);
        h.check(win, '#sshEnabled', true);
        h.fill(win, '#sshPort', '22');
        h.check(win, '#rdpEnabled', false);
        h.check(win, '#vncEnabled', true);
        h.fill(win, '#vncPort', '5900');
        h.click(win, 'button[type=submit]');
        await h.waitFor(() => /\/containers\/\d+$/.test(win.location.pathname), {
            timeout: 8000,
            label: 'redirect to the host\'s own detail page',
        });
        await h.waitForText(win, hostName, { timeout: 10000 });
        hostDetailPath = win.location.pathname;
        h.pass('host detail page shows the new host');
    });

    h.section('hosts: appears on the Machines grid, badged HOST');

    const machinesWin = h.openApp('nspawnmgr', `${location.origin}/nspawnmgr/`);
    await h.step('shows a HOST badge with SSH/VNC connect enabled, RDP disabled', async () => {
        await h.waitForReload(machinesWin);
        await h.waitForText(machinesWin, hostName, { timeout: 8000 });
        const card = [...machinesWin.document.querySelectorAll('.machine-card')]
            .find((c) => c.textContent.includes(hostName));
        if (!card) throw new Error(`no machine-card containing "${hostName}"`);
        h.assertContains(card.textContent, 'HOST', 'HOST badge shown');
        const sshButton = card.querySelector('button.access-pill.SSH');
        const rdpButton = card.querySelector('button.access-pill.RDP');
        const vncButton = card.querySelector('button.access-pill.VNC');
        h.assertContains(String(!sshButton.disabled), 'true', 'SSH connect enabled');
        h.assertContains(String(rdpButton.disabled), 'true', 'RDP connect disabled (was not enabled on create)');
        h.assertContains(String(!vncButton.disabled), 'true', 'VNC connect enabled');
    });

    h.section('hosts: cleanup');

    await h.step('deleting the host from its own detail page removes it', async () => {
        // Re-open the host's own detail page: `win` names the same reused tab as machinesWin
        // above, so it's currently showing whatever the last openApp() call navigated it to (the
        // Machines grid), not the host detail page this step needs.
        const detailWin = h.openApp('nspawnmgr', `${location.origin}${hostDetailPath}`);
        await h.waitForReload(detailWin);
        await h.waitForText(detailWin, 'Delete host', { timeout: 8000 });
        h.clickByText(detailWin, 'button', 'Delete host');
        await h.acceptAppDialog(detailWin);
        // detail.js redirects to `/` (the Machines grid) after a successful delete.
        await h.waitForReload(detailWin);
        await h.waitForText(detailWin, 'Machines', { timeout: 8000 });
        h.assertNotContains(h.bodyText(detailWin), hostName, 'host no longer listed');
    });
}
