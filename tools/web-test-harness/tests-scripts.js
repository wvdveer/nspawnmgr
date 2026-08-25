// Drives the container Scripts "Execute"/"Abort" flow (script-form.html + script-form.js) against
// the dev-stack's FakeContainerCliExecutor.startScript, which deliberately takes ~6 real seconds
// (see tools/fake-machinectl) so there's an actual window to click Abort during, unlike the old
// instant fake runScript. Builds its own disposable template + container via raw fetch() (same
// fixture convention tests-containers.js uses) so this suite doesn't depend on run order against
// the other suites.
async function runScriptTests() {
    const h = window.harness;
    h.section('scripts: fixture template + container setup');

    const stamp = Date.now();
    const templateName = `harness-script-template-${stamp}`;
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
                rdpState: 'NOT_CAPABLE',
                vncState: 'NOT_CAPABLE',
                active: true,
            }),
        });
        if (!response.ok) {
            throw new Error(`fixture template creation failed: ${response.status} ${await response.text()}`);
        }
        templateId = (await response.json()).id;
        h.pass(`fixture template '${templateName}' created (id ${templateId})`);
    });

    const containerName = `harness-script-c-${stamp}`;
    let containerId;
    await h.step('create a disposable fixture container', async () => {
        const response = await fetch(`${location.origin}/nspawnmgr/api/containers`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: containerName, templateId, rdpEnabled: false, vncEnabled: false }),
        });
        if (!response.ok) {
            throw new Error(`fixture container creation failed: ${response.status} ${await response.text()}`);
        }
        containerId = (await response.json()).id;
        h.pass(`fixture container '${containerName}' created (id ${containerId})`);
    });

    h.section('scripts: execute + abort');

    const win = h.openApp('nspawnmgr', `${location.origin}/nspawnmgr/containers/${containerId}/scripts/new`);
    await h.step('new script form renders', async () => {
        await h.waitForReload(win);
        await h.waitForSelector(win, '#scriptBody', { timeout: 8000 });
        h.assertContains(h.bodyText(win), 'New script', 'new script form heading visible');
    });

    await h.step('clicking Execute starts an abortable run', async () => {
        h.fill(win, '#name', `harness-script-${stamp}`);
        h.fill(win, '#scriptBody', 'echo hi');
        h.click(win, '#btn-execute');
        await h.waitFor(() => win.document.getElementById('btn-abort').style.display !== 'none', {
            timeout: 4000,
            label: 'Abort button to appear while the fake run is in flight',
        });
        h.pass('Abort button visible while running');
    });

    await h.step('clicking Abort reaches an Aborted terminal state', async () => {
        // The Abort button becomes visible synchronously (before the run-async POST's response
        // comes back and sets a runId to abort), so clicking it immediately after the previous
        // step - a real user never could, but automation can - would race that response and no-op
        // silently. A short wait here is well within the fake's ~6s run window either way.
        await new Promise((resolve) => setTimeout(resolve, 500));
        h.click(win, '#btn-abort');
        await h.acceptAppDialog(win);
        await h.waitFor(() => win.document.getElementById('status').textContent.includes('Aborted'), {
            timeout: 8000,
            label: 'status text to read Aborted',
        });
        await h.waitFor(() => win.document.getElementById('btn-abort').style.display === 'none', {
            timeout: 4000,
            label: 'Abort button to hide again once the run is terminal',
        });
        h.pass('run reached Aborted and polling stopped');
    });

    h.section('scripts: cleanup');

    await h.step('deleting the fixture container cleans up', async () => {
        const response = await fetch(`${location.origin}/nspawnmgr/api/containers/${containerId}`, { method: 'DELETE' });
        if (!response.ok) throw new Error(`fixture container delete failed: ${response.status} ${await response.text()}`);
        h.pass('fixture container deleted');
    });

    await h.step('deleting the fixture template cleans up', async () => {
        const response = await fetch(`${location.origin}/nspawnmgr/api/admin/templates/${templateId}`, { method: 'DELETE' });
        if (!response.ok) throw new Error(`fixture template delete failed: ${response.status} ${await response.text()}`);
        h.pass('fixture template deleted');
    });
}
