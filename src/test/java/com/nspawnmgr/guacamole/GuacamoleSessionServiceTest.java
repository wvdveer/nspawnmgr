package com.nspawnmgr.guacamole;

import com.nspawnmgr.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.FORBIDDEN;

class GuacamoleSessionServiceTest {

    private SettingsService settingsService;
    private GuacamoleTokenClient tokenClient;
    private AtomicReference<Instant> now;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private GuacamoleSessionService service;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        when(settingsService.guacamoleBaseUrl()).thenReturn("http://localhost:8080/guacamole");
        tokenClient = mock(GuacamoleTokenClient.class);
        now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new GuacamoleSessionService(settingsService, tokenClient, now::get, restTemplate);
    }

    @Test
    void secondConnectForTheSameUserWithinTheReuseWindowReusesTheSameToken() {
        // Confirmed live: connecting to a second container in a different tab disconnected the
        // first tab's already-open session, because a fresh Guacamole token was minted (and the
        // prior one apparently invalidated) for the same shared per-user Guacamole account on
        // every single connect.
        when(tokenClient.login("ward", "pw")).thenReturn(new GuacamoleTokenClient.TokenResponse("tok-1", "mysql"));
        server.expect(requestTo("http://localhost:8080/guacamole/api/session/data/mysql/self?token=tok-1"))
                .andRespond(withSuccess());

        String firstUrl = service.buildSessionUrl("ward", "pw", "conn-a", "http://localhost:8080");
        String secondUrl = service.buildSessionUrl("ward", "pw", "conn-b", "http://localhost:8080");

        verify(tokenClient, times(1)).login("ward", "pw");
        assertThat(firstUrl).contains("token=tok-1");
        assertThat(secondUrl).contains("token=tok-1");
        server.verify();
    }

    @Test
    void mintsAFreshTokenOncePastTheReuseWindow() {
        when(tokenClient.login("ward", "pw"))
                .thenReturn(new GuacamoleTokenClient.TokenResponse("tok-1", "mysql"))
                .thenReturn(new GuacamoleTokenClient.TokenResponse("tok-2", "mysql"));

        String firstUrl = service.buildSessionUrl("ward", "pw", "conn-a", "http://localhost:8080");
        now.set(now.get().plus(31, ChronoUnit.MINUTES));
        String secondUrl = service.buildSessionUrl("ward", "pw", "conn-b", "http://localhost:8080");

        // Past the reuse window, the validity check is never reached - a fresh token is minted
        // outright, same as before this check existed.
        verify(tokenClient, times(2)).login("ward", "pw");
        assertThat(firstUrl).contains("token=tok-1");
        assertThat(secondUrl).contains("token=tok-2");
        server.verify();
    }

    @Test
    void differentUsersNeverShareACachedToken() {
        when(tokenClient.login("ward", "pw1")).thenReturn(new GuacamoleTokenClient.TokenResponse("tok-ward", "mysql"));
        when(tokenClient.login("erin", "pw2")).thenReturn(new GuacamoleTokenClient.TokenResponse("tok-erin", "mysql"));

        String wardUrl = service.buildSessionUrl("ward", "pw1", "conn-a", "http://localhost:8080");
        String erinUrl = service.buildSessionUrl("erin", "pw2", "conn-b", "http://localhost:8080");

        assertThat(wardUrl).contains("token=tok-ward");
        assertThat(erinUrl).contains("token=tok-erin");
    }

    @Test
    void mintsAFreshTokenWhenTheCachedOneWasInvalidatedByAnExplicitLogout() {
        // Confirmed live: a user logging out of their embedded Guacamole session invalidated that
        // token server-side, but the cache kept handing it out anyway (still within the reuse
        // window) until Tomcat was restarted. A 403 from Guacamole's own "self" endpoint is the
        // signal this cached entry is dead and must be replaced, not reused.
        when(tokenClient.login("ward", "pw"))
                .thenReturn(new GuacamoleTokenClient.TokenResponse("tok-1", "mysql"))
                .thenReturn(new GuacamoleTokenClient.TokenResponse("tok-2", "mysql"));
        server.expect(requestTo("http://localhost:8080/guacamole/api/session/data/mysql/self?token=tok-1"))
                .andRespond(withStatus(FORBIDDEN));

        String firstUrl = service.buildSessionUrl("ward", "pw", "conn-a", "http://localhost:8080");
        String secondUrl = service.buildSessionUrl("ward", "pw", "conn-b", "http://localhost:8080");

        verify(tokenClient, times(2)).login("ward", "pw");
        assertThat(firstUrl).contains("token=tok-1");
        assertThat(secondUrl).contains("token=tok-2");
        server.verify();
    }

    @Test
    void reusesTheCachedTokenWhenTheValidityCheckItselfFailsToConnect() {
        // A transient network hiccup validating the cached token must not force every concurrent
        // tab to re-mint (and potentially disconnect each other) - the exact bug this cache exists
        // to prevent in the first place. Throwing IOException here mimics what RestTemplate itself
        // throws (wrapped in ResourceAccessException, a real Exception) when Guacamole is
        // unreachable - not to be confused with MockRestServiceServer's own AssertionError for an
        // unmatched request, which is an Error, not an Exception, and wouldn't be caught the same way.
        when(tokenClient.login("ward", "pw")).thenReturn(new GuacamoleTokenClient.TokenResponse("tok-1", "mysql"));
        server.expect(requestTo("http://localhost:8080/guacamole/api/session/data/mysql/self?token=tok-1"))
                .andRespond(request -> { throw new java.io.IOException("simulated network failure"); });

        String firstUrl = service.buildSessionUrl("ward", "pw", "conn-a", "http://localhost:8080");
        String secondUrl = service.buildSessionUrl("ward", "pw", "conn-b", "http://localhost:8080");

        verify(tokenClient, times(1)).login("ward", "pw");
        assertThat(firstUrl).contains("token=tok-1");
        assertThat(secondUrl).contains("token=tok-1");
        server.verify();
    }
}
