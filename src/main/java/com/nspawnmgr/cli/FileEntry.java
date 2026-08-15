package com.nspawnmgr.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * One immediate child of a directory listed by {@link ContainerFilesystemBrowser#list} - backs the
 * per-container Files browser page.
 */
public record FileEntry(String name, boolean directory, long sizeBytes, long mtimeEpochSeconds) {

    /**
     * Parses "type\tname\tsize\tmtime" lines (one per entry - see
     * nspawnmgr-list-rootfs-dir.sh's own output convention; tab-separated rather than
     * space-separated like {@link DownloadedPackage#parseLines}, since filenames here are
     * arbitrary user content and may contain spaces). Lines that don't match are silently skipped
     * rather than failing the whole call, same posture as {@link DownloadedPackage#parseLines}.
     */
    public static List<FileEntry> parseLines(String stdout) {
        List<FileEntry> result = new ArrayList<>();
        for (String line : stdout.lines().toList()) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 4) {
                continue;
            }
            try {
                boolean directory = "d".equals(parts[0]);
                long sizeBytes = Long.parseLong(parts[2]);
                long mtimeEpochSeconds = Long.parseLong(parts[3]);
                result.add(new FileEntry(parts[1], directory, sizeBytes, mtimeEpochSeconds));
            } catch (NumberFormatException e) {
                // Not one of the expected output lines - ignore rather than fail the whole call.
            }
        }
        return result;
    }
}
