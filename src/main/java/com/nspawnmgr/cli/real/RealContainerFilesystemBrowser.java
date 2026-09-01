package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerFilesystemBrowser;
import com.nspawnmgr.cli.FileEntry;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserMessages;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Component
@Profile("!dev")
public class RealContainerFilesystemBrowser implements ContainerFilesystemBrowser {

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;
    private final UserMessages messages;

    public RealContainerFilesystemBrowser(SettingsService settingsService, SshRemoteExecutor ssh, UserMessages messages) {
        this.settingsService = settingsService;
        this.ssh = ssh;
        this.messages = messages;
    }

    @Override
    public List<FileEntry> list(String rootAbsolutePath, String relativeDir) {
        String targetDir = Path.of(rootAbsolutePath, relativeDir).toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15),
                List.of(wrapperScript("nspawnmgr-list-rootfs-dir.sh"), targetDir));
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.failedToList", targetDir, result.stderr()));
        }
        return FileEntry.parseLines(result.stdout());
    }

    @Override
    public byte[] download(String rootAbsolutePath, String relativePath) {
        String targetFile = Path.of(rootAbsolutePath, relativePath).toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofMinutes(2),
                List.of(wrapperScript("nspawnmgr-download-rootfs-file.sh"), targetFile));
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.failedToDownload", targetFile, result.stderr()));
        }
        return Base64.getDecoder().decode(result.stdout().trim());
    }

    @Override
    public void upload(String rootAbsolutePath, String relativeDir, String filename, byte[] content) {
        String targetFile = Path.of(rootAbsolutePath, relativeDir, filename).toString();
        String encoded = Base64.getEncoder().encodeToString(content);
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofMinutes(2),
                List.of(wrapperScript("nspawnmgr-upload-rootfs-file.sh"), targetFile), encoded);
        if (result.exitCode() == 2) {
            throw new IllegalArgumentException(messages.get("error.validation.fileAlreadyExists", filename));
        }
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.failedToUploadTo", targetFile, result.stderr()));
        }
    }

    private String wrapperScript(String name) {
        return Path.of(settingsService.nspawnPrivilegedScriptsDir(), name).toString();
    }
}
