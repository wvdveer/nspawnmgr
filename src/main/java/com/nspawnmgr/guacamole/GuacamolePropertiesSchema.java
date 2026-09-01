package com.nspawnmgr.guacamole;

import com.nspawnmgr.service.UserMessages;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

import static com.nspawnmgr.guacamole.GuacamolePropertyFieldType.CHECKBOX;
import static com.nspawnmgr.guacamole.GuacamolePropertyFieldType.NUMBER;
import static com.nspawnmgr.guacamole.GuacamolePropertyFieldType.PASSWORD;
import static com.nspawnmgr.guacamole.GuacamolePropertyFieldType.SELECT;
import static com.nspawnmgr.guacamole.GuacamolePropertyFieldType.TEXT;

/**
 * The full set of {@code guacamole.properties} keys /admin/settings can edit. Labels/help text are
 * sourced from the official Apache Guacamole manual:
 * <a href="https://guacamole.apache.org/doc/gug/configuring-guacamole.html">guacd properties</a>,
 * <a href="https://guacamole.apache.org/doc/gug/mysql-auth.html">MySQL auth extension</a>,
 * <a href="https://guacamole.apache.org/doc/gug/postgresql-auth.html">PostgreSQL auth extension</a>,
 * translated via {@link UserMessages} (key convention: {@code guac.group.<name>} for a group's own
 * title, {@code guac.field.<property-key>.label}/{@code .help} for a field's own label/help text —
 * the property key itself, e.g. {@code mysql-ssl-mode}, is the stable per-field namespace segment).
 * Only one of the two database extensions is meant to be active at a time — see
 * SettingsService.writeGuacamoleProperties(), which clears the non-selected type's keys on save.
 *
 * <p>Built fresh per call (not cached) since the translated text depends on the current request's
 * resolved locale — negligible cost at this data size (under 70 fields).
 */
@Component
public class GuacamolePropertiesSchema {

    private final UserMessages messages;

    public GuacamolePropertiesSchema(UserMessages messages) {
        this.messages = messages;
    }

    private GuacamolePropertyField field(String key, GuacamolePropertyFieldType type, String defaultValue) {
        return new GuacamolePropertyField(key, messages.get("guac.field." + key + ".label"), type,
                defaultValue, messages.get("guac.field." + key + ".help"));
    }

    private GuacamolePropertyField field(String key, GuacamolePropertyFieldType type, String defaultValue,
                                          List<String> options) {
        return new GuacamolePropertyField(key, messages.get("guac.field." + key + ".label"), type,
                defaultValue, messages.get("guac.field." + key + ".help"), options);
    }

    private GuacamolePropertyGroup group(String titleKey, List<GuacamolePropertyField> fields) {
        return new GuacamolePropertyGroup(messages.get("guac.group." + titleKey), fields);
    }

    public List<GuacamolePropertyGroup> guacdGroups() {
        return List.of(
                group("guacdConnection", List.of(
                        field("guacd-hostname", TEXT, "localhost"),
                        field("guacd-port", NUMBER, "4822"),
                        field("guacd-ssl", CHECKBOX, "false")
                ))
        );
    }

    public List<GuacamolePropertyGroup> mysqlGroups() {
        return List.of(
                group("required", List.of(
                        field("mysql-database", TEXT, ""),
                        field("mysql-username", TEXT, ""),
                        field("mysql-password", PASSWORD, "")
                )),
                group("connection", List.of(
                        field("mysql-hostname", TEXT, "localhost"),
                        field("mysql-port", NUMBER, "3306"),
                        field("mysql-driver", SELECT, "", List.of("", "mysql", "mariadb")),
                        field("mysql-server-timezone", TEXT, ""),
                        field("mysql-batch-size", NUMBER, "1000")
                )),
                group("sslTls", List.of(
                        field("mysql-ssl-mode", SELECT, "preferred",
                                List.of("disabled", "preferred", "required", "verify-ca", "verify-identity")),
                        field("mysql-ssl-trust-store", TEXT, ""),
                        field("mysql-ssl-trust-password", PASSWORD, ""),
                        field("mysql-ssl-client-store", TEXT, ""),
                        field("mysql-ssl-client-password", PASSWORD, "")
                )),
                group("passwordPolicy", List.of(
                        field("mysql-user-password-min-length", NUMBER, ""),
                        field("mysql-user-password-require-multiple-case", CHECKBOX, "false"),
                        field("mysql-user-password-require-symbol", CHECKBOX, "false"),
                        field("mysql-user-password-require-digit", CHECKBOX, "false"),
                        field("mysql-user-password-prohibit-username", CHECKBOX, "false"),
                        field("mysql-user-password-min-age", NUMBER, "0"),
                        field("mysql-user-password-max-age", NUMBER, "0"),
                        field("mysql-user-password-history-size", NUMBER, "0")
                )),
                group("connectionConcurrencyLimits", List.of(
                        field("mysql-default-max-connections", NUMBER, "0"),
                        field("mysql-default-max-group-connections", NUMBER, "0"),
                        field("mysql-default-max-connections-per-user", NUMBER, "0"),
                        field("mysql-default-max-group-connections-per-user", NUMBER, "1"),
                        field("mysql-absolute-max-connections", NUMBER, "0")
                )),
                group("externalAuthIntegration", List.of(
                        field("mysql-user-required", CHECKBOX, "false"),
                        field("mysql-auto-create-accounts", CHECKBOX, "false"),
                        field("mysql-track-external-connection-history", CHECKBOX, "true")
                )),
                group("accessWindows", List.of(
                        field("mysql-enforce-access-windows-for-active-sessions", CHECKBOX, "true")
                ))
        );
    }

