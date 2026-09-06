package com.nspawnmgr.service;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Named, version-controlled shell-command templates for {@link ProvisioningService}/
 * {@link ContainerLifecycleService} - see {@code provisioning-commands.yml}'s own header comment
 * for why these were pulled out of Java (previously inline string literals scattered across those
 * two classes, several duplicated verbatim 2-4x). Loaded once at startup from that bundled YAML
 * resource - deliberately NOT admin/DB-editable, unlike Template's own per-desktop-manager
 * install-command overrides (a different, narrower kind of override for a different purpose): these
 * are internal provisioning mechanics, not something a template author should be tuning per
 * container.
 *
 * <p>Each value is still a {@link java.util.Formatter} template ({@code %s}/{@code %1$s}/etc.)
 * exactly as before - callers still call {@code .formatted(...)} themselves, this class only
 * resolves the literal text by name.
 */
@Component
public class ProvisioningCommands {

    private final Properties commands;

    public ProvisioningCommands() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("provisioning-commands.yml"));
        this.commands = factory.getObject();
    }

    /** @throws IllegalStateException if {@code key} has no entry - a missing/misspelled key should
     *  fail loudly the first time it's looked up, not silently produce a null that blows up
     *  somewhere unrelated (a bare {@code %s} passed straight to {@code sh()}, for instance). */
    public String get(String key) {
        String value = commands.getProperty("provisioning-commands." + key);
        if (value == null) {
            throw new IllegalStateException("No provisioning command configured for key: " + key);
        }
        return value;
    }
}
