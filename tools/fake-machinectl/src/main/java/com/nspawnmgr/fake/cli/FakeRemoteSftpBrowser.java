package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.FileEntry;
import com.nspawnmgr.cli.RemoteSftpBrowser;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accepts any credential (this is a fake - there's no real auth to check) and serves a small,
 * lazily-created scratch directory as the "home directory" for whichever {@code address} is
 * connected to, keyed by address so separate dev-stack Files tabs (a fake QEMU VM vs a fake
 * EXTERNAL host) don't share one tree. Plain NIO logic, same as {@link FakeContainerFilesystemBrowser}.
 *
 * <p>Every {@code absoluteDir}/{@code absolutePath} parameter is used directly as a real path on
 * this dev machine's own filesystem (see {@link RemoteSftpBrowser}'s own javadoc: browsing is
 * genuinely unrestricted, not capped at the fake home directory) - harmless in a dev-only context,
 * unlike the real implementation this stands in for.
 */
@Component
@Profile("dev")
public class FakeRemoteSftpBrowser implements RemoteSftpBrowser {

    private final Map<String, Path> homeDirsByAddress = new ConcurrentHashMap<>();

    @Override
    public List<FileEntry> list(String address, int port, String username, char[] password, String absoluteDir) {
        Path dir = Path.of(absoluteDir);
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
    public byte[] download(String address, int port, String username, char[] password, String absolutePath) {
        Path file = Path.of(absolutePath);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ContainerCliException("Failed to download " + file, e);
        }
    }

    @Override
    public void upload(String address, int port, String username, char[] password, String absoluteDir, String filename, byte[] content) {
        Path target = Path.of(absoluteDir).resolve(filename);
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

    @Override
    public String testConnection(String address, int port, String username, char[] password) {
        // Any credential "authenticates" against a fake target - just make sure the scratch tree exists.
        return homeDir(address).toString();
    }

    /** Lazily creates a small scratch tree the first time each address is connected to - only used
     *  to seed a sensible starting point via {@link #testConnection}; list/download/upload above
     *  never resolve through this once connected, since browsing is unrestricted. */
    private Path homeDir(String address) {
        return homeDirsByAddress.computeIfAbsent(address, addr -> {
            try {
                Path dir = Files.createTempDirectory("nspawnmgr-fake-sftp-");
                Files.writeString(dir.resolve("welcome.txt"),
                        "This is a fake SFTP home directory for " + addr + " (dev-stack only).\n",
                        StandardCharsets.UTF_8);
                Files.createDirectory(dir.resolve("notes"));
                Files.writeString(dir.resolve("notes").resolve("todo.txt"), "- try uploading a file\n- try downloading welcome.txt\n",
                        StandardCharsets.UTF_8);
                return dir;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create fake SFTP home directory for " + addr, e);
            }
        });
    }
}
