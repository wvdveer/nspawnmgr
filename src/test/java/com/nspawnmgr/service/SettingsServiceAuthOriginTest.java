package com.nspawnmgr.service;

import com.nspawnmgr.config.AuthProperties;
import com.nspawnmgr.config.DnsProperties;
import com.nspawnmgr.config.GuacamoleProperties;
import com.nspawnmgr.config.HostProperties;
import com.nspawnmgr.config.NspawnProperties;
import com.nspawnmgr.config.ProvisioningProperties;
import com.nspawnmgr.config.SshProperties;
import com.nspawnmgr.domain.AppSettings;
import com.nspawnmgr.guacamole.GuacamolePropertiesSchema;
import com.nspawnmgr.repository.AppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for {@link SettingsService#authLoginUrlForRequest} - lets a request that
 * comes in on an admin-approved additional origin (e.g. reverse-proxied under a second public
 * hostname) get a login redirect/returnTo built against ITS OWN origin, instead of always bouncing
 * to authLoginUrl's fixed origin, without changing that existing single-origin behavior for every
 * other host. See SecurityConfig.loginRedirectUrl()/AccountPageController.logoutUrl() - both call
 * through this same method.
 */
class SettingsServiceAuthOriginTest {

    private static final String LOGIN_URL = "http://acer.patersonst:12345/auth/login";

    private AppSettings row;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        AppSettingsRepository appSettingsRepository = mock(AppSettingsRepository.class);
        row = new AppSettings();
        row.setId(1L);
        row.setAuthLoginUrl(LOGIN_URL);
        when(appSettingsRepository.findById(1L)).thenReturn(Optional.of(row));

        service = new SettingsService(appSettingsRepository,
                new GuacamoleProperties(null, null, null, null, null),
                new HostProperties(null, null),
                new AuthProperties(null, null, null, null, null, null, null, 0, 0, null,
                        System.getProperty("java.io.tmpdir") + "/nspawnmgr-test-auth.properties"),
                new ProvisioningProperties(null, 0),
                new SshProperties(null, 0, null, null, null, 0, false),
                new NspawnProperties(null, null, null, null),
                new DnsProperties("/tmp/dns-hosts", "/tmp/dns-upstream.conf", "1.1.1.1,9.9.9.9"),
                TestUserMessages.create(),
                new GuacamolePropertiesSchema(TestUserMessages.create()));
    }

    private static HttpServletRequest requestWithHost(String host) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Host")).thenReturn(host);
        return request;
    }

    @Test
    void fallsBackToTheFixedLoginUrlWhenNoAdditionalOriginsConfigured() {
        assertThat(service.authLoginUrlForRequest(requestWithHost("nspawnmgr.vanderveer.id")))
                .isEqualTo(LOGIN_URL);
    }

    @Test
    void fallsBackToTheFixedLoginUrlWhenTheHostDoesntMatchAnyTrustedOrigin() {
        row.setAuthAdditionalTrustedOrigins("https://nspawnmgr.vanderveer.id");

        assertThat(service.authLoginUrlForRequest(requestWithHost("some-other-host.example.com")))
                .isEqualTo(LOGIN_URL);
    }

    @Test
    void usesTheMatchedTrustedOriginsOwnSchemeAndAuthorityWithTheConfiguredLoginUrlsPath() {
        row.setAuthAdditionalTrustedOrigins("https://nspawnmgr.vanderveer.id");

        assertThat(service.authLoginUrlForRequest(requestWithHost("nspawnmgr.vanderveer.id")))
                .isEqualTo("https://nspawnmgr.vanderveer.id/auth/login");
    }

    @Test
    void matchesCaseInsensitivelyAndPicksTheRightEntryOutOfSeveral() {
        row.setAuthAdditionalTrustedOrigins("https://one.example.com,https://Nspawnmgr.Vanderveer.ID,https://two.example.com");

        assertThat(service.authLoginUrlForRequest(requestWithHost("nspawnmgr.vanderveer.id")))
                .isEqualTo("https://Nspawnmgr.Vanderveer.ID/auth/login");
    }

    @Test
    void doesNotMatchOnHostAloneWhenTheTrustedOriginHasAnExplicitNonDefaultPort() {
        row.setAuthAdditionalTrustedOrigins("https://nspawnmgr.vanderveer.id:8443");

        assertThat(service.authLoginUrlForRequest(requestWithHost("nspawnmgr.vanderveer.id")))
                .isEqualTo(LOGIN_URL);
    }

    @Test
    void returnsNullWhenNoLoginUrlIsConfiguredAtAll() {
        row.setAuthLoginUrl(null);
        row.setAuthAdditionalTrustedOrigins("https://nspawnmgr.vanderveer.id");

        assertThat(service.authLoginUrlForRequest(requestWithHost("nspawnmgr.vanderveer.id"))).isNull();
    }
}
