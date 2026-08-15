package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerFilesystemBrowser;
import com.nspawnmgr.cli.FileEntry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** File browsing isn't OS-specific, so this reuses plain NIO logic, same as FakePackageCacheFilesystem. */
@Component
@Profile("dev")
public class FakeContainerFilesystemBrowser implements ContainerFilesystemBrowser {

    @Override
    public List<FileEntry> list(String rootAbsolutePath, String relativeDir) {
        Path dir = Path.of(rootAbsolutePath, relativeDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<FileEntry> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.toList()) {
                boolean directory = Files.isDirectory(entry);
                long sizeBytes = directory ? 0 : Files.size(entry);
                long mtimeEpochSeconds = Files.getLastModifiedTime(entry).toInstant().getEpochSecond();
                result.add(new FileEntry(entry.getFileName().toString(), directory, sizeBytes, mtimeEpochSeconds));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + dir, e);
        }
        return result;
    }

    @Override
    public byte[] download(String rootAbsolutePath, String relativePath) {
        Path file = Path.of(rootAbsolutePath, relativePath);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ContainerCliException("Failed to download " + file, e);
        }
    }

    @Override
    public void upload(String rootAbsolutePath, String relativeDir, String filename, byte[] content) {
        Path target = Path.of(rootAbsolutePath, relativeDir, filename);
        if (Files.exists(target)) {
            throw new IllegalArgumentException("A file or folder named '" + filename + "' already exists in this directory.");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new ContainerCliException("Failed to upload to " + target, e);
        }
    }
}
