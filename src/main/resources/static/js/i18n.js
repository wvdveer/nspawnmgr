// Shared translation lookup for every page's own JS - reads the active locale's key->template
// table embedded once per page by fragments/app-shell.html (window.NSPAWNMGR_I18N), rather than a
// separate fetch. Deliberately the SAME naive {0}/{1} substitution TranslationService.java uses
// server-side (not java.text.MessageFormat semantics) - keeping both sides this simple is what
// keeps them behaving identically. Missing key renders visibly as [[missing:key]], same as the
// server-side fallback, so a gap is obvious rather than silently blank.
function t(key, ...args) {
    const table = window.NSPAWNMGR_I18N || {};
    let template = table[key];
    if (template === undefined) {
        return `[[missing:${key}]]`;
    }
    for (let i = 0; i < args.length; i++) {
        template = template.split(`{${i}}`).join(String(args[i]));
    }
    return template;
}
