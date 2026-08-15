package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nspawnmgr.nspawn")
public record NspawnProperties(
        String templatesDir,
        String machinesDir,
        String settingsDir,
        String privilegedScriptsDir
) {
}
