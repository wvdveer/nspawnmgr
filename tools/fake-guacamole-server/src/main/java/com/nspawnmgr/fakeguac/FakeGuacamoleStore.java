package com.nspawnmgr.fakeguac;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** All state is in-memory and reset via POST /fake/reset, matching a real Guacamole install closely enough to test against. */
@Component
public class FakeGuacamoleStore {

    private final SecureRandom random = new SecureRandom();
    private final AtomicLong connectionIdSequence = new AtomicLong(1);

    final Map<String, String> userPasswords = new ConcurrentHashMap<>();
    final Map<String, String> tokenToUsername = new ConcurrentHashMap<>();
    final Map<String, Connection> connections = new ConcurrentHashMap<>();
    final Set<String> permissions = ConcurrentHashMap.newKeySet(); // "username:connectionId"

    record Connection(String identifier, String name, String protocol, Map<String, Object> parameters) {
    }

    public String newConnectionId() {
        return "c" + connectionIdSequence.getAndIncrement();
    }

    public String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void reset() {
        userPasswords.clear();
        tokenToUsername.clear();
        connections.clear();
        permissions.clear();
        connectionIdSequence.set(1);
    }
}
