const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const tbody = document.getElementById('key-sequences-body');
const status = document.getElementById('key-sequences-status');

function rowPayload(row) {
    return {
        label: row.querySelector('.ks-label').value,
        ctrl: row.querySelector('.ks-ctrl').checked,
        alt: row.querySelector('.ks-alt').checked,
        shift: row.querySelector('.ks-shift').checked,
        superKey: row.querySelector('.ks-super').checked,
        mainKeysym: Number(row.querySelector('.ks-main-key').value),
    };
}

async function reorder() {
    const orderedIds = Array.from(tbody.querySelectorAll('tr')).map((row) => row.getAttribute('data-id'));
    const response = await fetch(`${basePath}/api/admin/key-sequences/reorder`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ orderedIds }),
    });
    if (!response.ok) {
        status.textContent = t('general.failedPrefix', await response.text());
    }
}

tbody.addEventListener('click', async (event) => {
    const row = event.target.closest('tr');
    if (!row) {
        return;
    }
    const id = row.getAttribute('data-id');

    if (event.target.classList.contains('btn-save-sequence')) {
        const response = await fetch(`${basePath}/api/admin/key-sequences/${id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(rowPayload(row)),
        });
        if (!response.ok) {
            status.textContent = t('general.failedPrefix', await response.text());
            return;
        }
        status.textContent = t('page.adminKeySequences.saved');
        return;
    }

    if (event.target.classList.contains('btn-delete-sequence')) {
        if (!(await window.appDialog.confirm(t('page.adminKeySequences.confirmDelete')))) {
            return;
        }
        const response = await fetch(`${basePath}/api/admin/key-sequences/${id}`, { method: 'DELETE' });
        if (!response.ok) {
            status.textContent = t('general.failedPrefix', await response.text());
            return;
        }
        row.remove();
        return;
    }

    if (event.target.classList.contains('btn-move-up')) {
        const previous = row.previousElementSibling;
        if (previous) {
            tbody.insertBefore(row, previous);
            await reorder();
        }
        return;
    }

    if (event.target.classList.contains('btn-move-down')) {
        const next = row.nextElementSibling;
        if (next) {
            tbody.insertBefore(next, row);
            await reorder();
        }
    }
});

document.getElementById('btn-add-sequence').addEventListener('click', async () => {
    const label = document.getElementById('new-ks-label').value;
    if (!label) {
        return;
    }
    const payload = {
        label,
        ctrl: document.getElementById('new-ks-ctrl').checked,
        alt: document.getElementById('new-ks-alt').checked,
        shift: document.getElementById('new-ks-shift').checked,
        superKey: document.getElementById('new-ks-super').checked,
        mainKeysym: Number(document.getElementById('new-ks-main-key').value),
    };
    const response = await fetch(`${basePath}/api/admin/key-sequences`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    if (!response.ok) {
        status.textContent = t('general.failedPrefix', await response.text());
        return;
    }
    window.location.reload();
});
