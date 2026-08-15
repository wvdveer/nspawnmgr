package com.nspawnmgr.security;

/** Identity fields extracted from the USER_ID_URL JSON response via the configured JsonPath settings. */
public record ExternalIdentity(
        String externalId,
        String username,
        String email,
        String fullName,
        boolean adminFlag
) {
}
