// Drives the container create/detail pages. Requires an already-signed-in session (run
// runAuthTests() first, or sign in by hand) with the ADMIN role - a fresh dev-stack H2 database
// auto-promotes whichever account logs in first to ADMIN (see UserService), so this is true by
// default right after tools/scripts/start-dev-stack.sh, but not if someone else already signed in
// first this session.
//
// Creates its own disposable template via a raw fetch() (not through the admin template UI - that
// UI is what tests-admin.js exists to test; here it's just fixture setup, the same role
// db/test/*.sql fixtures play in the reference harness this is modeled on) so this suite has
// something to build a real container from without depending on run order against tests-admin.js
// or on a template already existing in the dev DB.
//
// Deliberately does NOT click Start/Stop/Force stop: a freshly created container is already
// mid-provisioning (async, see ProvisioningService), so its actual state at the moment this suite
// runs is a race - clicking Start against a container that's already starting (or clicking Stop
// against one still CREATING) can hit a real 500 and pop a blocking alert() (see detail.js's
// post() helper), which would freeze the tab. The panel actions below (description, shares, port
// mappings, outbound) are valid in any container state, so they're what's exercised instead.
async function runContainerTests(shareTargetUsername) {
    const h = window.harness;
    h.section('containers: fixture template setup');

    const stamp = Date.now();
    const templateName = `harness-template-${stamp}`;
    let templateId;
    await h.step('create a disposable fixture template', async () => {
        const response = await fetch(`${location.origin}/nspawnmgr/api/admin/templates`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name: templateName,
                description: 'harness fixture - safe to delete',
                sourcePath: 'harness-fixture',
                backend: 'SYSTEMD_NSPAWN',
                packageManager: 'APT',
                rdpCapable: false,
                vncCapable: false,
                active: true,
            }),
        });
        if (!response.ok) {
            throw new Error(`fixture template creation failed: ${response.status} ${await response.text()}`);
        }
        const created = await response.json();
        templateId = created.id;
        h.pass(`fixture template '${templateName}' created (id ${templateId})`);
    });

    h.section('containers: create via the real form');

    const containerName = `harness-c-${stamp}`;
    const win = h.openApp('nspawnmgr', `${location.origin}/nspawnmgr/containers/new`);
    await h.step('create form renders with the fixture template selectable', async () => {
        await h.waitForReload(win);
        await h.waitForSelector(win, '#name', { timeout: 8000 });
        h.assertContains(h.bodyText(win), 'New container', 'create form heading visible');
        const option = [...win.document.querySelectorAll('#templateId option')]
            .find((o) => o.textContent.trim() === templateName);
        if (!option) throw new Error(`fixture template '${templateName}' not offered in the dropdown`);
        h.setInputValue(win.document.getElementById('templateId'), option.value);
    });

    await h.step('submitting creates the container and lands on its detail page', async () => {
        h.fill(win, '#name', containerName);
        h.fill(win, '#description', 'created by web-test-harness');
        h.click(win, 'button[type=submit]');
        await h.waitFor(() => /\/containers\/\d+$/.test(win.location.pathname), {
            timeout: 8000,
            label: 'redirect to the new container\'s detail page',
        });
        await h.waitForText(win, containerName, { timeout: 8000 });
        h.pass(`landed on the detail page for '${containerName}'`);
    });

    h.section('containers: owner panel round-trips');

    await h.step('saving a description persists across reload', async () => {
        const newDescription = `updated by harness at ${stamp}`;
        h.fill(win, '#description', newDescription);
        h.click(win, '#btn-save-description');
        await h.waitForReload(win);
        await h.waitFor(() => win.document.getElementById('description').value === newDescription, {
            label: 'description input to show the saved value after reload',
            timeout: 10000,
        });
        h.pass('description round-tripped');
    });

    if (shareTargetUsername) {
        await h.step(`sharing with '${shareTargetUsername}' adds them to Shared with`, async () => {
            h.fill(win, '#share-username', shareTargetUsername);
            h.click(win, '#btn-add-share');
            await h.waitForReload(win);
            await h.waitForText(win, shareTargetUsername, { timeout: 10000 });
            h.pass('share appears in the list');
        });

        await h.step(`removing the share for '${shareTargetUsername}' clears it`, async () => {
            const row = [...win.document.querySelectorAll('#share-list li')]
                .find((li) => li.textContent.includes(shareTargetUsername));
            if (!row) throw new Error('share row not found to remove');
            h.clickByText(win, 'button', 'Remove', row);
            await h.waitForReload(win);
            h.assertNotContains(h.bodyText(win), shareTargetUsername, 'share no longer listed');
        });
    } else {
        h.pass('share round-trip skipped (no share-target username provided above)');
    }

    const hostPort = 20000 + (stamp % 10000);
    await h.step('adding a port mapping shows it in the list', async () => {
        h.fill(win, '#port-mapping-host-port', String(hostPort));
        h.fill(win, '#port-mapping-container-port', '22');
        h.click(win, '#btn-add-port-mapping');
        await h.waitForReload(win);
        await h.waitForText(win, `${hostPort} -> 22`, { timeout: 10000 });
        h.pass('port mapping listed');
    });

    await h.step('removing the port mapping clears it', async () => {
        const row = [...win.document.querySelectorAll('#port-mapping-list li')]
            .find((li) => li.textContent.includes(`${hostPort} -> 22`));
        if (!row) throw new Error('port mapping row not found to remove');
        h.clickByText(win, 'button', 'Remove', row);
        await h.waitForReload(win);
        h.assertNotContains(h.bodyText(win), `${hostPort} -> 22`, 'port mapping no longer listed');
    });

    await h.step('toggling outbound access persists across reload', async () => {
        const before = win.document.getElementById('outbound-enabled').checked;
        h.check(win, '#outbound-enabled', !before);
        h.click(win, '#btn-save-outbound');
        await h.waitForReload(win);
        h.assertContains(String(win.document.getElementById('outbound-enabled').checked), String(!before),
            'outbound checkbox flipped and stuck');
    });

    h.section('containers: PAM auth round-trip (requires RUNNING)');

    // Unlike the panel actions above, saving PAM auth settings on a RUNNING container pushes a
    // live script to it (PamCredentialAuthService.applyToContainer), which reads
    // container.getTemplate() - open-in-view is off, so this is the one action in this suite that
    // actually exercises the "container loaded in one transaction, touched in another" class of
    // bug (LazyInitializationException, hit for real on fed1, 2026-08-13) that a pure Mockito unit
    // test structurally cannot catch. The container needs to actually reach RUNNING first -
    // FakeContainerReadinessChecker is "always ready on the first poll tick" and
    // ContainerReadinessPollingService polls every 10s, so this is a real wait, not a guess.
    const containerIdMatch = win.location.pathname.match(/\/containers\/(\d+)$/);
    const containerId = containerIdMatch ? containerIdMatch[1] : null;
    await h.step('container reaches RUNNING', async () => {
        if (!containerId) throw new Error('could not extract container id from ' + win.location.pathname);
        await h.waitFor(async () => {
            const response = await fetch(`${location.origin}/nspawnmgr/api/containers/${containerId}/status`);
            if (!response.ok) return false;
            const status = await response.json();
            return status.state === 'RUNNING' || null;
        }, { timeout: 25000, interval: 1000, label: 'container to reach RUNNING' });
        h.pass('container is RUNNING');
    });

    await h.step('saving PAM auth settings on a RUNNING container succeeds and persists', async () => {
        h.setInputValue(win.document.getElementById('pam-auth-source-select'), 'NSPAWNMGR_AUTH_BACKEND');
        h.check(win, 'input.pam-auth-service-checkbox[value=sshd]', true);
        h.click(win, '#btn-save-pam-auth');
        await h.waitForReload(win, { timeout: 15000 });
        h.assertContains(win.document.getElementById('pam-auth-source-select').value, 'NSPAWNMGR_AUTH_BACKEND',
            'source select shows the saved value after reload');
        const sshdChecked = win.document.querySelector('input.pam-auth-service-checkbox[value=sshd]').checked;
        h.assertContains(String(sshdChecked), 'true', 'sshd checkbox stuck checked after reload');
    });

    h.section('containers: cleanup');

    await h.step('deleting the container removes it and returns to the list', async () => {
        h.click(win, '#btn-delete');
        await h.acceptAppDialog(win);
        await h.waitFor(() => win.location.pathname === '/nspawnmgr/' || win.location.pathname === '/nspawnmgr', {
            timeout: 8000,
            label: 'redirect to the containers list after delete',
        });
        // No separate waitForReload here: the pathname check above already confirms the
        // navigation completed, so win.document is already the new (list) document by this point
        // - waiting for a further document swap that isn't coming would just time out.
        await h.waitForText(win, 'Containers', { timeout: 10000 });
        h.assertNotContains(h.bodyText(win), containerName, 'deleted container no longer listed');
    });

    await h.step('deleting the fixture template cleans up', async () => {
        const response = await fetch(`${location.origin}/nspawnmgr/api/admin/templates/${templateId}`, { method: 'DELETE' });
        if (!response.ok) throw new Error(`fixture template delete failed: ${response.status} ${await response.text()}`);
        h.pass('fixture template deleted');
    });
}
