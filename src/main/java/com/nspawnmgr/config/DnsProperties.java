package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nspawnmgr.dns")
public record DnsProperties(
        String hostsFile,
        String upstreamServersFile,
        String upstreamServers
) {
}
