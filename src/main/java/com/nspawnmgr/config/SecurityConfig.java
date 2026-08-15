package com.nspawnmgr.config;

import com.nspawnmgr.security.CookiePreAuthFilter;
import com.nspawnmgr.security.IdentityCache;
import com.nspawnmgr.security.IdentityResolver;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

// WebSecurityConfigurerAdapter (deprecated but still present/working in Security 5.7, bundled
// with Boot 2.7) rather than the SecurityFilterChain-bean style, since the latter's
// requestMatchers(String...) convenience overload doesn't exist until Security 5.8/Boot 3.x.
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    private final SettingsService settingsService;
    private final IdentityResolver identityResolver;
    private final IdentityCache identityCache;
    private final UserService userService;

    public SecurityConfig(SettingsService settingsService, IdentityResolver identityResolver,
                           IdentityCache identityCache, UserService userService) {
        this.settingsService = settingsService;
        this.identityResolver = identityResolver;
        this.identityCache = identityCache;
        this.userService = userService;
    }

    @Bean
    public CookiePreAuthFilter cookiePreAuthFilter() {
        return new CookiePreAuthFilter(settingsService, identityResolver, identityCache, userService);
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .httpBasic().disable()
                .formLogin().disable()
                .authorizeRequests()
                // /api/bootstrap/self-hosted-infra-status: polled by the DB setup wizard's own
                // progress page before any admin has ever logged in - see
                // SelfHostedBootstrapStatusController's own javadoc.
                // /internal/pam-auth/**: called by a container's own pam_nspawnmgr verify script,
                // which has no nspawnmgr login cookie - authenticates itself via its own bearer
                // token instead, see PamAuthVerifyController's own javadoc.
                .antMatchers("/css/**", "/js/**", "/login-required", "/api/bootstrap/**", "/internal/pam-auth/**").permitAll()
                .antMatchers("/admin/**", "/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
                .and()
                .addFilterBefore(cookiePreAuthFilter(), BasicAuthenticationFilter.class)
                .exceptionHandling()
                .authenticationEntryPoint((request, response, authException) ->
                        response.sendRedirect(loginRedirectUrl(request)));
    }

    // Sends the browser to the auth app's own login page (if configured) with a returnTo pointing
    // back to the page that was originally requested, so it can redirect here once logged in.
    // Falls back to the static /login-required page when no login URL is configured.
    private String loginRedirectUrl(HttpServletRequest request) {
        String loginUrl = settingsService.authLoginUrl();
        if (loginUrl == null || loginUrl.isBlank()) {
            // sendRedirect() interprets a leading "/" as relative to the server root, not this
            // webapp's context path - needs to be explicit now that nspawnmgr runs at /nspawnmgr
            // rather than ROOT (see docs/administrator-guide.md's Tomcat section).
            return request.getContextPath() + "/login-required";
        }
        String returnTo = URLEncoder.encode(returnToUrl(request, loginUrl), StandardCharsets.UTF_8);
        return loginUrl + (loginUrl.contains("?") ? "&" : "?") + "returnTo=" + returnTo;
    }

    // The cookie auth.war issues is scoped to whichever host:port loginUrl itself points at (that's
    // the origin actually serving the Set-Cookie response, and no Domain attribute is set) - not
    // necessarily the host:port the browser used to reach nspawnmgr in the first place (e.g. a
    // request that came in as "localhost:8080" while HOST_EXTERNAL_HOSTNAME/AUTH_LOGIN_URL point at
    // the real hostname). Building returnTo from request.getRequestURL() reflected whatever the
    // browser happened to use, so a mismatch here sent the user back to a host the cookie was never
    // scoped to - an infinite login loop with a genuinely valid cookie the browser just never sends.
    // Using loginUrl's own scheme/authority instead guarantees returnTo always matches wherever the
    // cookie will actually be scoped; only the path+query (which page to return to) comes from the
    // real request.
    private String returnToUrl(HttpServletRequest request, String loginUrl) {
        URI loginUri = URI.create(loginUrl);
        String pathAndQuery = request.getRequestURI()
                + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        return loginUri.getScheme() + "://" + loginUri.getAuthority() + pathAndQuery;
    }
}
