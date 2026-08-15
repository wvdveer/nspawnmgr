package com.nspawnmgr.security;

import com.nspawnmgr.domain.Role;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserService;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Trusts an upstream-established identity: reads the configured cookie, resolves it to an
 * identity via USER_ID_URL (cached), and upserts/loads the matching local User. Not a
 * AbstractPreAuthenticatedProcessingFilter subclass because identity extraction requires an
 * outbound HTTP call plus caching, not just reading a header.
 */
public class CookiePreAuthFilter extends OncePerRequestFilter {

    private final SettingsService settingsService;
    private final IdentityResolver identityResolver;
    private final IdentityCache identityCache;
    private final UserService userService;

    public CookiePreAuthFilter(SettingsService settingsService, IdentityResolver identityResolver,
                                IdentityCache identityCache, UserService userService) {
        this.settingsService = settingsService;
        this.identityResolver = identityResolver;
        this.identityCache = identityCache;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String cookieValue = readCookie(request);
        if (cookieValue != null) {
            Optional<ExternalIdentity> identity = identityCache.get(cookieValue)
                    .orElseGet(() -> {
                        Optional<ExternalIdentity> resolved = identityResolver.resolve(cookieValue);
                        identityCache.put(cookieValue, resolved);
                        return resolved;
                    });
            identity.ifPresent(this::authenticate);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(ExternalIdentity identity) {
        try {
            User user = userService.upsert(identity);
            var principal = new NspawnmgrUserPrincipal(user);
            List<SimpleGrantedAuthority> authorities = user.getRole() == Role.ADMIN
                    ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                    : List.of(new SimpleGrantedAuthority("ROLE_USER"));
            var authentication = new PreAuthenticatedAuthenticationToken(principal, null, authorities);
            authentication.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception e) {
            throw new AuthenticationServiceException("Failed to resolve authenticated user", e);
        }
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (settingsService.authCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
