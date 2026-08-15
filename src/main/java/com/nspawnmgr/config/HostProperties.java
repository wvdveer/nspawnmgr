package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nspawnmgr.host")
public record HostProperties(
        String publicAddress,
        // The hostname/DNS-name users OUTSIDE this host should use in a browser — distinct from
        // publicAddress above, which is what Guacamole's guacd uses to reach into a container
        // (usually 127.0.0.1, since guacd is normally co-located). Backs the URL "Refresh" buttons
        // on /admin/settings — see SettingsService.hostExternalHostname() and
        // docs/administrator-guide.md §8.
        String externalHostname
) {
}
