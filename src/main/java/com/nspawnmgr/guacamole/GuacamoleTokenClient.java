package com.nspawnmgr.guacamole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nspawnmgr.config.GuacamoleProperties;
import com.nspawnmgr.service.SettingsService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/** POST /api/tokens — the one call both admin provisioning and end-user SSO session minting share. */
@Component
public class GuacamoleTokenClient {

    // Without this, a wedged Guacamole webapp blocks the calling thread forever - notably the
    // @Async provisioning thread in ProvisioningService, permanently stranding a container in
    // CREATING with no exception ever thrown and no automatic recovery.
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final GuacamoleProperties properties;
    private final SettingsService settingsService;
    private final RestTemplate restTemplate = new RestTemplate(timeoutFactory());

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());
        return factory;
    }
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GuacamoleTokenClient(GuacamoleProperties properties, SettingsService settingsService) {
        this.properties = properties;
        this.settingsService = settingsService;
    }

    public record TokenResponse(String authToken, String dataSource) {
    }

    public TokenResponse login(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("password", password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String body = restTemplate.postForObject(settingsService.guacamoleBaseUrl() + "/api/tokens",
                new HttpEntity<>(form, headers), String.class);
        try {
            JsonNode node = objectMapper.readTree(body);
            String authToken = node.path("authToken").asText();
            String dataSource = node.path("dataSource").asText(settingsService.guacamoleDataSource());
            return new TokenResponse(authToken, dataSource);
        } catch (Exception e) {
            throw new GuacamoleClientException("Failed to parse Guacamole token response", e);
        }
    }

    public TokenResponse loginAsAdmin() {
        return login(properties.adminUsername(), properties.adminPassword());
    }
}
