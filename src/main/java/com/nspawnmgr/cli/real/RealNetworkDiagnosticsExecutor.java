package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.NetworkDiagnosticsExecutor;
import com.nspawnmgr.service.SettingsService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs the network-diagnostics checks/fixes over SSH+sudo, same mechanism as
 * {@link RealContainerOutboundAccessManager}. Checks are fixed-shape read-only commands
 * (NOPASSWD-gated, see packaging/nspawnmgr-deb/debian/nspawnmgr.sudoers). Fixes are on the
 * password-required sudoers tier, but only actually need a fresh password entered when in
 * admin-approval mode - same fallback RealContainerCliExecutor.runInMachine already uses: a
 * null/blank override falls back to the stored sudo secret.
 */
@Component
@Profile("!dev")
public class RealNetworkDiagnosticsExecutor implements NetworkDiagnosticsExecutor {

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FIX_TIMEOUT = Duration.ofSeconds(20);

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;

    public RealNetworkDiagnosticsExecutor(SettingsService settingsService, SshRemoteExecutor ssh) {
        this.settingsService = settingsService;
        this.ssh = ssh;
    }

    @Override
    public CommandResult networkdStatus() {
        return ssh.execNoPasswordSudo(CHECK_TIMEOUT, List.of("/usr/bin/systemctl", "is-active", "systemd-networkd"));
    }

    @Override
    public CommandResult ufwStatus() {
        return ssh.execNoPasswordSudo(CHECK_TIMEOUT, List.of("/usr/sbin/ufw", "status", "verbose"));
    }

    @Override
    public CommandResult visudoCheck() {
        return ssh.execNoPasswordSudo(CHECK_TIMEOUT,
                List.of("/usr/sbin/visudo", "-cf", "/etc/sudoers.d/nspawnmgr_exec"));
    }

    @Override
    public CommandResult detectHostAddresses() {
        return ssh.execNoPasswordSudo(CHECK_TIMEOUT, List.of("/usr/sbin/ip", "-4", "-o", "addr", "show", "scope", "global"));
    }

    @Override
    public void enableNetworkd(char[] sudoPassword) {
        CommandResult result = ssh.execWithSudoPassword(FIX_TIMEOUT,
                List.of("/usr/bin/systemctl", "enable", "--now", "systemd-networkd"), null, resolvePassword(sudoPassword));
        if (!result.success()) {
            throw new ContainerCliException("Failed to enable systemd-networkd: " + result.stderr());
        }
    }

    @Override
    public CommandResult checkBridge() {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(),
                "nspawnmgr-diag-check-bridge.sh").toString();
        return ssh.execNoPasswordSudo(CHECK_TIMEOUT, List.of(scriptPath));
    }

    private char[] resolvePassword(char[] sudoPassword) {
        return sudoPassword != null && sudoPassword.length > 0 ? sudoPassword : settingsService.sshPassword().toCharArray();
    }
}
