package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.security.ReturnToUrlBuilder;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.TranslationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
    public String account(Model model, HttpServletRequest request) {
        model.addAttribute("currentUser", currentUserProvider.get());
        model.addAttribute("logoutUrl", logoutUrl(request));
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
     *
     * <p>Carries a returnTo pointing back to this same /account page (see ReturnToUrlBuilder's own
     * javadoc for why it's built from the resolved login URL's own scheme/authority - see
     * {@link com.nspawnmgr.service.SettingsService#authLoginUrlForRequest} - not this request's)
     * so LogoutServlet's own "Log in again" link can echo it into its own login redirect - without
     * this, that link used to drop the user on auth.war's generic "logged in, close this page"
     * confirmation instead of ever coming back to nspawnmgr.
     */
    private String logoutUrl(HttpServletRequest request) {
        String loginUrl = settingsService.authLoginUrlForRequest(request);
        if (loginUrl == null || loginUrl.isBlank() || !loginUrl.endsWith("/login")) {
            return null;
        }
        String logoutBase = loginUrl.substring(0, loginUrl.length() - "/login".length()) + "/logout";
        String returnTo = URLEncoder.encode(ReturnToUrlBuilder.build(request, loginUrl), StandardCharsets.UTF_8);
        return logoutBase + "?returnTo=" + returnTo;
    }
}
