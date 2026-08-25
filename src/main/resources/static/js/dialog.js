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
            <input type="text" class="app-dialog-input" style="display:none;"/>
            <div class="app-dialog-actions">
                <button type="button" class="btn-primary app-dialog-cancel">Cancel</button>
                <button type="button" class="btn-primary app-dialog-ok">OK</button>
            </div>
        `;
        document.body.appendChild(dialogEl);
        return dialogEl;
    }

    /** withInput: OK resolves the typed string (Cancel resolves null) instead of true/false -
     *  used by prompt() below. */
    function show(message, showCancel, withInput, defaultValue) {
        const dialog = ensureDialog();
        dialog.querySelector('.app-dialog-message').textContent = message;
        const input = dialog.querySelector('.app-dialog-input');
        input.style.display = withInput ? '' : 'none';
        input.value = withInput && defaultValue ? defaultValue : '';
        const cancelButton = dialog.querySelector('.app-dialog-cancel');
        const okButton = dialog.querySelector('.app-dialog-ok');
        cancelButton.style.display = showCancel ? '' : 'none';
        return new Promise((resolve) => {
            function finish(result) {
                dialog.close();
                okButton.removeEventListener('click', onOk);
                cancelButton.removeEventListener('click', onCancel);
                dialog.removeEventListener('cancel', onCancel);
                input.removeEventListener('keydown', onInputKeydown);
                resolve(result);
            }
            function onOk() {
                finish(withInput ? input.value : true);
            }
            function onCancel(event) {
                event.preventDefault();
                finish(withInput ? null : false);
            }
            function onInputKeydown(event) {
                if (event.key === 'Enter') {
                    event.preventDefault();
                    onOk();
                }
            }
            okButton.addEventListener('click', onOk);
            cancelButton.addEventListener('click', onCancel);
            dialog.addEventListener('cancel', onCancel);
            if (withInput) {
                input.addEventListener('keydown', onInputKeydown);
            }
            dialog.showModal();
            (withInput ? input : okButton).focus();
        });
    }

    window.appDialog = {
        // Resolves true/false - replaces `if (!confirm(...)) return;`
        confirm: (message) => show(message, true, false),
        // Resolves once dismissed - replaces `alert(...)`
        alert: (message) => show(message, false, false),
        // Resolves the typed string, or null if cancelled - replaces `prompt(...)`.
        prompt: (message, defaultValue) => show(message, true, true, defaultValue),
    };
})();