    public List<GuacamolePropertyGroup> postgresqlGroups() {
        return List.of(
                group("required", List.of(
                        field("postgresql-database", TEXT, ""),
                        field("postgresql-username", TEXT, ""),
                        field("postgresql-password", PASSWORD, "")
                )),
                group("connection", List.of(
                        field("postgresql-hostname", TEXT, "localhost"),
                        field("postgresql-port", NUMBER, "5432"),
                        field("postgresql-default-statement-timeout", NUMBER, "0"),
                        field("postgresql-socket-timeout", NUMBER, "0"),
                        field("postgresql-batch-size", NUMBER, "5000")
                )),
                group("sslTls", List.of(
                        field("postgresql-ssl-mode", SELECT, "prefer",
                                List.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full")),
                        field("postgresql-ssl-cert-file", TEXT, ""),
                        field("postgresql-ssl-key-file", TEXT, ""),
                        field("postgresql-ssl-root-cert-file", TEXT, ""),
                        field("postgresql-ssl-key-password", PASSWORD, "")
                )),
                group("passwordPolicy", List.of(
                        field("postgresql-user-password-min-length", NUMBER, ""),
                        field("postgresql-user-password-require-multiple-case", CHECKBOX, "false"),
                        field("postgresql-user-password-require-symbol", CHECKBOX, "false"),
                        field("postgresql-user-password-require-digit", CHECKBOX, "false"),
                        field("postgresql-user-password-prohibit-username", CHECKBOX, "false"),
                        field("postgresql-user-password-min-age", NUMBER, "0"),
                        field("postgresql-user-password-max-age", NUMBER, "0"),
                        field("postgresql-user-password-history-size", NUMBER, "0")
                )),
                group("connectionConcurrencyLimits", List.of(
                        field("postgresql-default-max-connections", NUMBER, "0"),
                        field("postgresql-default-max-group-connections", NUMBER, "0"),
                        field("postgresql-default-max-connections-per-user", NUMBER, "0"),
                        field("postgresql-default-max-group-connections-per-user", NUMBER, "1"),
                        field("postgresql-absolute-max-connections", NUMBER, "0")
                )),
                group("externalAuthIntegration", List.of(
                        field("postgresql-user-required", CHECKBOX, "false"),
                        field("postgresql-auto-create-accounts", CHECKBOX, "false"),
                        field("postgresql-track-external-connection-history", CHECKBOX, "true")
                )),
                group("accessWindows", List.of(
                        field("postgresql-enforce-access-windows-for-active-sessions", CHECKBOX, "true")
                ))
        );
    }

    public List<GuacamolePropertyGroup> groupsFor(String databaseType) {
        return "postgresql".equals(databaseType) ? postgresqlGroups() : mysqlGroups();
    }

    /** All mysql-* schema keys, flattened — used to clear stale entries when postgresql is selected. */
    public List<String> mysqlKeys() {
        return keysOf(mysqlGroups());
    }

    /** All postgresql-* schema keys, flattened — used to clear stale entries when mysql is selected. */
    public List<String> postgresqlKeys() {
        return keysOf(postgresqlGroups());
    }

    public List<String> guacdKeys() {
        return keysOf(guacdGroups());
    }

    private static List<String> keysOf(List<GuacamolePropertyGroup> groups) {
        return groups.stream()
                .flatMap(group -> group.fields().stream())
                .map(GuacamolePropertyField::key)
                .collect(Collectors.toList());
    }
}
