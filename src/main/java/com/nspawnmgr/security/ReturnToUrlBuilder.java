package com.nspawnmgr.security;

import javax.servlet.http.HttpServletRequest;
import java.net.URI;

/**
 * Builds the {@code returnTo} target passed to auth.war's login page - shared by {@link
 * com.nspawnmgr.config.SecurityConfig}'s own unauthenticated-redirect entry point and {@link
 * com.nspawnmgr.web.AccountPageController}'s logout link, so both send the browser back with the
 * same, correctly-scoped URL.
 *
 * <p>The cookie auth.war issues is scoped to whichever host:port {@code authLoginUrl} itself
 * points at (that's the origin actually serving the {@code Set-Cookie} response, and no Domain
 * attribute is set) - not necessarily the host:port the browser used to reach nspawnmgr in the
 * first place (e.g. a request that came in as "localhost:8080" while HOST_EXTERNAL_HOSTNAME/
 * AUTH_LOGIN_URL point at the real hostname). Building returnTo from the request's own URL
 * verbatim reflects whatever the browser happened to use, so a mismatch there sends the user back
 * to a host the cookie was never scoped to - an infinite login loop with a genuinely valid cookie
 * the browser just never sends. Using {@code authLoginUrl}'s own scheme/authority instead
 * guarantees returnTo always matches wherever the cookie will actually be scoped; only the
 * path+query (which page to return to) comes from the real request.
 */
public final class ReturnToUrlBuilder {

    private ReturnToUrlBuilder() {
    }

    public static String build(HttpServletRequest request, String authLoginUrl) {
        URI loginUri = URI.create(authLoginUrl);
        String pathAndQuery = request.getRequestURI()
                + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        return loginUri.getScheme() + "://" + loginUri.getAuthority() + pathAndQuery;
    }
}
