package com.nspawnmgr.fakeguac;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Unprefixed: this module is deployed as guacamole.war, so Tomcat's own context path
 * ("/guacamole") already supplies that segment externally — GuacamoleTokenClient/
 * GuacamoleAdminClient/GuacamoleSessionService build URLs as
 * {@code properties.baseUrl() + "/api/..."} where baseUrl already ends in "/guacamole". /fake/reset
 * is deliberately NOT part of the mimicked API, so it lives in FakeGuacamoleResetController instead.
 */
@RestController
public class FakeGuacamoleController {

    private final FakeGuacamoleStore store;
    private final FakeGuacamoleProperties properties;

    public FakeGuacamoleController(FakeGuacamoleStore store, FakeGuacamoleProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @PostMapping(value = "/api/tokens", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Map<String, Object>> login(@RequestParam String username, @RequestParam String password) {
        boolean isAdmin = properties.adminUsername().equals(username) && properties.adminPassword().equals(password);
        boolean isKnownUser = password.equals(store.userPasswords.get(username));
        if (!isAdmin && !isKnownUser) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String token = store.newToken();
        store.tokenToUsername.put(token, username);
        return ResponseEntity.ok(Map.of("authToken", token, "dataSource", properties.dataSource(), "username", username));
    }

    @PostMapping("/api/session/data/{dataSource}/users")
    public ResponseEntity<Map<String, Object>> createUser(@PathVariable String dataSource, @RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        if (store.userPasswords.containsKey(username)) {
            // Matches a real Guacamole server, confirmed live on yoga: a duplicate username comes
            // back as 400 Bad Request (not 409 Conflict, which this fake used to return) with a
            // "already exists" message - GuacamoleAdminClient.createOrGetUser's swallow logic
            // keys off that exact shape, so the fake has to mimic it or this bug ships silently
            // again the next time this code path changes.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", "User \"" + username + "\" already exists.", "type", "BAD_REQUEST"));
        }
        store.userPasswords.put(username, (String) body.get("password"));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/api/session/data/{dataSource}/users/{username}")
    public ResponseEntity<Void> updateUser(@PathVariable String dataSource, @PathVariable String username,
                                            @RequestBody Map<String, Object> body) {
        if (!store.userPasswords.containsKey(username)) {
            return ResponseEntity.notFound().build();
        }
        store.userPasswords.put(username, (String) body.get("password"));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/session/data/{dataSource}/users/{username}")
    public ResponseEntity<Void> deleteUser(@PathVariable String dataSource, @PathVariable String username) {
        if (store.userPasswords.remove(username) == null) {
            return ResponseEntity.notFound().build();
        }
        store.permissions.removeIf(p -> p.startsWith(username + ":"));
        return ResponseEntity.noContent().build();
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/api/session/data/{dataSource}/connections")
    public ResponseEntity<Map<String, Object>> createConnection(@PathVariable String dataSource, @RequestBody Map<String, Object> body) {
        String identifier = store.newConnectionId();
        var connection = new FakeGuacamoleStore.Connection(
                identifier,
                (String) body.get("name"),
                (String) body.get("protocol"),
                (Map<String, Object>) body.getOrDefault("parameters", Map.of()));
        store.connections.put(identifier, connection);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("identifier", identifier));
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/api/session/data/{dataSource}/connections/{id}")
    public ResponseEntity<Void> updateConnection(@PathVariable String dataSource, @PathVariable String id,
                                                  @RequestBody Map<String, Object> body) {
        if (!store.connections.containsKey(id)) {
            return ResponseEntity.notFound().build();
        }
        var connection = new FakeGuacamoleStore.Connection(
                id,
                (String) body.get("name"),
                (String) body.get("protocol"),
                (Map<String, Object>) body.getOrDefault("parameters", Map.of()));
        store.connections.put(id, connection);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/session/data/{dataSource}/connections/{id}")
    public ResponseEntity<Void> deleteConnection(@PathVariable String dataSource, @PathVariable String id) {
        if (store.connections.remove(id) == null) {
            return ResponseEntity.notFound().build();
        }
        store.permissions.removeIf(p -> p.endsWith(":" + id));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/api/session/data/{dataSource}/users/{username}/permissions")
    public ResponseEntity<Void> patchPermissions(@PathVariable String dataSource, @PathVariable String username,
                                                  @RequestBody List<Map<String, String>> patch) {
        for (Map<String, String> op : patch) {
            String connectionId = op.get("path").replace("/connectionPermissions/", "");
            String key = username + ":" + connectionId;
            if ("add".equals(op.get("op"))) {
                store.permissions.add(key);
            } else {
                store.permissions.remove(key);
            }
        }
        return ResponseEntity.noContent().build();
    }
}
