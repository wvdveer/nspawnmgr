// Core DOM-testing helpers for driving the real, server-rendered nspawnmgr/auth pages from a
// plain browser tab. window.open() gives back a same-origin WindowProxy here (this harness is
// deployed into the same Tomcat instance, at http://localhost:8080/test-harness/, alongside
// /nspawnmgr and /auth - see tools/scripts/start-dev-stack.sh), so its `document` can be queried
// and manipulated directly - no postMessage bridge, no browser-automation framework, no npm
// dependency. Modeled on C:\projects\rust\usermgr\tools\web-test-harness, trimmed of the parts
// specific to that project's WASM/localStorage-consent-banner shape (nspawnmgr is plain
// server-rendered HTML with full-page navigations, not a SPA).
'use strict';

const results = { pass: 0, fail: 0 };
let logEl = null;

function attachLog(el) {
    logEl = el;
}

function logLine(text) {
    if (logEl) {
        logEl.textContent += text + '\n';
        logEl.scrollTop = logEl.scrollHeight;
    }
    console.log(text);
}

function section(name) {
    logLine(`\n== ${name} ==`);
}

function pass(desc) {
    results.pass++;
    logLine(`  ok - ${desc}`);
}

function fail(desc, detail) {
    results.fail++;
    logLine(`  FAIL - ${desc}${detail ? ` (${detail})` : ''}`);
}

function resetResults() {
    results.pass = 0;
    results.fail = 0;
    if (logEl) logEl.textContent = '';
}

function summary() {
    logLine(`\n== Summary: ${results.pass} passed, ${results.fail} failed ==`);
    return results.fail === 0;
}

// Opens (or reuses, by name) a tab. Reusing by name means calling this twice with the same `name`
// navigates the existing tab rather than spawning a new one, matching how a human would work with
// a pinned set of test tabs.
function openApp(name, url) {
    const win = window.open(url, name);
    if (!win) {
        throw new Error(
            `window.open() blocked for '${name}' - allow popups for this page in your browser`
        );
    }
    return win;
}

// predicate may be sync or async (return a value or a Promise of one) - `await`ing a plain,
// non-Promise value resolves to that same value immediately, so this handles both uniformly.
// Deliberately NOT `value = predicate()` + truthy-check on that directly: a pending Promise object
// is itself always truthy, so an async predicate would (without the await) look "done" on its very
// first tick regardless of what it eventually resolves to, skipping every retry entirely.
function waitFor(predicate, { timeout = 5000, interval = 100, label = 'condition' } = {}) {
    return new Promise((resolve, reject) => {
        const start = Date.now();
        (async function poll() {
            let value;
            try {
                value = await predicate();
            } catch (e) {
                value = undefined; // e.g. the window navigated mid-check; keep polling
            }
            if (value) return resolve(value);
            if (Date.now() - start > timeout) {
                return reject(new Error(`timed out waiting for ${label}`));
            }
            setTimeout(poll, interval);
        })();
    });
}

// nspawnmgr's pages are plain server-rendered HTML with real full-page navigations (a form
// submit, or `window.location.href = ...`/`.reload()` after a fetch succeeds) rather than a SPA
// patching its own DOM - so waiting for "the page reloaded" is needed after almost every action,
// not just the first open. Every caller here triggers the reload via a click whose handler
// `await`s a fetch() before calling location.reload()/setting location.href (see detail.js /
// admin-templates.js / admin-packages.js / create.js) - so at the moment this function is called
// (synchronously, right after the click), the reload hasn't started yet and win.document is still
// the pre-click Document. Navigation replaces that Document object wholesale, so comparing
// identity - not just readyState - is what actually detects "the new page is here": checking
// readyState alone races the reload and can resolve against the still-complete *old* document
// before the browser has even begun unloading it, which reads as a no-op update every time.
function waitForReload(win, opts) {
    const priorDocument = win.document;
    return waitFor(() => win.document !== priorDocument && win.document.readyState === 'complete' && win.document.body, {
        label: 'page load',
        ...opts,
    });
}

function waitForSelector(win, selector, opts) {
    return waitFor(() => win.document.querySelector(selector), {
        label: `selector '${selector}'`,
        ...opts,
    });
}

function waitForText(win, text, opts) {
    return waitFor(() => (win.document.body.textContent.includes(text) ? true : null), {
        label: `text '${text}'`,
        ...opts,
    });
}

