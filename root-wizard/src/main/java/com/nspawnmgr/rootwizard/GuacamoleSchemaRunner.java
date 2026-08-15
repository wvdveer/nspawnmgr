package com.nspawnmgr.rootwizard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs guacamole-auth-jdbc's own {@code .sql} schema scripts against a real database. A twin copy
 * of this exact class lives in the main nspawnmgr module too (at
 * {@code com.nspawnmgr.guacamole.GuacamoleSchemaRunner}, which {@code SettingsService} still
 * delegates to for {@code /admin/settings}' "Test database connection" button) — this module gets
 * its own separate classloader, so there's no way to share one copy between the two WARs; see
 * {@code DbConnectionSettings}' own javadoc for this project's established
 * duplicate-small-Spring-free-classes-across-WAR-modules convention.
 */
final class GuacamoleSchemaRunner {

    private GuacamoleSchemaRunner() {
    }

    /**
     * Runs every {@code .sql} file in {@code scriptsDirectory}, in filename order (matching the
     * numbered-file convention {@code guacamole-auth-jdbc}'s own schema/ folder ships with —
     * {@code 001-create-schema.sql}, {@code 002-create-admin-user.sql}, etc.), against Guacamole's
     * database. Statement boundaries are found with {@link #splitSqlStatements}, not a plain
     * {@code split(";")} — every one of Guacamole's own schema files starts with the standard
     * Apache license header, which contains a semicolon inside a {@code --} comment line
     * ({@code -- "License"); you may not use this file...}); a naive split treats that as a
     * statement boundary and sends the (comment-only) fragment before it to the database as a
     * query, which fails with a syntax error before a single real statement ever runs.
     */
    static void runSchemaScripts(String databaseType, String hostname, int port, String database,
                                   String username, String password, String scriptsDirectory) {
        Path dir = Path.of(scriptsDirectory);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + scriptsDirectory);
        }
        List<Path> scripts;
        try (Stream<Path> stream = Files.list(dir)) {
            scripts = stream.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not list " + scriptsDirectory + ": " + e.getMessage(), e);
        }
        if (scripts.isEmpty()) {
            throw new IllegalArgumentException("No .sql files found in " + scriptsDirectory);
        }

        String url = jdbcUrl(databaseType, hostname, port, database);
        JdbcDrivers.load(databaseType);
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            for (Path script : scripts) {
                String content;
                try {
                    content = Files.readString(script);
                } catch (IOException e) {
                    throw new IllegalStateException("Could not read " + script + ": " + e.getMessage(), e);
                }
                try (Statement statement = connection.createStatement()) {
                    for (String sql : splitSqlStatements(content)) {
                        String trimmed = sql.strip();
                        if (!trimmed.isEmpty()) {
                            statement.execute(trimmed);
                        }
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException("Failed running " + script.getFileName() + ": " + e.getMessage(), e);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not connect: " + e.getMessage(), e);
        }
    }

    /** True if {@code tableName} (a fixed constant, never admin-supplied) exists and is queryable on {@code connection}. */
    static boolean tableExists(Connection connection, String tableName) {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT 1 FROM " + tableName + " WHERE 1 = 0");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    static String jdbcUrl(String databaseType, String hostname, int port, String database) {
        return "jdbc:" + databaseType + "://" + hostname + ":" + port + "/" + database;
    }

    /**
     * Splits {@code content} into individual statements on top-level {@code ;} characters only —
     * tracks {@code --}/{@code /* *}{@code /} comments and {@code '}/{@code "}/backtick-quoted
     * regions (with both {@code \}-escape and doubled-quote-escape) so a semicolon inside any of
     * those is never mistaken for a statement boundary. See {@link #runSchemaScripts} for why this
     * matters in practice, not just in theory.
     */
    static List<String> splitSqlStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int length = content.length();
        int i = 0;
        while (i < length) {
            char c = content.charAt(i);
            if (c == '-' && i + 1 < length && content.charAt(i + 1) == '-') {
                int end = content.indexOf('\n', i);
                if (end == -1) {
                    end = length;
                }
                current.append(content, i, end);
                i = end;
            } else if (c == '/' && i + 1 < length && content.charAt(i + 1) == '*') {
                int end = content.indexOf("*/", i + 2);
                end = (end == -1) ? length : end + 2;
                current.append(content, i, end);
                i = end;
            } else if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                int j = i + 1;
                while (j < length) {
                    char cj = content.charAt(j);
                    if (cj == '\\' && j + 1 < length) {
                        j += 2;
                    } else if (cj == quote) {
                        if (j + 1 < length && content.charAt(j + 1) == quote) {
                            j += 2;
                        } else {
                            j++;
                            break;
                        }
                    } else {
                        j++;
                    }
                }
                current.append(content, i, j);
                i = j;
            } else if (c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
    }
}
