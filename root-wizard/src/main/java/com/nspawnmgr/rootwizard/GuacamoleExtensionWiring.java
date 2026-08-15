package com.nspawnmgr.rootwizard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Finishes wiring guacamole-auth-jdbc into GUACAMOLE_HOME once the wizard has a real,
 * schema-populated Guacamole database to point it at: copies the extension JAR into
 * {@code extensions/} and writes the {@code <vendor>-hostname/-port/-database/-username/-password}
 * fields into {@code guacamole.properties}. GUACAMOLE_HOME is already {@code tomcat:tomcat}-owned
 * by the time this runs (postinst creates it unconditionally), so writing here as the same user
 * Tomcat itself runs as needs no extra privilege.
 */
final class GuacamoleExtensionWiring {

    private static final String GUACAMOLE_VERSION = "1.5.5";

    private GuacamoleExtensionWiring() {
    }

    static String guacamoleHome() {
        return resolveKey("GUACAMOLE_HOME", "/etc/guacamole");
    }

    static void wireExtension(String vendor, String hostname, int port, String database, String username, String password)
            throws IOException {
        String home = guacamoleHome();
        Path extensionsDir = Path.of(home, "extensions");
        Files.createDirectories(extensionsDir);

        Path sourceJar = Path.of(home, "guacamole-auth-jdbc", vendor,
                "guacamole-auth-jdbc-" + vendor + "-" + GUACAMOLE_VERSION + ".jar");
        if (!Files.exists(sourceJar)) {
            throw new IOException("Extension JAR not found at " + sourceJar
                    + " - has install-guacamole-auth-jdbc.sh run yet?");
        }
        Files.copy(sourceJar, extensionsDir.resolve(sourceJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);

        Path propertiesPath = Path.of(home, "guacamole.properties");
        Properties properties = new Properties();
        if (Files.exists(propertiesPath)) {
            try (var in = Files.newInputStream(propertiesPath)) {
                properties.load(in);
            }
        }
        properties.setProperty(vendor + "-hostname", hostname);
        properties.setProperty(vendor + "-port", String.valueOf(port));
        properties.setProperty(vendor + "-database", database);
        properties.setProperty(vendor + "-username", username);
        properties.setProperty(vendor + "-password", password);
        try (var out = Files.newOutputStream(propertiesPath)) {
            properties.store(out, "Written by nspawnmgr's first-boot database setup wizard.");
        }
    }

    // Same precedence DbConnectionSettings/PreSpringSshClient use: system property, then env var,
    // then default. Each class keeps its own tiny copy of this rather than sharing one - none of
    // them have a natural common base, and the logic is three lines.
    private static String resolveKey(String key, String defaultValue) {
        String fromSystemProperty = System.getProperty(key);
        if (fromSystemProperty != null) {
            return fromSystemProperty;
        }
        String fromEnv = System.getenv(key);
        return fromEnv != null ? fromEnv : defaultValue;
    }
}
