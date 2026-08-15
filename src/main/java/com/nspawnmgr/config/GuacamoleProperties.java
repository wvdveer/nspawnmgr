package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nspawnmgr.guacamole")
public record GuacamoleProperties(
        String baseUrl,
        String adminUsername,
        String adminPassword,
        String dataSource,
        /** Filesystem directory holding Guacamole's own guacamole.properties — deploy-time only,
         *  deliberately not live-editable via /admin/settings (it's the location a live-edit
         *  feature writes to, not itself a value worth overriding at runtime). */
        String home
) {
}
