package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.TomcatConfigWriter;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserMessages;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Reuses the existing {@code nspawnmgr-write-file.sh} wrapper script (already NOPASSWD-granted,
 * already used by {@link RealContainerFilesystemProvisioner#writeNspawnSettings} for exactly this
 * "write arbitrary content to a root-owned path" shape) — no new wrapper script or sudoers entry
 * needed to let nspawnmgr's own process (which may not itself have write access to Tomcat's own
 * {@code conf/server.xml}) update it.
 */
@Component
@Profile("!dev")
public class RealTomcatConfigWriter implements TomcatConfigWriter {

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;
    private final UserMessages messages;

    public RealTomcatConfigWriter(SettingsService settingsService, SshRemoteExecutor ssh, UserMessages messages) {
        this.settingsService = settingsService;
        this.ssh = ssh;
        this.messages = messages;
    }

    @Override
    public void write(String path, String content) {
        String wrapperScript = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-write-file.sh").toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(wrapperScript, path), content);
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.failedToWrite", path, result.stderr()));
        }
    }
}
