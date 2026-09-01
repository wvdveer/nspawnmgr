package com.nspawnmgr.service;

import com.nspawnmgr.config.LangProperties;

/**
 * A real {@link UserMessages}, backed by a real {@link TranslationService} loading the project's
 * actual {@code lang/en.json} (via {@code user.dir}, same convention {@code application-dev.yml}
 * uses) - not a Mockito mock, since several tests assert on real English message content. Always
 * resolves to English: {@link LocaleResolutionService#resolve} needs an {@code HttpServletRequest},
 * which {@link UserMessages#currentLocale()} has none of outside a real request thread, falling
 * back to {@link TranslationService#DEFAULT_LOCALE} - exactly right for a plain unit test.
 */
public final class TestUserMessages {

    private TestUserMessages() {
    }

    public static UserMessages create() {
        TranslationService translationService = new TranslationService(new LangProperties(System.getProperty("user.dir") + "/lang"));
        LocaleResolutionService localeResolutionService = new LocaleResolutionService(translationService);
        return new UserMessages(translationService, localeResolutionService);
    }
}
