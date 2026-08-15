package com.nspawnmgr.security;

import com.nspawnmgr.service.SettingsService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Small TTL cache so the pre-auth filter doesn't call USER_ID_URL on every single request. */
@Component
public class IdentityCache {

    private record Entry(Optional<ExternalIdentity> identity, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final SettingsService settingsService;

    public IdentityCache(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public Optional<Optional<ExternalIdentity>> get(String cookieValue) {
        Entry entry = entries.get(cookieValue);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.identity());
    }

    public void put(String cookieValue, Optional<ExternalIdentity> identity) {
        Instant expiresAt = Instant.now().plusSeconds(settingsService.authCacheTtlSeconds());
        entries.put(cookieValue, new Entry(identity, expiresAt));
    }
}
