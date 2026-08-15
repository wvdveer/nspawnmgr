package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nspawnmgr.crypto")
public record CryptoProperties(
        String secretKey
) {
}
