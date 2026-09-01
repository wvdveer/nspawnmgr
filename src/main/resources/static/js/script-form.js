const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const containerId = document.body.getAttribute('data-container-id');
let scriptId = document.body.getAttribute('data-script-id') || null;

const nameInput = document.getElementById('name');
const bodyInput = document.getElementById('scriptBody');
const statusEl = document.getElementById('status');
const outputPanel = document.getElementById('output-panel');
const outputLinesEl = document.getElementById('output-lines');
const saveButton = document.getElementById('btn-save');
const executeButton = document.getElementById('btn-execute');
const abortButton = document.getElementById('btn-abort');

let currentRunId = null;
let pollHandle = null;

function showStatus(message, isError) {
    statusEl.textContent = message;
    statusEl.style.color = isError ? 'var(--error-color)' : 'var(--success-color)';
}

function scriptsUrl(suffix) {
    const base = `${basePath}/api/containers/${containerId}/scripts`;
    return scriptId ? `${base}/${scriptId}${suffix}` : `${base}${suffix}`;
}

async function onSaveClick() {
    const name = nameInput.value;
    const scriptBody = bodyInput.value;
    const method = scriptId ? 'PUT' : 'POST';
    const response = await fetch(scriptsUrl(''), {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, scriptBody }),
    });
    if (!response.ok) {
        showStatus(t('general.failedPrefix', await response.text()), true);
        return;
    }
    const result = await response.json();
    if (!scriptId) {
        // A brand-new script now has an id - move to its real URL, same as saving from the "new"
        // page always would, matching every other form in this app (reload/redirect after a
        // successful mutation).
        window.location.href = `${basePath}/containers/${containerId}/scripts/${result.id}`;
        return;
    }
    showStatus(t('js.status.saved'), false);
}

async function onDeleteClick() {
    if (!await window.appDialog.confirm(t('js.confirm.deleteScript'))) {
        return;
    }
    const response = await fetch(scriptsUrl(''), { method: 'DELETE' });
    if (!response.ok) {
        showStatus(t('general.failedPrefix', await response.text()), true);
        return;
    }
    window.location.href = `${basePath}/containers/${containerId}`;
}

function ensureDeleteButtonExists() {
    if (document.getElementById('btn-delete')) {
        return;
    }
    const deleteButton = document.createElement('button');
    deleteButton.id = 'btn-delete';
    deleteButton.type = 'button';
    deleteButton.className = 'btn-danger';
    deleteButton.textContent = t('button.delete');
    deleteButton.addEventListener('click', onDeleteClick);
    document.getElementById('script-form').appendChild(deleteButton);
}

function runStatusUrl(runId) {
    return `${basePath}/api/containers/${containerId}/scripts/runs/${runId}`;
}

function setRunningUi(running) {
    saveButton.disabled = running;
    executeButton.disabled = running;
    abortButton.style.display = running ? '' : 'none';
}

async function onExecuteClick() {
    const name = nameInput.value;
    const scriptBody = bodyInput.value;
    showStatus(t('js.status.running'), false);
    setRunningUi(true);
    // Always saves the current body first (create if this is a brand-new script, else update) -
    // the single Execute button never runs stale content, and there's no separate save-then-run
    // round trip to think about.
    const response = await fetch(scriptsUrl('/run-async'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, scriptBody }),
    });
    if (!response.ok) {
        setRunningUi(false);
        showStatus(t('general.failedPrefix', await response.text()), true);
        return;
    }
    const { runId, scriptId: newScriptId } = await response.json();
    currentRunId = runId;
    if (!scriptId) {
        scriptId = newScriptId;
        window.history.replaceState(null, '', `${basePath}/containers/${containerId}/scripts/${scriptId}`);
        ensureDeleteButtonExists();
    }
    pollHandle = setInterval(() => pollRunStatus(runId), 1000);
}

async function pollRunStatus(runId) {
    const response = await fetch(runStatusUrl(runId));
    if (!response.ok) {
        return;
    }
    const status = await response.json();
    if (status.state === 'RUNNING') {
        return;
    }
    clearInterval(pollHandle);
    pollHandle = null;
    setRunningUi(false);
    if (currentRunId === runId) {
        currentRunId = null;
    }
    const message = status.state === 'ABORTED' ? t('js.status.aborted') : t('js.status.finished', status.exitCode);
    showStatus(message, status.state !== 'COMPLETED' || status.exitCode !== 0);
    renderOutput(status.lines);
}

async function onAbortClick() {
    if (!currentRunId) {
        return;
    }
    if (!await window.appDialog.confirm(t('js.confirm.abortScript'))) {
        return;
    }
    await fetch(runStatusUrl(currentRunId) + '/abort', { method: 'POST' });
}

document.getElementById('btn-save')?.addEventListener('click', onSaveClick);
document.getElementById('btn-delete')?.addEventListener('click', onDeleteClick);
document.getElementById('btn-execute')?.addEventListener('click', onExecuteClick);
abortButton?.addEventListener('click', onAbortClick);

function formatTimestamp(date) {
    const pad = (n, len) => String(n).padStart(len || 2, '0');
    return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
}

// New timestamp heading whenever a line arrives more than 1000ms after the previous one; lines
// within that window are grouped under the same heading. Each line is tagged stdout/stderr.
function renderOutput(lines) {
    outputLinesEl.innerHTML = '';
    outputPanel.style.display = lines.length > 0 ? '' : 'none';
    let lastTimestamp = null;
    let currentGroup = null;
    lines.forEach((line) => {
        const timestamp = new Date(line.timestamp);
        if (lastTimestamp === null || (timestamp - lastTimestamp) > 1000) {
            const heading = document.createElement('div');
            heading.className = 'output-timestamp';
            heading.textContent = formatTimestamp(timestamp);
            outputLinesEl.appendChild(heading);
            currentGroup = document.createElement('div');
            currentGroup.className = 'output-group';
            outputLinesEl.appendChild(currentGroup);
        }
        const lineEl = document.createElement('div');
        lineEl.className = 'output-line output-' + line.source.toLowerCase();
        const tag = document.createElement('span');
        tag.className = 'output-tag';
        tag.textContent = line.source === 'STDOUT' ? t('js.outputTag.stdout') : t('js.outputTag.stderr');
        const text = document.createElement('span');
        text.className = 'output-text';
        text.textContent = line.text;
        lineEl.append(tag, text);
        currentGroup.appendChild(lineEl);
        lastTimestamp = timestamp;
    });
}
