package com.nspawnmgr.rootwizard;

/**
 * Explicitly loads (and thereby registers, via each driver's own static initializer) the JDBC
 * driver class for a vendor before any {@code DriverManager.getConnection()} call. A twin copy of
 * this exact class lives in the main nspawnmgr module too (shared there between
 * {@code com.nspawnmgr.service.SettingsService} and {@code com.nspawnmgr.guacamole.GuacamoleSchemaRunner})
 * — this module gets its own separate classloader, so there's no way to share one copy between the
 * two WARs; see {@code DbConnectionSettings}' own javadoc for this project's established
 * duplicate-small-Spring-free-classes-across-WAR-modules convention.
 *
 * <p>Without this, {@code DriverManager} can fail with "No suitable driver found" even though
 * mysql-connector-j/postgresql are genuinely on this webapp's classpath: {@code DriverManager}'s
 * JDBC 4 auto-registration runs its {@code ServiceLoader} scan exactly once per JVM, using
 * whichever thread's context classloader happens to touch {@code DriverManager} *first* — which,
 * in a shared Tomcat instance, is not guaranteed to be this webapp's own classloader (another
 * webapp, or Tomcat's own startup machinery, can win that race first). Loading the driver class by
 * name sidesteps the race entirely: it runs in *this* webapp's classloader, every time.
 */
final class JdbcDrivers {

    private JdbcDrivers() {
    }

    static void load(String vendor) {
        String driverClass = "postgresql".equals(vendor) ? "org.postgresql.Driver" : "com.mysql.cj.jdbc.Driver";
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            // Both drivers are always on the classpath (this module's own pom.xml) — should be unreachable.
            throw new IllegalStateException("JDBC driver for '" + vendor + "' not found on the classpath", e);
        }
    }
}
