package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nspawnmgr.pam-auth")
public record PamAuthProperties(
        String callbackBaseUrl
) {
}
