package com.nspawnmgr.web.dto;

public record HostResponse(
        Long id,
        String name,
        String hostname,
        String ownerUsername,
        boolean sshEnabled,
        Integer sshPort,
        boolean rdpEnabled,
        Integer rdpPort,
        boolean vncEnabled,
        Integer vncPort
) {
}
