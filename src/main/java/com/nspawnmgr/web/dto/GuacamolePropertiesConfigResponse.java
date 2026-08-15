package com.nspawnmgr.web.dto;

import com.nspawnmgr.guacamole.GuacamolePropertyGroup;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything /admin/settings needs to render the structured Guacamole config editor: file status,
 * current values (pulled straight from disk, including passwords — this is an intentional editor,
 * not the earlier write-only design), which database type looks currently configured, and the
 * field schema (labels/help text sourced from the Guacamole manual) for both extensions so the
 * page can render the form without hardcoding field definitions itself.
 */
public record GuacamolePropertiesConfigResponse(
        String path,
        boolean fileExists,
        Instant lastModified,
        String databaseType,
        Map<String, String> values,
        List<GuacamolePropertyGroup> guacdGroups,
        List<GuacamolePropertyGroup> mysqlGroups,
        List<GuacamolePropertyGroup> postgresqlGroups
) {
}
