package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.DownloadedPackage;
import com.nspawnmgr.cli.PackageCacheFilesystem;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Component
@Profile("!dev")
public class RealPackageCacheFilesystem implements PackageCacheFilesystem {

    private static final Logger log = LoggerFactory.getLogger(RealPackageCacheFilesystem.class);

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;
    private final UserMessages messages;

    public RealPackageCacheFilesystem(SettingsService settingsService, SshRemoteExecutor ssh, UserMessages messages) {
        this.settingsService = settingsService;
        this.ssh = ssh;
        this.messages = messages;
    }

    // Loopback SSH (same host Tomcat runs on - see SshRemoteExecutor's own javadoc), so this is
    // disk-to-disk + Base64 CPU overhead, not real network transfer time - 30 minutes is meant to
    // be generous headroom for a large (multi-GB) file on a modest host, not a tuned real-world
    // measurement.
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(30);

    @Override
    public void upload(String targetPath, InputStream content) {
        // Streams content -> Base64-encode -> stdin incrementally, never materializing the whole
        // file (encoded or not) in memory - confirmed live, the previous byte[]-then-one-big-
        // Base64-String approach caused a real OutOfMemoryError on a large real upload. Base64
        // rather than a raw byte passthrough - the SSH stdin channel is fundamentally a byte stream
        // the remote shell script reads, and the classic reason to Base64-wrap it is to survive a
        // text-oriented hop; here the real reason is compatibility with
        // SshRemoteExecutor's existing String-payload-oriented exec methods' own on-the-wire
        // convention - the script decodes it back to raw bytes server-side, streaming the whole way
        // (nspawnmgr-upload-package.sh's `base64 -d` never buffers its input either).
        CommandResult result = ssh.execNoPasswordSudoStreamingStdin(UPLOAD_TIMEOUT,
                List.of(wrapperScript("nspawnmgr-upload-package.sh"), targetPath),
                stdin -> {
                    try (OutputStream encoded = Base64.getEncoder().wrap(stdin)) {
                        content.transferTo(encoded);
                    }
                });
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.failedToUploadPackageTo", targetPath, result.stderr()));
        }
    }

    @Override
    public void delete(String path) {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15),
                List.of(wrapperScript("nspawnmgr-delete-cached-package.sh"), path));
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.failedToDeleteCachedPackage", path, result.stderr()));
        }
    }

    @Override
    public void copyIntoContainer(String sourcePath, String destDir) {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(30),
                List.of(wrapperScript("nspawnmgr-copy-into-container.sh"), sourcePath, destDir));
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.failedToCopyInto", sourcePath, destDir, result.stderr()));
        }
    }

    @Override
    public void copyIntoPodmanContainer(String sourcePath, String containerName, String destPathInContainer) {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(30),
                List.of("podman", "cp", sourcePath, containerName + ":" + destPathInContainer));
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.failedToCopyInto",
                    sourcePath, containerName + ":" + destPathInContainer, result.stderr()));
        }
    }

    @Override
    public List<DownloadedPackage> listFiles(String dir) {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15),
                List.of(wrapperScript("nspawnmgr-list-auto-cache.sh"), dir));
        if (!result.success()) {
            log.warn("nspawnmgr-list-auto-cache.sh failed for {} (exit {}): stdout={} stderr={}",
                    dir, result.exitCode(), result.stdout(), result.stderr());
            return List.of();
        }
        return DownloadedPackage.parseLines(result.stdout());
    }

    private String wrapperScript(String name) {
        return Path.of(settingsService.nspawnPrivilegedScriptsDir(), name).toString();
    }
}
