package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
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
    // installPodman/installQemu are real apt-get install runs (network + package unpacking), not a
    // config flip like every other fix here - confirmed elsewhere in this app that a plain package
    // install can genuinely take minutes depending on mirror speed (see
    // ProvisioningService.PACKAGE_INSTALL_TIMEOUT's own reasoning), so FIX_TIMEOUT's 20s is nowhere
    // near enough for these two specifically.
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

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
    public CommandResult enableNetworkd(char[] sudoPassword) {
        return ssh.execWithSudoPassword(FIX_TIMEOUT,
                List.of("/usr/bin/systemctl", "enable", "--now", "systemd-networkd"), null, resolvePassword(sudoPassword));
    }

    @Override
    public CommandResult checkBridge() {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(),
                "nspawnmgr-diag-check-bridge.sh").toString();
        return ssh.execNoPasswordSudo(CHECK_TIMEOUT, List.of(scriptPath));
    }

    @Override
    public CommandResult checkPodman() {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(),
                "nspawnmgr-diag-check-podman.sh").toString();
        return ssh.execNoPasswordSudo(CHECK_TIMEOUT, List.of(scriptPath));
    }

    @Override
    public CommandResult checkQemu() {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(),
                "nspawnmgr-diag-check-qemu.sh").toString();
        return ssh.execNoPasswordSudo(CHECK_TIMEOUT, List.of(scriptPath));
    }

    @Override
    public CommandResult installPodman(char[] sudoPassword) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(),
                "nspawnmgr-install-podman.sh").toString();
        return ssh.execWithSudoPassword(INSTALL_TIMEOUT, List.of(scriptPath), null, resolvePassword(sudoPassword));
    }

    @Override
    public CommandResult installQemu(char[] sudoPassword) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(),
                "nspawnmgr-install-qemu.sh").toString();
        return ssh.execWithSudoPassword(INSTALL_TIMEOUT, List.of(scriptPath), null, resolvePassword(sudoPassword));
    }

    @Override
    public CommandResult checkPodmanNetwork() {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(),
                "nspawnmgr-diag-check-podman-network.sh").toString();
        return ssh.execNoPasswordSudo(CHECK_TIMEOUT, List.of(scriptPath));
    }

    @Override
    public CommandResult configurePodmanNetwork(char[] sudoPassword) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(),
                "nspawnmgr-configure-podman-network.sh").toString();
        return ssh.execWithSudoPassword(FIX_TIMEOUT, List.of(scriptPath), null, resolvePassword(sudoPassword));
    }

    @Override
    public CommandResult checkQemuBridge() {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(),
                "nspawnmgr-diag-check-qemu-bridge.sh").toString();
        return ssh.execNoPasswordSudo(CHECK_TIMEOUT, List.of(scriptPath));
    }

    @Override
    public CommandResult configureQemuBridge(char[] sudoPassword) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(),
                "nspawnmgr-configure-qemu-bridge.sh").toString();
        return ssh.execWithSudoPassword(FIX_TIMEOUT, List.of(scriptPath), null, resolvePassword(sudoPassword));
    }

    private char[] resolvePassword(char[] sudoPassword) {
        return sudoPassword != null && sudoPassword.length > 0 ? sudoPassword : settingsService.sshPassword().toCharArray();
    }
}
