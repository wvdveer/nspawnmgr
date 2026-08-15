package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerIsoMounter;
import com.nspawnmgr.service.SettingsService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Shells out to two fixed wrapper scripts under {@code NspawnProperties.privilegedScriptsDir()} -
 * purely host-side loop mount/unmount (see their own comments) - getting the mount into the
 * container is a static .nspawn setting (NspawnSettingsRenderer), not this class's job. NOPASSWD,
 * same as {@link RealContainerOutboundAccessManager}.
 */
@Component
@Profile("!dev")
public class RealContainerIsoMounter implements ContainerIsoMounter {

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;

    public RealContainerIsoMounter(SettingsService settingsService, SshRemoteExecutor ssh) {
        this.settingsService = settingsService;
        this.ssh = ssh;
    }

    @Override
    public void mount(String machineName, String isoHostSourcePath) {
        runWrapper(Duration.ofSeconds(30), "nspawnmgr-mount-iso.sh", machineName, isoHostSourcePath);
    }

    @Override
    public void unmount(String machineName) {
        runWrapper(Duration.ofSeconds(30), "nspawnmgr-unmount-iso.sh", machineName);
    }

    private void runWrapper(Duration timeout, String scriptName, String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(settingsService.nspawnPrivilegedScriptsDir(), scriptName).toString());
        command.addAll(List.of(args));
        CommandResult result = ssh.execNoPasswordSudo(timeout, command);
        if (!result.success()) {
            throw new ContainerCliException("ISO mount command failed (" + scriptName + "): " + result.stdout() + " -- " + result.stderr());
        }
    }
}
