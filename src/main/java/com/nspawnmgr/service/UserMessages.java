package com.nspawnmgr.service;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Convenience facade over {@link TranslationService}/{@link LocaleResolutionService} for the ~165
 * exception-throw sites in {@code service/} and {@code cli/real/} that build a user-facing message
 * (one that can reach the browser via {@code ApiExceptionHandler}, as opposed to a log line - log
 * messages are explicitly out of scope for translation). Resolves the current request's locale via
 * {@link RequestContextHolder} so call sites don't need an {@code HttpServletRequest} threaded all
 * the way down to wherever the exception is actually thrown.
 *
 * <p>Falls back to {@link TranslationService#DEFAULT_LOCALE} when called from outside a request
 * thread (e.g. a {@code @Scheduled} background task) - those exceptions are typically only ever
 * logged, not returned to a specific browser, so there's no real locale to resolve anyway.
 */
@Service
public class UserMessages {

    private final TranslationService translationService;
    private final LocaleResolutionService localeResolutionService;

    public UserMessages(TranslationService translationService, LocaleResolutionService localeResolutionService) {
        this.translationService = translationService;
        this.localeResolutionService = localeResolutionService;
    }

    public String get(String key, Object... args) {
        return translationService.get(currentLocale(), key, args);
    }

    private String currentLocale() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return TranslationService.DEFAULT_LOCALE;
        }
        return localeResolutionService.resolve(attrs.getRequest());
    }
}
