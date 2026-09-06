package com.nspawnmgr.guacamole;

import com.nspawnmgr.cli.TomcatRestartService;
import com.nspawnmgr.config.GuacamoleProperties;
import com.nspawnmgr.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * Keeps {@code GUACAMOLE_HOME/extensions/} carrying the auth-jdbc extension jar that actually
 * matches what {@code install-guacamole-auth-jdbc.sh} most recently extracted into
 * {@code GUACAMOLE_HOME/guacamole-auth-jdbc/} - {@code root-wizard}'s own
 * {@code GuacamoleExtensionWiring.wireExtension()} does this same copy, but only ever runs once,
 * from the pre-Spring first-boot database setup wizard. An already-configured install never goes
 * through that wizard again, so nothing previously re-copied a new package version's jar into
 * extensions/ on upgrade - the version pinned at first setup would otherwise stay wired forever,
 * regardless of how many times the package itself gets upgraded.
 *
 * <p>Reads the currently-extracted version from {@code GUACAMOLE_HOME/guacamole-auth-jdbc/VERSION}
 * (written by install-guacamole-auth-jdbc.sh on every extraction) rather than pinning its own copy
 * of the version string - this and that script agreeing by construction is worth more than a
 * yet another hardcoded version literal alongside the ones (packaging manifests, that script's own
 * GUACAMOLE_VERSION, {@code GuacamoleExtensionWiring.GUACAMOLE_VERSION}) this project already has.
 *
 * <p>Runs on every application startup, same trigger point as any other Guacamole reconciliation
 * ApplicationRunner and for the same reason (the restart at the end of every package upgrade) - skips silently if
 * Guacamole isn't configured yet, or if install-guacamole-auth-jdbc.sh hasn't extracted anything
 * for the active vendor yet. Never lets a failure here block application startup.
 *
 * <p>Guacamole's own webapp only reads {@code extensions/} at its own Tomcat deploy time (same
 * constraint {@code DatabaseSetupWizardServlet}'s own comment on this documents) - by the time this
 * runs, guacamole.war has very likely already deployed with whatever was in extensions/ before this
 * swap, since all four WARs cold-start together after the machine reboot a package upgrade ends
 * with. So when a swap actually changes something, this queues a Tomcat restart via the same
 * {@link TomcatRestartService} the "Restart Tomcat" /admin/settings button uses (a `--no-block`
 * systemctl restart, safe to fire from the very Tomcat instance being restarted - see that
 * service's own doc) - skipped entirely when nothing changed, so a routine, unchanged restart never
 * triggers a second one of its own.
 */
@Component
public class GuacamoleExtensionRefresh implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GuacamoleExtensionRefresh.class);

    private final SettingsService settingsService;
    private final GuacamoleProperties guacamoleProperties;
    private final TomcatRestartService tomcatRestartService;

    public GuacamoleExtensionRefresh(SettingsService settingsService, GuacamoleProperties guacamoleProperties,
                                      TomcatRestartService tomcatRestartService) {
        this.settingsService = settingsService;
        this.guacamoleProperties = guacamoleProperties;
        this.tomcatRestartService = tomcatRestartService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            refreshIfNeeded();
        } catch (Exception e) {
            log.warn("Guacamole auth extension refresh failed (non-fatal - will retry on next restart): {}", e.getMessage());
        }
    }

    private void refreshIfNeeded() throws IOException {
        Map<String, String> properties = settingsService.readGuacamoleProperties();
        if (properties.isEmpty()) {
            return;
        }
        String vendor = settingsService.detectGuacamolePropertiesDatabaseType();
        String home = guacamoleProperties.home();

        Path versionFile = Path.of(home, "guacamole-auth-jdbc", "VERSION");
        if (!Files.isRegularFile(versionFile)) {
            return;
        }
        String version = Files.readString(versionFile).strip();
        if (version.isEmpty()) {
            return;
        }

        Path sourceJar = Path.of(home, "guacamole-auth-jdbc", vendor,
                "guacamole-auth-jdbc-" + vendor + "-" + version + ".jar");
        if (!Files.isRegularFile(sourceJar)) {
            log.warn("Guacamole auth extension refresh: {} not found - has install-guacamole-auth-jdbc.sh run yet?",
                    sourceJar);
            return;
        }

        Path extensionsDir = Path.of(home, "extensions");
        Files.createDirectories(extensionsDir);

        String currentJarName = sourceJar.getFileName().toString();
        String stalePrefix = "guacamole-auth-jdbc-" + vendor + "-";
        boolean changed = false;
        try (var listing = Files.list(extensionsDir)) {
            for (Path existing : listing.toList()) {
                String name = existing.getFileName().toString();
                if (name.startsWith(stalePrefix) && name.endsWith(".jar") && !name.equals(currentJarName)) {
                    Files.delete(existing);
                    changed = true;
                    log.info("Guacamole auth extension refresh: removed stale {} (superseded by {}).", name, currentJarName);
                }
            }
        }

        Path target = extensionsDir.resolve(currentJarName);
        boolean alreadyCurrent = Files.isRegularFile(target) && Files.size(target) == Files.size(sourceJar);
        if (!alreadyCurrent) {
            Files.copy(sourceJar, target, StandardCopyOption.REPLACE_EXISTING);
            changed = true;
            log.info("Guacamole auth extension refresh: wired {} into {}.", currentJarName, extensionsDir);
        }

        if (changed) {
            log.info("Guacamole auth extension refresh: restarting Tomcat so guacamole.war picks up the change.");
            tomcatRestartService.restart();
        }
    }
}
