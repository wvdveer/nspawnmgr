package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.DownloadedPackage;
import com.nspawnmgr.cli.PackageCacheFilesystem;
import com.nspawnmgr.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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

    public RealPackageCacheFilesystem(SettingsService settingsService, SshRemoteExecutor ssh) {
        this.settingsService = settingsService;
        this.ssh = ssh;
    }

    @Override
    public void upload(String targetPath, byte[] content) {
        // Base64 rather than a raw byte passthrough - the SSH stdin channel this arrives over is a
        // Java String (UTF-8 encoded), which would corrupt arbitrary binary content otherwise. The
        // script decodes it back to raw bytes server-side (nspawnmgr-upload-package.sh).
        String encoded = Base64.getEncoder().encodeToString(content);
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofMinutes(2),
                List.of(wrapperScript("nspawnmgr-upload-package.sh"), targetPath), encoded);
        if (!result.success()) {
            throw new ContainerCliException("Failed to upload package to " + targetPath + ": " + result.stderr());
        }
    }

    @Override
    public void delete(String path) {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15),
                List.of(wrapperScript("nspawnmgr-delete-cached-package.sh"), path));
        if (!result.success()) {
            throw new ContainerCliException("Failed to delete cached package " + path + ": " + result.stderr());
        }
    }

    @Override
    public void copyIntoContainer(String sourcePath, String destDir) {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(30),
                List.of(wrapperScript("nspawnmgr-copy-into-container.sh"), sourcePath, destDir));
        if (!result.success()) {
            throw new ContainerCliException("Failed to copy " + sourcePath + " into " + destDir + ": " + result.stderr());
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
