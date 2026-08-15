// Drives the Hosts feature: admin create/edit/delete at /admin/hosts, and the owner-facing browse/
// connect page at /hosts. Requires an already-signed-in ADMIN session (see tests-auth.js's own
// comment on how that role gets assigned on a fresh dev-stack DB) - ownerUsername is set to that
// same account, since HostService requires the owner to already exist (have logged in at least
// once), and the signed-in admin is the one account guaranteed to satisfy that.
async function runHostTests(ownerUsername) {
    const h = window.harness;
    const stamp = Date.now();

    h.section('hosts: admin create via the real form (SSH + VNC, RDP off)');

    const hostName = `harness-host-${stamp}`;
    const win = h.openApp('nspawnmgr', `${location.origin}/nspawnmgr/admin/hosts/new`);
    await h.step('new-host form renders', async () => {
        await h.waitForReload(win);
        await h.waitForSelector(win, '#name', { timeout: 8000 });
        h.assertContains(h.bodyText(win), 'New host', 'form heading visible');
    });

    await h.step('submitting creates the host and lists it', async () => {
        h.fill(win, '#name', hostName);
        h.fill(win, '#hostname', '203.0.113.10');
        h.fill(win, '#ownerUsername', ownerUsername);
        h.check(win, '#sshEnabled', true);
        h.fill(win, '#sshPort', '22');
        h.check(win, '#rdpEnabled', false);
        h.check(win, '#vncEnabled', true);
        h.fill(win, '#vncPort', '5900');
        h.click(win, 'button[type=submit]');
        await h.waitFor(() => win.location.pathname.endsWith('/admin/hosts'), {
            timeout: 8000,
            label: 'redirect to the hosts list',
        });
        await h.waitForText(win, hostName, { timeout: 10000 });
        h.pass('host appears in the admin list');
    });

    await h.step('admin list shows SSH/VNC enabled, RDP not', async () => {
        const row = h.findRowByText(win, hostName);
        h.assertContains(row.textContent, 'yes (22)', 'SSH shown enabled with its port');
        h.assertContains(row.textContent, 'yes (5900)', 'VNC shown enabled with its port');
        h.assertNotContains(row.textContent.replace('yes (22)', '').replace('yes (5900)', ''), 'yes (',
            'RDP not shown enabled');
    });

    h.section('hosts: separate from Containers, visible on /hosts');

    const containersWin = h.openApp('nspawnmgr', `${location.origin}/nspawnmgr/`);
    await h.step('does not appear on the containers list', async () => {
        await h.waitForReload(containersWin);
        await h.waitForText(containersWin, 'Containers', { timeout: 8000 });
        h.assertNotContains(h.bodyText(containersWin), hostName, 'host absent from Containers');
    });

    const hostsWin = h.openApp('nspawnmgr', `${location.origin}/nspawnmgr/hosts`);
    await h.step('appears on the owner-facing /hosts page with SSH/VNC connect enabled', async () => {
        await h.waitForReload(hostsWin);
        await h.waitForText(hostsWin, hostName, { timeout: 8000 });
        const row = h.findRowByText(hostsWin, hostName);
        const sshButton = [...row.querySelectorAll('button')].find((b) => b.textContent.trim() === 'SSH');
        const rdpButton = [...row.querySelectorAll('button')].find((b) => b.textContent.trim() === 'RDP');
        const vncButton = [...row.querySelectorAll('button')].find((b) => b.textContent.trim() === 'VNC');
        h.assertContains(String(!sshButton.disabled), 'true', 'SSH connect enabled');
        h.assertContains(String(rdpButton.disabled), 'true', 'RDP connect disabled (was not enabled on create)');
        h.assertContains(String(!vncButton.disabled), 'true', 'VNC connect enabled');
    });

    h.section('hosts: cleanup');

    await h.step('deleting the host removes it from the admin list', async () => {
        // Re-open /admin/hosts: `win` names the same reused tab as containersWin/hostsWin above, so
        // it's currently showing whatever the last openApp() call navigated it to (the /hosts page),
        // not the admin list this step needs.
        const adminWin = h.openApp('nspawnmgr', `${location.origin}/nspawnmgr/admin/hosts`);
        await h.waitForReload(adminWin);
        await h.waitForText(adminWin, hostName, { timeout: 8000 });
        const row = h.findRowByText(adminWin, hostName);
        h.clickByText(adminWin, 'button', 'Delete', row);
        await h.acceptAppDialog(adminWin);
        await h.waitForReload(adminWin);
        h.assertNotContains(h.bodyText(adminWin), hostName, 'host no longer listed');
    });
}
