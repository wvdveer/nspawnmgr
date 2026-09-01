package com.nspawnmgr.service;

import com.nspawnmgr.security.NspawnmgrUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Resolves the effective UI locale for the current request: an authenticated user's own
 * {@code User.preferredLanguage} wins if set; otherwise the browser's own Accept-Language header
 * (via the servlet API's own q-value-sorted {@link HttpServletRequest#getLocales()}), matched
 * against whatever locale codes {@link TranslationService} actually has a file for; otherwise
 * {@link TranslationService#DEFAULT_LOCALE}.
 *
 * <p>Deliberately does NOT go through {@code CurrentUserProvider} - that throws when there's no
 * authenticated principal in the security context, but a locale still needs resolving for
 * not-yet-authenticated pages (e.g. {@code /login-required} itself). Reads the security context
 * directly instead and treats "no principal yet" the same as "no override set".
 */
@Service
public class LocaleResolutionService {

    private final TranslationService translationService;

    public LocaleResolutionService(TranslationService translationService) {
        this.translationService = translationService;
    }

    public String resolve(HttpServletRequest request) {
        String preferred = currentUserPreferredLanguage();
        if (preferred != null && translationService.isAvailable(preferred)) {
            return preferred.toLowerCase(Locale.ROOT);
        }
        String fromHeader = matchAcceptLanguage(request);
        return fromHeader != null ? fromHeader : TranslationService.DEFAULT_LOCALE;
    }

    private String currentUserPreferredLanguage() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof NspawnmgrUserPrincipal principal) {
            return principal.user().getPreferredLanguage();
        }
        return null;
    }

    private String matchAcceptLanguage(HttpServletRequest request) {
        Enumeration<Locale> locales = request.getLocales();
        while (locales.hasMoreElements()) {
            String code = locales.nextElement().getLanguage().toLowerCase(Locale.ROOT);
            if (translationService.isAvailable(code)) {
                return code;
            }
        }
        return null;
    }
}
