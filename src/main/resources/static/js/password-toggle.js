// Wraps a password <input> with a show/hide toggle button - see app.css's own .password-field/
// .password-toggle rules for the visual side. Exposed on window (not an IIFE-private helper)
// since callers need it both for inputs already in the page at load time and for inputs created
// dynamically afterward (e.g. detail.js's own per-user "change password" field).
const EYE_ICON = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8Z"/><circle cx="12" cy="12" r="3"/></svg>';
const EYE_OFF_ICON = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.9 4.24A10.94 10.94 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19M6.61 6.61C3.06 8.82 1 12 1 12s4 8 11 8a10.9 10.9 0 0 0 5.39-1.61M1 1l22 22"/><path d="M14.12 14.12a3 3 0 1 1-4.24-4.24"/></svg>';

function attachPasswordToggle(input) {
    if (!input || input.dataset.toggleAttached === 'true') {
        return;
    }
    input.dataset.toggleAttached = 'true';

    const wrapper = document.createElement('span');
    wrapper.className = 'password-field';
    input.parentNode.insertBefore(wrapper, input);
    wrapper.appendChild(input);

    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'password-toggle';
    toggle.setAttribute('aria-label', t('js.passwordToggle.show'));
    toggle.innerHTML = EYE_ICON;
    wrapper.appendChild(toggle);

    toggle.addEventListener('click', () => {
        const willShow = input.type === 'password';
        input.type = willShow ? 'text' : 'password';
        toggle.innerHTML = willShow ? EYE_OFF_ICON : EYE_ICON;
        toggle.setAttribute('aria-label', willShow ? t('js.passwordToggle.hide') : t('js.passwordToggle.show'));
    });
}
