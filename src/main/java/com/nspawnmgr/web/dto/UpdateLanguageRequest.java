package com.nspawnmgr.web.dto;

/** {@code language} is a 2-letter code matching an available lang/ file, or blank/null to clear
 *  the override and go back to auto-detecting from the browser's own Accept-Language header - see
 *  UserService#updatePreferredLanguage. */
public record UpdateLanguageRequest(
        String language
) {
}
