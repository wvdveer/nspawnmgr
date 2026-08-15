package com.nspawnmgr.guacamole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nspawnmgr.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Admin-authenticated REST client for provisioning Guacamole users, connections, and connection
 * permissions. Re-authenticates as admin on demand rather than tracking token expiry precisely.
 * Uses HttpComponentsClientHttpRequestFactory (Apache HttpClient) because the default JDK-backed
 * factory doesn't support HTTP PATCH, which the permissions endpoint requires.
 */
@Component
public class GuacamoleAdminClient {

    // Without this, a wedged Guacamole webapp blocks the calling thread forever - notably the
    // @Async provisioning thread in ProvisioningService, permanently stranding a container in
    // CREATING with no exception ever thrown and no automatic recovery.
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final SettingsService settingsService;
    private final GuacamoleTokenClient tokenClient;
    private final RestTemplate restTemplate;

    private static HttpComponentsClientHttpRequestFactory timeoutFactory() {
        var factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setConnectionRequestTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());
        return factory;
    }
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public GuacamoleAdminClient(SettingsService settingsService, GuacamoleTokenClient tokenClient) {
        this(settingsService, tokenClient, new RestTemplate(timeoutFactory()));
    }

    /** Test-only seam: lets GuacamoleAdminClientTest bind a MockRestServiceServer to a known instance. */
    GuacamoleAdminClient(SettingsService settingsService, GuacamoleTokenClient tokenClient, RestTemplate restTemplate) {
        this.settingsService = settingsService;
        this.tokenClient = tokenClient;
        this.restTemplate = restTemplate;
    }

    private String dataUrl(String suffix) {
        return settingsService.guacamoleBaseUrl() + "/api/session/data/" + settingsService.guacamoleDataSource() + suffix;
    }

    private String adminToken() {
        return tokenClient.loginAsAdmin().authToken();
    }

    public void createOrGetUser(String username, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("attributes", Map.of());
        try {
            restTemplate.exchange(dataUrl("/users") + "?token=" + adminToken(), HttpMethod.POST,
                    new HttpEntity<>(body), Void.class);
        } catch (HttpClientErrorException.Conflict e) {
            // User already exists — fine, this call is idempotent.
        } catch (HttpClientErrorException.BadRequest e) {
            // Confirmed live on a real Guacamole server (not the fake, which used 409): a
            // duplicate username comes back as 400 Bad Request with a JSON body whose "message"
            // says "already exists" - genuinely idempotent, same as the 409 case above. Two
            // ProvisioningService.provision() runs for the same owner's first two containers can
            // race here (ensureGuacamoleUser's own null-check isn't atomic), so this has to be
            // treated as success rather than surfaced as a provisioning failure. Any other 400
            // (malformed username, etc.) rethrows - only this specific message is safe to swallow.
            if (!isAlreadyExistsError(e)) {
                throw e;
            }
        }
    }

    private boolean isAlreadyExistsError(HttpClientErrorException e) {
        try {
            JsonNode node = objectMapper.readTree(e.getResponseBodyAsString());
            String message = node.path("message").asText("");
            return message.toLowerCase(java.util.Locale.ROOT).contains("already exists");
        } catch (Exception parseFailure) {
            return false;
        }
    }

    /** Admin-privileged password reset — no old password required, unlike Guacamole's own self-service change-password flow. */
    public void updateUserPassword(String username, String newPassword) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", newPassword);
        body.put("attributes", Map.of());
        restTemplate.exchange(dataUrl("/users/" + username) + "?token=" + adminToken(), HttpMethod.PUT,
                new HttpEntity<>(body), Void.class);
    }

    public String createSshConnection(String connectionName, String hostname, int port, String accountName, String privateKeyPem) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        parameters.put("username", accountName);
        parameters.put("private-key", privateKeyPem);
        return createConnection(connectionName, "ssh", parameters);
    }

    public String createRdpConnection(String connectionName, String hostname, int port, String accountName, String password, String security) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        parameters.put("username", accountName);
        parameters.put("password", password);
        parameters.put("security", security);
        parameters.put("ignore-cert", "true");
        return createConnection(connectionName, "rdp", parameters);
    }

    public String createVncConnection(String connectionName, String hostname, int port, String password) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        parameters.put("password", password);
        return createConnection(connectionName, "vnc", parameters);
    }

    /** Re-points an existing managed-container SSH connection at (possibly updated) hostname/credential. */
    public void updateSshConnection(String connectionId, String connectionName, String hostname, int port, String accountName, String privateKeyPem) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        parameters.put("username", accountName);
        parameters.put("private-key", privateKeyPem);
        updateConnection(connectionId, connectionName, "ssh", parameters);
    }

    public void updateRdpConnection(String connectionId, String connectionName, String hostname, int port, String accountName, String password, String security) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        parameters.put("username", accountName);
        parameters.put("password", password);
        parameters.put("security", security);
        parameters.put("ignore-cert", "true");
        updateConnection(connectionId, connectionName, "rdp", parameters);
    }

    /** Re-points an existing managed-container VNC connection at a (possibly updated) hostname. */
    public void updateVncConnection(String connectionId, String connectionName, String hostname, int port, String password) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        parameters.put("password", password);
        updateConnection(connectionId, connectionName, "vnc", parameters);
    }

    /**
     * For admin-configured external hosts: no username/password/private-key parameter is set, so
     * Guacamole prompts the connecting user for credentials interactively instead of nspawnmgr
     * having to generate/store any.
     */
    public String createSshConnectionPromptCredentials(String connectionName, String hostname, int port) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        return createConnection(connectionName, "ssh", parameters);
    }

    public String createRdpConnectionPromptCredentials(String connectionName, String hostname, int port, String security) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        parameters.put("security", security);
        parameters.put("ignore-cert", "true");
        return createConnection(connectionName, "rdp", parameters);
    }

    public String createVncConnectionPromptCredentials(String connectionName, String hostname, int port) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        return createConnection(connectionName, "vnc", parameters);
    }

    /** Re-points an existing external-host connection at (possibly updated) hostname/port config. */
    public void updateSshConnectionPromptCredentials(String connectionId, String connectionName, String hostname, int port) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        updateConnection(connectionId, connectionName, "ssh", parameters);
    }

    public void updateRdpConnectionPromptCredentials(String connectionId, String connectionName, String hostname, int port, String security) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        parameters.put("security", security);
        parameters.put("ignore-cert", "true");
        updateConnection(connectionId, connectionName, "rdp", parameters);
    }

    public void updateVncConnectionPromptCredentials(String connectionId, String connectionName, String hostname, int port) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("hostname", hostname);
        parameters.put("port", String.valueOf(port));
        updateConnection(connectionId, connectionName, "vnc", parameters);
    }

    private String createConnection(String connectionName, String protocol, Map<String, Object> parameters) {
        Map<String, Object> body = connectionBody(connectionName, protocol, parameters);
        var response = restTemplate.exchange(dataUrl("/connections") + "?token=" + adminToken(), HttpMethod.POST,
                new HttpEntity<>(body), String.class);
        try {
            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("identifier").asText();
        } catch (Exception e) {
            throw new GuacamoleClientException("Failed to parse Guacamole connection creation response", e);
        }
    }

    private void updateConnection(String connectionId, String connectionName, String protocol, Map<String, Object> parameters) {
        Map<String, Object> body = connectionBody(connectionName, protocol, parameters);
        body.put("identifier", connectionId);
        restTemplate.exchange(dataUrl("/connections/" + connectionId) + "?token=" + adminToken(), HttpMethod.PUT,
                new HttpEntity<>(body), Void.class);
    }

    private Map<String, Object> connectionBody(String connectionName, String protocol, Map<String, Object> parameters) {
        Map<String, Object> body = new HashMap<>();
        body.put("parentIdentifier", "ROOT");
        body.put("name", connectionName);
        body.put("protocol", protocol);
        body.put("parameters", parameters);
        body.put("attributes", Map.of());
        return body;
    }

    public void deleteConnection(String connectionId) {
        try {
            restTemplate.exchange(dataUrl("/connections/" + connectionId) + "?token=" + adminToken(),
                    HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        } catch (HttpClientErrorException.NotFound e) {
            // Already gone — fine.
        }
    }

    public void deleteUser(String username) {
        try {
            restTemplate.exchange(dataUrl("/users/" + username) + "?token=" + adminToken(),
                    HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
        } catch (HttpClientErrorException.NotFound e) {
            // Already gone — fine.
        }
    }

    public void grantConnectionPermission(String username, String connectionId) {
        patchConnectionPermission(username, connectionId, "add");
    }

    public void revokeConnectionPermission(String username, String connectionId) {
        patchConnectionPermission(username, connectionId, "remove");
    }

    private void patchConnectionPermission(String username, String connectionId, String op) {
        var patch = new Object[]{Map.of(
                "op", op,
                "path", "/connectionPermissions/" + connectionId,
                "value", "READ")};
        restTemplate.exchange(dataUrl("/users/" + username + "/permissions") + "?token=" + adminToken(),
                HttpMethod.PATCH, new HttpEntity<>(patch), Void.class);
    }
}
