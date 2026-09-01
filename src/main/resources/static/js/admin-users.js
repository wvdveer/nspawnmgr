const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');
const allRoles = (document.body.getAttribute('data-all-role-names') || '').split(',').filter(Boolean);

async function setRole(userId, role) {
    const response = await fetch(`${basePath}/api/admin/users/${userId}/role`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ role }),
    });
    if (!response.ok) {
        await window.appDialog.alert(t('general.failedPrefix', await response.text()));
        return;
    }
    window.location.reload();
}

let openMenu = null;

function closeMenu() {
    openMenu?.remove();
    openMenu = null;
}

document.addEventListener('click', (e) => {
    if (openMenu && !openMenu.contains(e.target) && !e.target.closest('.role-trigger')) {
        closeMenu();
    }
});
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeMenu();
});

// Role badge on each user card (admin/users.html) - clicking it opens a small context menu
// listing "Change to X" for every role other than the user's current one. Built from
// data-all-role-names (see AdminUserPageController) rather than hardcoding USER/ADMIN, so this
// keeps working unchanged if a third role is ever added.
document.querySelectorAll('.role-trigger').forEach((badge) => {
    badge.addEventListener('click', (e) => {
        e.stopPropagation();
        if (openMenu) {
            closeMenu();
            return;
        }
        const userId = badge.getAttribute('data-user-id');
        const currentRole = badge.getAttribute('data-role');
        const menu = document.createElement('div');
        menu.className = 'context-menu';
        allRoles.filter((role) => role !== currentRole).forEach((role) => {
            const item = document.createElement('button');
            item.type = 'button';
            item.textContent = t('js.changeRoleTo', t('role.' + role));
            item.addEventListener('click', () => {
                closeMenu();
                setRole(userId, role);
            });
            menu.appendChild(item);
        });
        document.body.appendChild(menu);
        const rect = badge.getBoundingClientRect();
        menu.style.top = `${rect.bottom + 4}px`;
        menu.style.left = `${rect.left}px`;
        openMenu = menu;
    });
});
