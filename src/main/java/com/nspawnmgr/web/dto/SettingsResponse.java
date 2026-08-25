package com.nspawnmgr.web.dto;

import java.time.Instant;

public record SettingsResponse(
        String guacamoleBaseUrl, String guacamoleDataSource, String hostPublicAddress,
        String authCookieName, String authUserIdUrl, String authLoginUrl,
        String authUserIdJson, String authUserUsernameJson, String authUserEmailJson,
        String authUserFullnameJson, String authUserIsAdminJson, long authCacheTtlSeconds,
        String provisioningAdminAccountName, int provisioningRdpPasswordLength,
        boolean authDetected, String authBackend, String authSmbServer, String authSmbDomain,
        String authRequiredGroup, String authSmbRequiredShare,
        boolean guacamoleDetected,
        String sshHost, int sshPort, String sshUsername, String sshPassword, String sshPrivateKeyPath,
        long sshConnectTimeoutMs, boolean sshStrictHostKeyChecking,
        String nspawnTemplatesDir, String nspawnMachinesDir, String nspawnSettingsDir, String nspawnPrivilegedScriptsDir,
        long authHttpTimeoutMs, String hostExternalHostname, String dnsUpstreamServers,
        int qemuVncPortRangeStart, int qemuVncPortRangeEnd,
        Instant updatedAt, Long updatedByUserId) {
}
