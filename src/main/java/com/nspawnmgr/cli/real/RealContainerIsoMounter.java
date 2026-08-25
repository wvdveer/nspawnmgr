package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerIsoMounter;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * SYSTEMD_NSPAWN: shells out to two fixed wrapper scripts under
 * {@code NspawnProperties.privilegedScriptsDir()} - purely host-side loop mount/unmount (see their
 * own comments) - getting the mount into the container is a static .nspawn setting
 * (NspawnSettingsRenderer), not this class's job. NOPASSWD, same as
 * {@link RealContainerOutboundAccessManager}.
 *
 * <p>QEMU: no loop-mount at all - only live-swaps the CD-ROM media via HMP if the VM happens to be
 * RUNNING right now (see {@link ContainerCliExecutor#changeQemuCdrom}/{@link
 * ContainerCliExecutor#ejectQemuCdrom}); a no-op while STOPPED, since
 * {@code ContainerFilesystemProvisioner#writeQemuUnit} (called separately by the same caller) is
 * what makes a STOPPED VM's *next* boot pick up the change.
 */
@Component
@Profile("!dev")
public class RealContainerIsoMounter implements ContainerIsoMounter {

    private static final Logger log = LoggerFactory.getLogger(RealContainerIsoMounter.class);

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;
    private final ContainerCliExecutor cliExecutor;

    public RealContainerIsoMounter(SettingsService settingsService, SshRemoteExecutor ssh, ContainerCliExecutor cliExecutor) {
        this.settingsService = settingsService;
        this.ssh = ssh;
        this.cliExecutor = cliExecutor;
    }

    @Override
    public void mount(String machineName, ContainerBackend backend, String isoHostSourcePath) {
        if (backend == ContainerBackend.QEMU) {
            if (cliExecutor.status(machineName, ContainerBackend.QEMU) == MachineStatus.RUNNING) {
                try {
                    cliExecutor.changeQemuCdrom(machineName, isoHostSourcePath);
                } catch (Exception e) {
                    log.warn("Live CD-ROM swap failed for running QEMU VM {} - will still take effect on next start: {}",
                            machineName, e.getMessage(), e);
                }
            }
            return;
        }
        runWrapper(Duration.ofSeconds(30), "nspawnmgr-mount-iso.sh", machineName, isoHostSourcePath);
    }

    @Override
    public void unmount(String machineName, ContainerBackend backend) {
        if (backend == ContainerBackend.QEMU) {
            if (cliExecutor.status(machineName, ContainerBackend.QEMU) == MachineStatus.RUNNING) {
                try {
                    cliExecutor.ejectQemuCdrom(machineName);
                } catch (Exception e) {
                    log.warn("Live CD-ROM eject failed for running QEMU VM {} - will still take effect on next start: {}",
                            machineName, e.getMessage(), e);
                }
            }
            return;
        }
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
