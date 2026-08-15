// Replaces window.confirm()/window.alert() with a plain HTML <dialog> - the native browser
// versions block the whole page (including any automation driving it, and they can't be themed).
// A <dialog>.showModal() is just DOM: focusable, styleable, and closable by a normal click.
(function () {
    let dialogEl = null;

    function ensureDialog() {
        if (dialogEl) return dialogEl;
        dialogEl = document.createElement('dialog');
        dialogEl.className = 'app-dialog';
        dialogEl.innerHTML = `
            <p class="app-dialog-message"></p>
            <div class="app-dialog-actions">
                <button type="button" class="btn-primary app-dialog-cancel">Cancel</button>
                <button type="button" class="btn-primary app-dialog-ok">OK</button>
            </div>
        `;
        document.body.appendChild(dialogEl);
        return dialogEl;
    }

    function show(message, showCancel) {
        const dialog = ensureDialog();
        dialog.querySelector('.app-dialog-message').textContent = message;
        const cancelButton = dialog.querySelector('.app-dialog-cancel');
        const okButton = dialog.querySelector('.app-dialog-ok');
        cancelButton.style.display = showCancel ? '' : 'none';
        return new Promise((resolve) => {
            function finish(result) {
                dialog.close();
                okButton.removeEventListener('click', onOk);
                cancelButton.removeEventListener('click', onCancel);
                dialog.removeEventListener('cancel', onCancel);
                resolve(result);
            }
            function onOk() {
                finish(true);
            }
            function onCancel(event) {
                event.preventDefault();
                finish(false);
            }
            okButton.addEventListener('click', onOk);
            cancelButton.addEventListener('click', onCancel);
            dialog.addEventListener('cancel', onCancel);
            dialog.showModal();
            okButton.focus();
        });
    }

    window.appDialog = {
        // Resolves true/false - replaces `if (!confirm(...)) return;`
        confirm: (message) => show(message, true),
        // Resolves once dismissed - replaces `alert(...)`
        alert: (message) => show(message, false),
    };
})();
