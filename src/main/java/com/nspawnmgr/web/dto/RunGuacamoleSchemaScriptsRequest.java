package com.nspawnmgr.web.dto;

public record RunGuacamoleSchemaScriptsRequest(
        String databaseType, String hostname, int port, String database, String username, String password,
        String scriptsDirectory) {
}
