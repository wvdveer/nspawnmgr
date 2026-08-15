package com.nspawnmgr.bootstrap;

/**
 * Explicitly loads (and thereby registers, via each driver's own static initializer) the JDBC
 * driver class for a vendor before any {@code DriverManager.getConnection()} call — reused from
 * {@code com.nspawnmgr.service.SettingsService} too (testing/provisioning Guacamole's own
 * database), not just this package.
 *
 * <p>Without this, {@code DriverManager} can fail with "No suitable driver found" even though
 * mysql-connector-j/postgresql are genuinely on this webapp's classpath: {@code DriverManager}'s
 * JDBC 4 auto-registration runs its {@code ServiceLoader} scan exactly once per JVM, using
 * whichever thread's context classloader happens to touch {@code DriverManager} *first* — which,
 * in a shared Tomcat instance, is not guaranteed to be this webapp's own classloader (another
 * webapp, or Tomcat's own startup machinery, can win that race first). Loading the driver class by
 * name sidesteps the race entirely: it runs in *this* webapp's classloader, every time.
 */
public final class JdbcDrivers {

    private JdbcDrivers() {
    }

    public static void load(String vendor) {
        String driverClass = switch (vendor) {
            case "postgresql" -> "org.postgresql.Driver";
            // Not a real deployment vendor (see docs/administrator-guide.md §4) - kept because
            // tools/scripts/real-lifecycle-test.sh genuinely runs Tomcat with -DDB_VENDOR=h2 (a
            // file-based H2 standing in for a "real" database) to exercise the actual pre-Spring
            // DbConnectionSettings.isReachable() check + Flyway/Hibernate boot path under
            // SPRING_PROFILES_ACTIVE=prod. Confirmed live: removing this case made isReachable()
            // silently fail (no driver found for the jdbc:h2: URL), which routes
            // NspawnmgrApplication.onStartup() into its "DB unreachable" placeholder-servlet branch
            // instead of booting normally - Flyway never runs, every downstream real-lifecycle-test
            // step fails with "Table ... not found (this database is empty)".
            case "h2" -> "org.h2.Driver";
            default -> "com.mysql.cj.jdbc.Driver";
        };
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            // All three drivers are always on the classpath (root pom.xml) — should be unreachable.
            throw new IllegalStateException("JDBC driver for '" + vendor + "' not found on the classpath", e);
        }
    }
}
