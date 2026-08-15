package com.nspawnmgr.guacamole;

import java.util.List;
import java.util.stream.Collectors;

import static com.nspawnmgr.guacamole.GuacamolePropertyFieldType.CHECKBOX;
import static com.nspawnmgr.guacamole.GuacamolePropertyFieldType.NUMBER;
import static com.nspawnmgr.guacamole.GuacamolePropertyFieldType.PASSWORD;
import static com.nspawnmgr.guacamole.GuacamolePropertyFieldType.SELECT;
import static com.nspawnmgr.guacamole.GuacamolePropertyFieldType.TEXT;

/**
 * The full set of {@code guacamole.properties} keys /admin/settings can edit, with labels/help
 * text sourced from the official Apache Guacamole manual:
 * <a href="https://guacamole.apache.org/doc/gug/configuring-guacamole.html">guacd properties</a>,
 * <a href="https://guacamole.apache.org/doc/gug/mysql-auth.html">MySQL auth extension</a>,
 * <a href="https://guacamole.apache.org/doc/gug/postgresql-auth.html">PostgreSQL auth extension</a>.
 * Only one of the two database extensions is meant to be active at a time — see
 * SettingsService.writeGuacamoleProperties(), which clears the non-selected type's keys on save.
 */
public final class GuacamolePropertiesSchema {

    private GuacamolePropertiesSchema() {
    }

    public static final List<GuacamolePropertyGroup> GUACD_GROUPS = List.of(
            new GuacamolePropertyGroup("guacd connection", List.of(
                    new GuacamolePropertyField("guacd-hostname", "guacd hostname", TEXT, "localhost",
                            "The host the Guacamole proxy daemon (guacd) is listening on."),
                    new GuacamolePropertyField("guacd-port", "guacd port", NUMBER, "4822",
                            "The port the Guacamole proxy daemon (guacd) is listening on."),
                    new GuacamolePropertyField("guacd-ssl", "Require SSL/TLS to guacd", CHECKBOX, "false",
                            "If set to ‘true’, Guacamole will require SSL/TLS encryption between the web application and guacd.")
            ))
    );

