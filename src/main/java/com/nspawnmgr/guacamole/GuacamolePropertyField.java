package com.nspawnmgr.guacamole;

import java.util.List;

/**
 * One editable {@code guacamole.properties} key, as rendered on /admin/settings. {@code label}
 * and {@code helpText} are sourced from the official Apache Guacamole manual (guacd/mysql-auth/
 * postgresql-auth pages) so the form doesn't invent its own explanations of what each setting does.
 */
public record GuacamolePropertyField(
        String key,
        String label,
        GuacamolePropertyFieldType type,
        String defaultValue,
        String helpText,
        List<String> options
) {
    public GuacamolePropertyField(String key, String label, GuacamolePropertyFieldType type, String defaultValue, String helpText) {
        this(key, label, type, defaultValue, helpText, List.of());
    }
}
