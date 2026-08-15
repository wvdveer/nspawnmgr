package com.nspawnmgr.bootstrap;

import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The database connection nspawnmgr will use, resolved the same way Spring's property binding
 * would once the real app is up — system property, then OS environment variable, then the setup
 * wizard's own config file, then the hardcoded default — so a value set any of these ways is found
 * consistently both by this pre-Spring reachability check and later by {@code application.yml}'s
 * {@code ${DB_URL:...}}-style placeholders. Deliberately has zero Spring dependency: this class
 * runs from {@link com.nspawnmgr.NspawnmgrApplication#onStartup}, before any Spring context exists.
 */
public record DbConnectionSettings(String url, String username, String password, String vendor) {

    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/nspawnmgr";
    private static final String DEFAULT_USERNAME = "nspawnmgr";
    private static final String DEFAULT_PASSWORD = "nspawnmgr";
    private static final String DEFAULT_VENDOR = "mysql";
    private static final String DEFAULT_CONFIG_FILE = "/etc/nspawnmgr/db-config/db.properties";

    public static DbConnectionSettings resolve() {
        Properties fromFile = readConfigFile();
        return new DbConnectionSettings(
                resolveKey("DB_URL", fromFile, DEFAULT_URL),
                resolveKey("DB_USERNAME", fromFile, DEFAULT_USERNAME),
                resolveKey("DB_PASSWORD", fromFile, DEFAULT_PASSWORD),
                resolveKey("DB_VENDOR", fromFile, DEFAULT_VENDOR));
    }

    public static String configFilePath() {
        return resolveKey("DB_CONFIG_FILE", new Properties(), DEFAULT_CONFIG_FILE);
    }

    /** Cheap, short-lived connection attempt — true means the real app can boot normally. */
    public boolean isReachable() {
        JdbcDrivers.load(vendor);
        try (Connection ignored = DriverManager.getConnection(url, username, password)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static Properties readConfigFile() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(configFilePath())) {
            props.load(in);
        } catch (IOException e) {
            // Missing file is the normal case before first setup — not an error.
        }
        return props;
    }

    /**
     * Presence, not blankness, decides whether a source "has" a value — matching how Spring's own
     * later {@code ${DB_PASSWORD:nspawnmgr}}-style placeholders resolve (application.yml): a
     * property/env var that's explicitly set to an empty string is a deliberate value (e.g. H2's
     * default {@code sa} account has no password at all), not "unset, fall through to the next
     * source." Getting this wrong here would make this pre-check reject a config Spring's real
     * startup would have happily accepted — exactly the DB_PASSWORD="" case that broke the real
     * (non-dev) container-lifecycle CI job the first time it ever ran against this code path.
     */
    private static String resolveKey(String key, Properties fileProperties, String defaultValue) {
        String fromSystemProperty = System.getProperty(key);
        if (fromSystemProperty != null) {
            return fromSystemProperty;
        }
        String fromEnv = System.getenv(key);
        if (fromEnv != null) {
            return fromEnv;
        }
        String fromFile = fileProperties.getProperty(key);
        if (fromFile != null) {
            return fromFile;
        }
        return defaultValue;
    }
}