    public static final List<GuacamolePropertyGroup> MYSQL_GROUPS = List.of(
            new GuacamolePropertyGroup("Required", List.of(
                    new GuacamolePropertyField("mysql-database", "Database name", TEXT, "",
                            "The name of the database that you created for Guacamole"),
                    new GuacamolePropertyField("mysql-username", "Username", TEXT, "",
                            "The username of the user that Guacamole should use to connect to the database"),
                    new GuacamolePropertyField("mysql-password", "Password", PASSWORD, "",
                            "The password Guacamole should provide when authenticating with the database")
            )),
            new GuacamolePropertyGroup("Connection", List.of(
                    new GuacamolePropertyField("mysql-hostname", "Hostname", TEXT, "localhost",
                            "The hostname or IP address of the server hosting your database"),
                    new GuacamolePropertyField("mysql-port", "Port", NUMBER, "3306",
                            "The port number of the MySQL or MariaDB database to connect to"),
                    new GuacamolePropertyField("mysql-driver", "JDBC driver", SELECT, "",
                            "Controls which JDBC driver is loaded (auto-detected if left blank).",
                            List.of("", "mysql", "mariadb")),
                    new GuacamolePropertyField("mysql-server-timezone", "Server timezone", TEXT, "",
                            "Specifies the timezone used by the MySQL server, in Region/Locale or GMT format (auto-detected if left blank)."),
                    new GuacamolePropertyField("mysql-batch-size", "Batch size", NUMBER, "1000",
                            "Controls how many objects may be retrieved from the database in a single query")
            )),
            new GuacamolePropertyGroup("SSL/TLS", List.of(
                    new GuacamolePropertyField("mysql-ssl-mode", "SSL mode", SELECT, "preferred",
                            "The SSL mode to use for the database connection.",
                            List.of("disabled", "preferred", "required", "verify-ca", "verify-identity")),
                    new GuacamolePropertyField("mysql-ssl-trust-store", "SSL trust store", TEXT, "",
                            "A JKS-formatted certificate store for validating the CA and server certificates (Java defaults used if left blank)."),
                    new GuacamolePropertyField("mysql-ssl-trust-password", "SSL trust store password", PASSWORD, "",
                            "Password for accessing the SSL trust store"),
                    new GuacamolePropertyField("mysql-ssl-client-store", "SSL client store", TEXT, "",
                            "A JKS store containing the client certificate and private key"),
                    new GuacamolePropertyField("mysql-ssl-client-password", "SSL client store password", PASSWORD, "",
                            "Password for accessing the client certificate store")
            )),
            new GuacamolePropertyGroup("Password policy", List.of(
                    new GuacamolePropertyField("mysql-user-password-min-length", "Minimum password length", NUMBER, "",
                            "The minimum length required of all user passwords, in characters (unenforced if left blank)."),
                    new GuacamolePropertyField("mysql-user-password-require-multiple-case", "Require upper and lower case", CHECKBOX, "false",
                            "Require both uppercase and lowercase characters in user passwords."),
                    new GuacamolePropertyField("mysql-user-password-require-symbol", "Require symbol", CHECKBOX, "false",
                            "Whether all user passwords must have at least one non-alphanumeric character."),
                    new GuacamolePropertyField("mysql-user-password-require-digit", "Require digit", CHECKBOX, "false",
                            "Whether all user passwords must have at least one numeric character."),
                    new GuacamolePropertyField("mysql-user-password-prohibit-username", "Prohibit username in password", CHECKBOX, "false",
                            "Prevent users from including their username in their password."),
                    new GuacamolePropertyField("mysql-user-password-min-age", "Minimum password age (days)", NUMBER, "0",
                            "The minimum number of days which must elapse before a user may reset their password"),
                    new GuacamolePropertyField("mysql-user-password-max-age", "Maximum password age (days)", NUMBER, "0",
                            "The maximum number of days which may elapse before a user is automatically required to reset their password"),
                    new GuacamolePropertyField("mysql-user-password-history-size", "Password history size", NUMBER, "0",
                            "Number of previous passwords to remember; zero means no history is kept.")
            )),
            new GuacamolePropertyGroup("Connection concurrency limits", List.of(
                    new GuacamolePropertyField("mysql-default-max-connections", "Max connections per connection", NUMBER, "0",
                            "Limit on concurrent connections to any single connection (0 = unlimited)."),
                    new GuacamolePropertyField("mysql-default-max-group-connections", "Max connections per group", NUMBER, "0",
                            "Limit on concurrent connections to any connection group (0 = unlimited)."),
                    new GuacamolePropertyField("mysql-default-max-connections-per-user", "Max connections per user", NUMBER, "0",
                            "Limit on per-user concurrent access to individual connections (0 = unlimited)."),
                    new GuacamolePropertyField("mysql-default-max-group-connections-per-user", "Max group connections per user", NUMBER, "1",
                            "The maximum number of concurrent connections to allow to any one connection group by the same user"),
                    new GuacamolePropertyField("mysql-absolute-max-connections", "Absolute max connections", NUMBER, "0",
                            "Absolute system-wide connection limit (0 = unlimited).")
            )),
            new GuacamolePropertyGroup("External authentication integration", List.of(
                    new GuacamolePropertyField("mysql-user-required", "Require database user account", CHECKBOX, "false",
                            "Require a database user account even when authenticated through another extension."),
                    new GuacamolePropertyField("mysql-auto-create-accounts", "Auto-create accounts", CHECKBOX, "false",
                            "Automatically create user accounts in the database for users who have successfully authenticated through another extension"),
                    new GuacamolePropertyField("mysql-track-external-connection-history", "Track external connection history", CHECKBOX, "true",
                            "Create connection history records for connections not defined in the database.")
            )),
            new GuacamolePropertyGroup("Access windows", List.of(
                    new GuacamolePropertyField("mysql-enforce-access-windows-for-active-sessions", "Enforce access windows for active sessions", CHECKBOX, "true",
                            "Enforce time-based access windows, logging out users when their access window closes.")
            ))
    );

