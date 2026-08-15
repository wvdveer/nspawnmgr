// Drives the admin Templates and Packages pages. Requires an already-signed-in ADMIN session (see
// tests-auth.js's own comment on how that role gets assigned on a fresh dev-stack DB). Independent
// of tests-containers.js - creates and tears down its own fixtures, doesn't share them.
async function runAdminTests() {
    const h = window.harness;
    const stamp = Date.now();

    h.section('admin: template create via the real form');

    const templateName = `harness-admin-template-${stamp}`;
    const win = h.openApp('nspawnmgr', `${location.origin}/nspawnmgr/admin/templates/new`);
    await h.step('new-template form renders', async () => {
        await h.waitForReload(win);
        await h.waitForSelector(win, '#name', { timeout: 8000 });
        h.assertContains(h.bodyText(win), 'New template', 'form heading visible');
    });

    await h.step('submitting creates the template and lists it', async () => {
        h.fill(win, '#name', templateName);
        h.fill(win, '#description', 'harness fixture - safe to delete');
        h.fill(win, '#sourcePath', 'harness-fixture');
        h.setInputValue(win.document.getElementById('packageManager'), 'APT');
        h.click(win, 'button[type=submit]');
        await h.waitFor(() => win.location.pathname.endsWith('/admin/templates'), {
            timeout: 8000,
            label: 'redirect to the templates list',
        });
        await h.waitForText(win, templateName, { timeout: 10000 });
        h.pass('template appears in the list');
    });

    h.section('admin: template edit form round-trip');

    // The create form above is the only thing this suite has ever exercised - the edit form
    // (admin/templates/{id}/edit) shares the same template-form.html/admin-template-form.js, but
    // reaches PUT instead of POST, and had zero coverage here until 2026-08-13. Round-trips two of
    // the per-protocol override fields (added the same day, for the VNC logout-vs-Xtigervnc fixes)
    // through the real edit form, not just a raw fetch() PUT, so a mistake in the field's
    // id/name/th:value wiring (see template-form.html) would actually be caught here.
    const xstartupOverride = `exec harness-fixture-session-${stamp}`;
    const processPatternOverride = `Xharness${stamp}`;
    await h.step('opening Edit on the fixture template shows the create-time values', async () => {
        const row = h.findRowByText(win, templateName);
        h.clickByText(win, 'a', 'Edit', row);
        await h.waitForReload(win);
        await h.waitForSelector(win, '#vncXstartupTemplate', { timeout: 8000 });
        h.assertContains(win.document.getElementById('name').value, templateName, 'edit form pre-filled with the right template');
    });

    await h.step('saving VNC override fields through the edit form persists them', async () => {
        h.fill(win, '#vncXstartupTemplate', xstartupOverride);
        h.fill(win, '#vncProcessNamePattern', processPatternOverride);
        h.click(win, 'button[type=submit]');
        await h.waitFor(() => win.location.pathname.endsWith('/admin/templates'), {
            timeout: 8000,
            label: 'redirect back to the templates list after edit',
        });
        const row = h.findRowByText(win, templateName);
        h.clickByText(win, 'a', 'Edit', row);
        await h.waitForReload(win);
        await h.waitForSelector(win, '#vncXstartupTemplate', { timeout: 8000 });
        h.assertContains(win.document.getElementById('vncXstartupTemplate').value, xstartupOverride,
            'vncXstartupTemplate stuck after edit + reopen');
        h.assertContains(win.document.getElementById('vncProcessNamePattern').value, processPatternOverride,
            'vncProcessNamePattern stuck after edit + reopen');
        // Back to the list - the next section (deactivate/reactivate) expects win.document to be
        // the templates list, not this edit form.
        h.clickByText(win, 'a', '« Back to templates', win.document);
        await h.waitForReload(win);
    });

    h.section('admin: deactivate / reactivate round-trip');

    await h.step('deactivating shows Active=no', async () => {
        const row = h.findRowByText(win, templateName);
        h.clickByText(win, 'button', 'Deactivate', row);
        await h.waitForReload(win);
        const updatedRow = h.findRowByText(win, templateName);
        h.assertContains(updatedRow.textContent, 'no', 'row shows inactive');
    });

    await h.step('reactivating shows Active=yes again', async () => {
        const row = h.findRowByText(win, templateName);
        h.clickByText(win, 'button', 'Reactivate', row);
        await h.waitForReload(win);
        const updatedRow = h.findRowByText(win, templateName);
        h.assertContains(updatedRow.textContent, 'yes', 'row shows active again');
    });

    h.section('admin: template cleanup');

    await h.step('deleting the template removes it from the list', async () => {
        const row = h.findRowByText(win, templateName);
        h.clickByText(win, 'button', 'Delete', row);
        await h.acceptAppDialog(win);
        await h.waitForReload(win);
        h.assertNotContains(h.bodyText(win), templateName, 'template no longer listed');
    });

    h.section('admin: package upload via the real form');

    const packageFilename = `harness-fixture-${stamp}.deb`;
    const packagesWin = h.openApp('nspawnmgr', `${location.origin}/nspawnmgr/admin/packages`);
    await h.step('packages page renders', async () => {
        await h.waitForReload(packagesWin);
        await h.waitForSelector(packagesWin, '#upload-form', { timeout: 8000 });
        h.assertContains(h.bodyText(packagesWin), 'Package cache', 'page heading visible');
    });

    await h.step('uploading a small fixture file lists it', async () => {
        h.setInputValue(packagesWin.document.getElementById('packageManager'), 'APT');
        h.setFileInput(packagesWin.document.getElementById('file'), packageFilename,
            'not a real package - web-test-harness fixture', 'application/octet-stream');
        h.fill(packagesWin, '#description', 'harness fixture - safe to delete');
        h.clickByText(packagesWin, 'button', 'Upload');
        await h.waitForReload(packagesWin);
        await h.waitForText(packagesWin, packageFilename, { timeout: 10000 });
        h.pass('uploaded package appears in the list');
    });

    h.section('admin: package cleanup');

    await h.step('deleting the package removes it from the list', async () => {
        const row = h.findRowByText(packagesWin, packageFilename);
        h.clickByText(packagesWin, 'button', 'Delete', row);
        await h.acceptAppDialog(packagesWin);
        await h.waitForReload(packagesWin);
        h.assertNotContains(h.bodyText(packagesWin), packageFilename, 'package no longer listed');
    });
}
