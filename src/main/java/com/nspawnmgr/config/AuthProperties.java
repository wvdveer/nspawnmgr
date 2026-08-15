package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nspawnmgr.auth")
public record AuthProperties(
        String cookieName,
        String userIdUrl,
        String userIdJson,
        String userUsernameJson,
        String userEmailJson,
        String userFullnameJson,
        String userIsAdminJson,
        long cacheTtlSeconds,
        long httpTimeoutMs,
        String loginUrl,
        /** Where SettingsService writes the shared properties file auth.war's AuthConfig reads on
         *  every request — must match auth.war's own default/context-param, see AuthConfig.java. */
        String settingsFile
) {
}
