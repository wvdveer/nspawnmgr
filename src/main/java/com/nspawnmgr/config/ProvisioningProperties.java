package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nspawnmgr.provisioning")
public record ProvisioningProperties(
        String adminAccountName,
        int rdpPasswordLength
) {
}
