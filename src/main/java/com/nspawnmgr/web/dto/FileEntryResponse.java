package com.nspawnmgr.web.dto;

public record FileEntryResponse(String name, boolean directory, long sizeBytes, long mtimeEpochSeconds) {

    public static FileEntryResponse from(com.nspawnmgr.cli.FileEntry entry) {
        return new FileEntryResponse(entry.name(), entry.directory(), entry.sizeBytes(), entry.mtimeEpochSeconds());
    }
}
