package com.nspawnmgr.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.nspawnmgr.service.SettingsService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * Calls USER_ID_URL (forwarding the session cookie) and extracts identity fields via the
 * configured JsonPath settings. Isolated from Spring Security so the JsonPath extraction logic
 * can be unit tested with a mocked HTTP client.
 */
@Component
public class IdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(IdentityResolver.class);

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IdentityResolver(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    private static SimpleClientHttpRequestFactory clientHttpRequestFactory(long timeoutMs) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        return factory;
    }

    public Optional<ExternalIdentity> resolve(String cookieValue) {
        String userIdUrl = settingsService.authUserIdUrl();
        if (userIdUrl == null || userIdUrl.isBlank()) {
            return Optional.empty();
        }
        String body;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Cookie", settingsService.authCookieName() + "=" + cookieValue);
            RestTemplate restTemplate = new RestTemplate(clientHttpRequestFactory(settingsService.authHttpTimeoutMs()));
            var response = restTemplate.exchange(userIdUrl, HttpMethod.GET,
                    new HttpEntity<Void>(headers), String.class);
            body = response.getBody();
        } catch (Exception e) {
            // Silently treating any failure here as "not authenticated" left this whole class of
            // bug (wrong hostname, auth.war unreachable, timeout) completely undiagnosable from
            // logs - the symptom was just "keeps redirecting to login" with nothing to go on.
            log.warn("Failed to resolve identity via {}: {}", userIdUrl, e.toString());
            return Optional.empty();
        }
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        return extract(body);
    }

    Optional<ExternalIdentity> extract(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            return Optional.empty();
        }
        String id = readPath(root, settingsService.authUserIdJson());
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String username = readPath(root, settingsService.authUserUsernameJson());
        String email = readPath(root, settingsService.authUserEmailJson());
        String fullName = readPath(root, settingsService.authUserFullnameJson());
        boolean adminFlag = "true".equalsIgnoreCase(readPath(root, settingsService.authUserIsAdminJson()));
        return Optional.of(new ExternalIdentity(id, username, email, fullName, adminFlag));
    }

    private String readPath(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Object value = JsonPath.read(root.toString(), path);
            return value == null ? null : value.toString();
        } catch (PathNotFoundException e) {
            return null;
        }
    }
}
