// Drives the "+" add-machine dropdown in fragments/app-shell.html's topbar - same plain
// show/hide-via-class pattern as dialog.js elsewhere in this project, no framework.
(function () {
    const menu = document.getElementById('add-menu');
    const btn = document.getElementById('add-menu-btn');
    if (!menu || !btn) return;

    function close() {
        menu.classList.remove('add-menu-open');
        btn.setAttribute('aria-expanded', 'false');
    }

    function toggle() {
        const open = menu.classList.toggle('add-menu-open');
        btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    }

    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        toggle();
    });
    document.addEventListener('click', (e) => {
        if (!menu.contains(e.target)) close();
    });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') close();
    });
})();
