package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.TranslationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AccountPageController {

    private final CurrentUserProvider currentUserProvider;
    private final SettingsService settingsService;
    private final TranslationService translationService;

    public AccountPageController(CurrentUserProvider currentUserProvider, SettingsService settingsService,
                                  TranslationService translationService) {
        this.currentUserProvider = currentUserProvider;
        this.settingsService = settingsService;
        this.translationService = translationService;
    }

    @GetMapping("/account")
    public String account(Model model) {
        model.addAttribute("currentUser", currentUserProvider.get());
        model.addAttribute("logoutUrl", logoutUrl());
        model.addAttribute("availableLanguages", translationService.availableLocalesWithNames());
        return "account";
    }

    /**
     * nspawnmgr has no login/session of its own to end - CookiePreAuthFilter re-authenticates
     * from auth.war's own cookie on every request, so a real logout has to happen over there (see
     * LogoutServlet in the auth module: invalidates its SessionStore entry and expires the
     * cookie). "/login" -> "/logout" is safe: both are sibling servlet mappings in the exact same
     * auth.war (see auth/src/main/webapp/WEB-INF/web.xml), never two different deployments. Null
     * when no login URL is configured (matches SecurityConfig.loginRedirectUrl's own guard) - the
     * page hides the Log out button entirely in that case, since there's nowhere to send it.
     */
    private String logoutUrl() {
        String loginUrl = settingsService.authLoginUrl();
        if (loginUrl == null || loginUrl.isBlank() || !loginUrl.endsWith("/login")) {
            return null;
        }
        return loginUrl.substring(0, loginUrl.length() - "/login".length()) + "/logout";
    }
}
