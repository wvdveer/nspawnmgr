package com.nspawnmgr.web.dto;

public record UpdateSettingsRequest(
        String guacamoleBaseUrl, String guacamoleDataSource, String hostPublicAddress,
        String authCookieName, String authUserIdUrl, String authLoginUrl,
        String authUserIdJson, String authUserUsernameJson, String authUserEmailJson,
        String authUserFullnameJson, String authUserIsAdminJson, Long authCacheTtlSeconds,
        String provisioningAdminAccountName, Integer provisioningRdpPasswordLength,
        String authBackend, String authSmbServer, String authSmbDomain,
        String authRequiredGroup, String authSmbRequiredShare,
        String sshHost, Integer sshPort, String sshUsername, String sshPassword, String sshPrivateKeyPath,
        Long sshConnectTimeoutMs, Boolean sshStrictHostKeyChecking,
        String nspawnTemplatesDir, String nspawnMachinesDir, String nspawnSettingsDir, String nspawnPrivilegedScriptsDir,
        Long authHttpTimeoutMs, String hostExternalHostname, String dnsUpstreamServers) {
}
