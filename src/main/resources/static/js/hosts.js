const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.querySelectorAll('.btn-session-ssh').forEach((button) => {
    button.addEventListener('click', () => {
        const hostId = button.getAttribute('data-host-id');
        window.open(`${basePath}/hosts/${hostId}/session/ssh`, '_blank');
    });
});

document.querySelectorAll('.btn-session-rdp').forEach((button) => {
    button.addEventListener('click', () => {
        const hostId = button.getAttribute('data-host-id');
        window.open(`${basePath}/hosts/${hostId}/session/rdp`, '_blank');
    });
});

document.querySelectorAll('.btn-session-vnc').forEach((button) => {
    button.addEventListener('click', () => {
        const hostId = button.getAttribute('data-host-id');
        window.open(`${basePath}/hosts/${hostId}/session/vnc`, '_blank');
    });
});
