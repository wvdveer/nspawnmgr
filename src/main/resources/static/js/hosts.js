const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.querySelectorAll('.btn-session-ssh').forEach((button) => {
    button.addEventListener('click', () => {
        const hostName = button.getAttribute('data-host-name');
        window.open(`${basePath}/hosts/${hostName}/session/ssh`, '_blank');
    });
});

document.querySelectorAll('.btn-session-rdp').forEach((button) => {
    button.addEventListener('click', () => {
        const hostName = button.getAttribute('data-host-name');
        window.open(`${basePath}/hosts/${hostName}/session/rdp`, '_blank');
    });
});

document.querySelectorAll('.btn-session-vnc').forEach((button) => {
    button.addEventListener('click', () => {
        const hostName = button.getAttribute('data-host-name');
        window.open(`${basePath}/hosts/${hostName}/session/vnc`, '_blank');
    });
});
