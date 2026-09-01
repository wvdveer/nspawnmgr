package com.nspawnmgr.web;

import com.nspawnmgr.service.LocaleResolutionService;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.TranslationService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Attributes every page-rendering controller's model needs, without threading them through each
 * controller's own method individually. First use: {@code sshApprovalRequired}, needed by
 * fragments/app-shell.html's Requests nav item (see RequestsPageController) on every single page,
 * not just the Requests page itself.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class GlobalModelAttributes {

    private final SettingsService settingsService;
    private final TranslationService translationService;
    private final LocaleResolutionService localeResolutionService;

    public GlobalModelAttributes(SettingsService settingsService, TranslationService translationService,
                                  LocaleResolutionService localeResolutionService) {
        this.settingsService = settingsService;
        this.translationService = translationService;
        this.localeResolutionService = localeResolutionService;
    }

    @ModelAttribute("sshApprovalRequired")
    public boolean sshApprovalRequired() {
        return settingsService.sshApprovalRequired();
    }

    /** Every template's own translation lookup - see {@link TranslationContext}. */
    @ModelAttribute("t")
    public TranslationContext translationContext(HttpServletRequest request) {
        return new TranslationContext(translationService, localeResolutionService.resolve(request));
    }

    /** The active locale's full key->template table, for fragments/app-shell.html to embed as an
     *  inline JSON blob ({@code window.NSPAWNMGR_I18N}) that {@code i18n.js} reads client-side for
     *  JS-built strings - avoids a separate fetch round-trip on every page load. */
    @ModelAttribute("translations")
    public Map<String, String> translations(HttpServletRequest request) {
        return translationService.allFor(localeResolutionService.resolve(request));
    }
}
