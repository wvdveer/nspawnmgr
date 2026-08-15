const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

async function setRole(userId, role) {
    const response = await fetch(`${basePath}/api/admin/users/${userId}/role`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ role }),
    });
    if (!response.ok) {
        await window.appDialog.alert('Failed: ' + await response.text());
        return;
    }
    window.location.reload();
}

document.querySelectorAll('.btn-promote').forEach((button) => {
    button.addEventListener('click', () => setRole(button.getAttribute('data-user-id'), 'ADMIN'));
});

document.querySelectorAll('.btn-demote').forEach((button) => {
    button.addEventListener('click', () => setRole(button.getAttribute('data-user-id'), 'USER'));
});
