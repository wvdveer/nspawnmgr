const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

// Machines card grid (containers/list.html) - access-pill buttons, distinguished by protocol via
// their own SSH/RDP/VNC class (see fragments/app-shell.html's sibling CSS) rather than a dedicated
// btn-session-* class per protocol.
document.querySelectorAll('.access-pill.SSH').forEach((button) => {
    button.addEventListener('click', () => {
        const containerName = button.getAttribute('data-container-name');
        window.open(`${basePath}/containers/${containerName}/session/ssh`, '_blank');
    });
});

document.querySelectorAll('.access-pill.RDP').forEach((button) => {
    button.addEventListener('click', () => {
        const containerName = button.getAttribute('data-container-name');
        window.open(`${basePath}/containers/${containerName}/session/rdp`, '_blank');
    });
});

document.querySelectorAll('.access-pill.VNC').forEach((button) => {
    button.addEventListener('click', () => {
        const containerName = button.getAttribute('data-container-name');
        window.open(`${basePath}/containers/${containerName}/session/vnc`, '_blank');
    });
});
