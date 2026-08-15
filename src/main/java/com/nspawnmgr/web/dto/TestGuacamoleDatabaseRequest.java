package com.nspawnmgr.web.dto;

public record TestGuacamoleDatabaseRequest(
        String databaseType, String hostname, int port, String database, String username, String password) {
}