    public static final List<GuacamolePropertyGroup> POSTGRESQL_GROUPS = List.of(
            new GuacamolePropertyGroup("Required", List.of(
                    new GuacamolePropertyField("postgresql-database", "Database name", TEXT, "",
                            "The name of the database that you created for Guacamole"),
                    new GuacamolePropertyField("postgresql-username", "Username", TEXT, "",
                            "The username of the user that Guacamole should use to connect to the database"),
                    new GuacamolePropertyField("postgresql-password", "Password", PASSWORD, "",
                            "The password Guacamole should provide when authenticating with the database")
            )),
            new GuacamolePropertyGroup("Connection", List.of(
                    new GuacamolePropertyField("postgresql-hostname", "Hostname", TEXT, "localhost",
                            "The hostname or IP address of the server hosting your database"),
                    new GuacamolePropertyField("postgresql-port", "Port", NUMBER, "5432",
                            "The port number of the PostgreSQL database to connect to"),
                    new GuacamolePropertyField("postgresql-default-statement-timeout", "Statement timeout (seconds)", NUMBER, "0",
                            "Seconds before aborting queries (0 = disabled)."),
                    new GuacamolePropertyField("postgresql-socket-timeout", "Socket timeout (seconds)", NUMBER, "0",
                            "Seconds to wait for socket read operations (0 = disabled)."),
                    new GuacamolePropertyField("postgresql-batch-size", "Batch size", NUMBER, "5000",
                            "Controls how many objects may be retrieved from the database in a single query")
            )),
            new GuacamolePropertyGroup("SSL/TLS", List.of(
                    new GuacamolePropertyField("postgresql-ssl-mode", "SSL mode", SELECT, "prefer",
                            "The SSL mode to use for the database connection.",
                            List.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full")),
                    new GuacamolePropertyField("postgresql-ssl-cert-file", "SSL client certificate file", TEXT, "",
                            "The file containing the client certificate, in PEM format"),
                    new GuacamolePropertyField("postgresql-ssl-key-file", "SSL client private key file", TEXT, "",
                            "The file containing the client private key, in PEM format"),
                    new GuacamolePropertyField("postgresql-ssl-root-cert-file", "SSL root certificate file", TEXT, "",
                            "The file containing the root and intermediate certificates"),
                    new GuacamolePropertyField("postgresql-ssl-key-password", "SSL private key password", PASSWORD, "",
                            "Password for the encrypted client private key")
            )),
            new GuacamolePropertyGroup("Password policy", List.of(
                    new GuacamolePropertyField("postgresql-user-password-min-length", "Minimum password length", NUMBER, "",
                            "The minimum length required of all user passwords, in characters (unenforced if left blank)."),
                    new GuacamolePropertyField("postgresql-user-password-require-multiple-case", "Require upper and lower case", CHECKBOX, "false",
                            "Require both uppercase and lowercase characters in user passwords."),
                    new GuacamolePropertyField("postgresql-user-password-require-symbol", "Require symbol", CHECKBOX, "false",
                            "Whether all user passwords must have at least one non-alphanumeric character."),
                    new GuacamolePropertyField("postgresql-user-password-require-digit", "Require digit", CHECKBOX, "false",
                            "Whether all user passwords must have at least one numeric character."),
                    new GuacamolePropertyField("postgresql-user-password-prohibit-username", "Prohibit username in password", CHECKBOX, "false",
                            "Prevent users from including their username in their password."),
                    new GuacamolePropertyField("postgresql-user-password-min-age", "Minimum password age (days)", NUMBER, "0",
                            "The minimum number of days which must elapse before a user may reset their password"),
                    new GuacamolePropertyField("postgresql-user-password-max-age", "Maximum password age (days)", NUMBER, "0",
                            "The maximum number of days which may elapse before a user is automatically required to reset their password"),
                    new GuacamolePropertyField("postgresql-user-password-history-size", "Password history size", NUMBER, "0",
                            "Number of previous passwords to remember; zero means no history is kept.")
            )),
            new GuacamolePropertyGroup("Connection concurrency limits", List.of(
                    new GuacamolePropertyField("postgresql-default-max-connections", "Max connections per connection", NUMBER, "0",
                            "Limit on concurrent connections to any single connection (0 = unlimited)."),
                    new GuacamolePropertyField("postgresql-default-max-group-connections", "Max connections per group", NUMBER, "0",
                            "Limit on concurrent connections to any connection group (0 = unlimited)."),
                    new GuacamolePropertyField("postgresql-default-max-connections-per-user", "Max connections per user", NUMBER, "0",
                            "Limit on per-user concurrent access to individual connections (0 = unlimited)."),
                    new GuacamolePropertyField("postgresql-default-max-group-connections-per-user", "Max group connections per user", NUMBER, "1",
                            "The maximum number of concurrent connections to allow to any one connection group by the same user"),
                    new GuacamolePropertyField("postgresql-absolute-max-connections", "Absolute max connections", NUMBER, "0",
                            "Absolute system-wide connection limit (0 = unlimited).")
            )),
            new GuacamolePropertyGroup("External authentication integration", List.of(
                    new GuacamolePropertyField("postgresql-user-required", "Require database user account", CHECKBOX, "false",
                            "Whether a user account within the database is required for authentication to succeed"),
                    new GuacamolePropertyField("postgresql-auto-create-accounts", "Auto-create accounts", CHECKBOX, "false",
                            "Whether to automatically create user accounts in the database for users authenticated through another extension"),
                    new GuacamolePropertyField("postgresql-track-external-connection-history", "Track external connection history", CHECKBOX, "true",
                            "Create connection history records for connections not defined in the database.")
            )),
            new GuacamolePropertyGroup("Access windows", List.of(
                    new GuacamolePropertyField("postgresql-enforce-access-windows-for-active-sessions", "Enforce access windows for active sessions", CHECKBOX, "true",
                            "Enforces time-based access windows for already-logged-in users.")
            ))
    );

    public static List<GuacamolePropertyGroup> groupsFor(String databaseType) {
        return "postgresql".equals(databaseType) ? POSTGRESQL_GROUPS : MYSQL_GROUPS;
    }

    /** All mysql-* schema keys, flattened — used to clear stale entries when postgresql is selected. */
    public static List<String> mysqlKeys() {
        return keysOf(MYSQL_GROUPS);
    }

    /** All postgresql-* schema keys, flattened — used to clear stale entries when mysql is selected. */
    public static List<String> postgresqlKeys() {
        return keysOf(POSTGRESQL_GROUPS);
    }

    public static List<String> guacdKeys() {
        return keysOf(GUACD_GROUPS);
    }

    private static List<String> keysOf(List<GuacamolePropertyGroup> groups) {
        return groups.stream()
                .flatMap(group -> group.fields().stream())
                .map(GuacamolePropertyField::key)
                .collect(Collectors.toList());
    }
}
