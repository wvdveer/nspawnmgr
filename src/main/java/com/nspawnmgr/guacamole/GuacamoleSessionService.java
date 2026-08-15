package com.nspawnmgr.guacamole;

import com.nspawnmgr.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Mints a Guacamole auth token server-side (using the stored per-user password) and builds the
 * embeddable client URL for a connection — the SSO hop so end users never see Guacamole's own
 * login screen.
 *
 * <p>Caches and reuses a still-fresh token per Guacamole username rather than minting a new one on
 * every call — confirmed live: connecting to a second container in a different tab disconnected
 * the first tab's already-open session. One nspawnmgr user shares a single Guacamole account
 * across every container it connects to (see ShareService.ensureGuacamoleUser), and minting a
 * fresh token for that same account on each connect apparently invalidates whatever token/session
 * was already active — normal Guacamole usage (multiple tabs open against multiple connections
 * under one login) relies on exactly the opposite: one token reused across concurrent tunnels.
 *
 * <p>A cached token can go stale before {@link #TOKEN_REUSE_WINDOW} elapses though: confirmed live,
 * a user who explicitly logs out of their embedded Guacamole session invalidates that token on
 * Guacamole's own side, but this cache has no way to know that on its own — it kept handing out the
 * now-dead token to every subsequent "Connect" click for up to 30 more minutes, and the only way
 * anyone found to clear it was restarting Tomcat (which wipes this in-memory map). {@link
 * #isStillValid} closes that gap with a lightweight check against Guacamole's own REST API
 * immediately before reuse, rather than trusting the reuse window alone.
 */
@Service
public class GuacamoleSessionService {

    // Without this, a wedged Guacamole webapp blocks the calling thread forever - same rationale as
    // GuacamoleTokenClient/GuacamoleAdminClient's own identical timeout.
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    // Comfortably under Guacamole's own default api-session-timeout (60 minutes) - the goal here
    // isn't to track that expiry precisely, just to make near-simultaneous multi-tab connects (the
    // reported failure) reuse the same token instead of racing a fresh one into existence. Actual
    // staleness (e.g. an explicit logout) is caught separately by isStillValid below.
    private static final Duration TOKEN_REUSE_WINDOW = Duration.ofMinutes(30);

    private final SettingsService settingsService;
    private final GuacamoleTokenClient tokenClient;
    private final Supplier<Instant> clock;
    private final RestTemplate restTemplate;
    private final Map<String, CachedToken> tokensByUsername = new ConcurrentHashMap<>();

    private record CachedToken(GuacamoleTokenClient.TokenResponse token, Instant issuedAt) {
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) HTTP_TIMEOUT.toMillis());
        factory.setReadTimeout((int) HTTP_TIMEOUT.toMillis());
        return factory;
    }

    @Autowired
    public GuacamoleSessionService(SettingsService settingsService, GuacamoleTokenClient tokenClient) {
        this(settingsService, tokenClient, Instant::now, new RestTemplate(timeoutFactory()));
    }

    /** Test-only seam: lets GuacamoleSessionServiceTest control elapsed time and mock HTTP calls. */
    GuacamoleSessionService(SettingsService settingsService, GuacamoleTokenClient tokenClient, Supplier<Instant> clock,
                             RestTemplate restTemplate) {
        this.settingsService = settingsService;
        this.tokenClient = tokenClient;
        this.clock = clock;
        this.restTemplate = restTemplate;
    }

    /**
     * {@code browserOrigin} (scheme://host:port) comes from the request that's asking for this
     * URL, not from {@link SettingsService#guacamoleBaseUrl()} - that setting is also used for
     * this server's own HTTP calls to Guacamole's API (GuacamoleAdminClient/GuacamoleTokenClient),
     * where "localhost" is the right, most reliable choice since guacamole.war is always deployed
     * in the same Tomcat instance as nspawnmgr itself. A URL embedded in the SSO iframe sent back
     * to the browser needs to be reachable *by that browser* though, which "localhost" only is
     * when the browser happens to be on this same host - confirmed live, a remote browser got a
     * dead/wrong connection. Since guacamole.war is always co-located with nspawnmgr on the same
     * host:port (see debian/postinst's Tomcat context.xml), whatever origin the browser used to
     * reach *this* request is guaranteed to reach Guacamole too - no separate "public address"
     * setting needed for this specific case. Only the path portion of guacamoleBaseUrl is still
     * taken from settings, so an admin who fronts Guacamole at a non-default path is respected.
     */
    public String buildSessionUrl(String guacUsername, String guacPassword, String connectionId, String browserOrigin) {
        GuacamoleTokenClient.TokenResponse token = tokensByUsername.compute(guacUsername, (username, cached) -> {
            if (cached != null && withinReuseWindow(cached) && isStillValid(cached.token())) {
                return cached;
            }
            return new CachedToken(tokenClient.login(username, guacPassword), clock.get());
        }).token();
        String identifier = connectionId + "\0c\0" + token.dataSource();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(identifier.getBytes(StandardCharsets.UTF_8));
        String path = URI.create(settingsService.guacamoleBaseUrl()).getPath();
        return browserOrigin + path + "/#/client/" + encoded + "?token=" + token.authToken();
    }

    private boolean withinReuseWindow(CachedToken cached) {
        return Duration.between(cached.issuedAt(), clock.get()).compareTo(TOKEN_REUSE_WINDOW) < 0;
    }

    /**
     * Guacamole's own "who am I" endpoint - any authenticated GET would do, this one's cheap and
     * side-effect-free. A 401/403 means the token was invalidated server-side (e.g. an explicit
     * logout) since it was minted/last reused, so the cached entry must not be handed out again.
     * Any other failure (network hiccup, Guacamole briefly unreachable) is treated as "assume still
     * valid" rather than forcing every concurrent tab to re-mint and potentially disconnect each
     * other - the exact bug the cache exists to avoid - letting a genuine problem surface later
     * when the browser actually tries to use the URL instead.
     */
    private boolean isStillValid(GuacamoleTokenClient.TokenResponse token) {
        String url = settingsService.guacamoleBaseUrl() + "/api/session/data/" + token.dataSource()
                + "/self?token=" + token.authToken();
        try {
            restTemplate.getForEntity(url, String.class);
            return true;
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