// Sets an <input>'s value through its native setter and fires a real 'input' event, so any
// oninput/onchange listener sees the change exactly as it would from real typing.
function setInputValue(el, value) {
    const proto = Object.getPrototypeOf(el);
    const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(el, value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
}

function fill(win, selector, value) {
    const el = win.document.querySelector(selector);
    if (!el) throw new Error(`fill: no element matching '${selector}'`);
    setInputValue(el, value);
    return el;
}

function check(win, selector, checked) {
    const el = win.document.querySelector(selector);
    if (!el) throw new Error(`check: no element matching '${selector}'`);
    if (el.checked !== checked) el.click();
    return el;
}

function click(win, selector) {
    const el = win.document.querySelector(selector);
    if (!el) throw new Error(`click: no element matching '${selector}'`);
    el.click();
    return el;
}

// Finds the first `tag` element whose trimmed text matches exactly - nspawnmgr's Thymeleaf pages
// don't use stable class names/ids for most buttons, just visible text (see e.g. the Edit/Delete
// buttons in admin/templates.html). Pass a `root` (e.g. a specific <tr>) to scope the search.
function clickByText(win, tag, text, root) {
    const scope = root || win.document;
    const el = [...scope.querySelectorAll(tag)].find((e) => e.textContent.trim() === text);
    if (!el) throw new Error(`clickByText: no <${tag}> with text "${text}"`);
    el.click();
    return el;
}

function findRowByText(win, text) {
    const row = [...win.document.querySelectorAll('tr')].find((tr) =>
        tr.textContent.includes(text)
    );
    if (!row) throw new Error(`findRowByText: no <tr> containing "${text}"`);
    return row;
}

function bodyText(win) {
    return win.document.body.textContent.replace(/\s+/g, ' ').trim();
}

// A handful of nspawnmgr actions (delete container, delete template/package, take ownership) ask
// for confirmation before proceeding via window.appDialog.confirm() (see detail.js/
// admin-templates.js/admin-packages.js) - a plain HTML <dialog>, not a blocking native confirm(),
// specifically so this harness (and any other automation) can click through it like any other
// button. Call this *after* the click that opens the dialog - it waits for the dialog to actually
// appear before clicking its OK button, then waits for it to close again (appDialog's Promise only
// resolves - and the caller's own subsequent fetch/reload only starts - once that happens).
async function acceptAppDialog(win) {
    await waitFor(() => win.document.querySelector('dialog.app-dialog[open]'), { label: 'confirmation dialog to open' });
    click(win, 'dialog.app-dialog .app-dialog-ok');
    await waitFor(() => !win.document.querySelector('dialog.app-dialog[open]'), { label: 'confirmation dialog to close' });
}

// Assigns a real File to a file <input> via the DataTransfer API - the only way a script is
// allowed to populate input.files itself (direct assignment is blocked). Used by the packages
// admin suite to drive a real multipart upload through the actual form, not a raw fetch() that
// would bypass the UI entirely.
function setFileInput(el, filename, content, mimeType) {
    const file = new File([content], filename, { type: mimeType });
    const dataTransfer = new DataTransfer();
    dataTransfer.items.add(file);
    el.files = dataTransfer.files;
    el.dispatchEvent(new Event('change', { bubbles: true }));
}

function assertContains(actual, needle, desc) {
    if (String(actual).includes(needle)) {
        pass(desc);
    } else {
        fail(desc, `expected to find '${needle}' in: ${actual}`);
    }
}

function assertNotContains(actual, needle, desc) {
    if (!String(actual).includes(needle)) {
        pass(desc);
    } else {
        fail(desc, `did not expect to find '${needle}' in: ${actual}`);
    }
}

async function step(desc, fn) {
    try {
        await fn();
    } catch (e) {
        fail(desc, e.message);
        throw e; // let the caller decide whether to abort the rest of the suite
    }
}

window.harness = {
    section,
    pass,
    fail,
    resetResults,
    summary,
    attachLog,
    openApp,
    waitFor,
    waitForReload,
    waitForSelector,
    waitForText,
    setInputValue,
    fill,
    check,
    click,
    clickByText,
    findRowByText,
    bodyText,
    acceptAppDialog,
    setFileInput,
    assertContains,
    assertNotContains,
    step,
    results,
};
