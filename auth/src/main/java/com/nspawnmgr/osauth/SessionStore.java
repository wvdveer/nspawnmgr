package com.nspawnmgr.osauth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory token -> Identity store. Fine for a test-harness auth provider; not durable across restarts. */
public class SessionStore {

    private static final SessionStore INSTANCE = new SessionStore();

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Identity> tokens = new ConcurrentHashMap<>();

    private SessionStore() {
    }

    public static SessionStore instance() {
        return INSTANCE;
    }

    public String createSession(Identity identity) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, identity);
        return token;
    }

    public Identity lookup(String token) {
        return token == null ? null : tokens.get(token);
    }

    public void invalidate(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }
}
