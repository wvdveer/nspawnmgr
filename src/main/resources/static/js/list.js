const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

document.querySelectorAll('.btn-session-ssh').forEach((button) => {
    button.addEventListener('click', () => {
        const containerId = button.getAttribute('data-container-id');
        window.open(`${basePath}/containers/${containerId}/session/ssh`, '_blank');
    });
});

document.querySelectorAll('.btn-session-rdp').forEach((button) => {
    button.addEventListener('click', () => {
        const containerId = button.getAttribute('data-container-id');
        window.open(`${basePath}/containers/${containerId}/session/rdp`, '_blank');
    });
});

document.querySelectorAll('.btn-session-vnc').forEach((button) => {
    button.addEventListener('click', () => {
        const containerId = button.getAttribute('data-container-id');
        window.open(`${basePath}/containers/${containerId}/session/vnc`, '_blank');
    });
});
