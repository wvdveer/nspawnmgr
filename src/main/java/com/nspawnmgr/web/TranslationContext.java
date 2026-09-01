package com.nspawnmgr.web;

import com.nspawnmgr.service.TranslationService;

/** Per-request, Thymeleaf-callable translation lookup bound to one resolved locale - a template
 *  calls {@code ${t.get('nav.machines')}} / {@code ${t.get('error.container.notRunning', name)}}
 *  without needing to know or pass the locale itself each time. See {@link GlobalModelAttributes}'s
 *  own {@code "t"} model attribute for how this gets into every page. */
public class TranslationContext {

    private final TranslationService translationService;
    private final String locale;

    public TranslationContext(TranslationService translationService, String locale) {
        this.translationService = translationService;
        this.locale = locale;
    }

    public String get(String key, Object... args) {
        return translationService.get(locale, key, args);
    }

    public String getLocale() {
        return locale;
    }
}
