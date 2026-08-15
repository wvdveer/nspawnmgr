package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.AbortableScriptRun;
import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.MachineBootSettings;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.config.SshProperties;
import com.nspawnmgr.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("!dev")
public class RealContainerCliExecutor implements ContainerCliExecutor {

    private static final Logger log = LoggerFactory.getLogger(RealContainerCliExecutor.class);

    private final SshRemoteExecutor ssh;
    private final SshProperties sshProperties;
    private final SettingsService settingsService;

    public RealContainerCliExecutor(SshRemoteExecutor ssh, SshProperties sshProperties, SettingsService settingsService) {
        this.ssh = ssh;
        this.sshProperties = sshProperties;
        this.settingsService = settingsService;
    }

    @Override
    public void start(String machineName) {
        run(Duration.ofSeconds(30), "machinectl", "start", machineName);
    }

    @Override
    public void stopGraceful(String machineName) {
        run(Duration.ofSeconds(30), "machinectl", "poweroff", machineName);
    }

    @Override
    public void stopForce(String machineName) {
        run(Duration.ofSeconds(15), "machinectl", "terminate", machineName);
    }

    @Override
    public void restart(String machineName) {
        run(Duration.ofSeconds(30), "machinectl", "reboot", machineName);
    }

    @Override
    public void pause(String machineName) {
        // Confirmed live (2026-08-07): a container started via `machinectl start` runs as
        // systemd-nspawn@<name>.service, NOT as a separate machine-<name>.scope - nspawn only
        // creates its own scope when launched outside of a service unit. `systemctl freeze
        // machine-b2.scope` failed with "Unit machine-b2.scope not found" against a real container;
        // `systemctl list-units 'systemd-nspawn@*' 'machine-*'` on the same host showed only the
        // service units, no scopes. freeze/thaw work on any unit with a cgroup, service included.
        run(Duration.ofSeconds(15), "systemctl", "freeze", "systemd-nspawn@" + machineName + ".service");
    }

    @Override
    public void resume(String machineName) {
        run(Duration.ofSeconds(15), "systemctl", "thaw", "systemd-nspawn@" + machineName + ".service");
    }

    /**
     * machinectl terminate returns once teardown is *initiated*, not once systemd has finished
     * releasing the machine's mount/image — an immediate remove can lose that race with
     * "Could not remove image: Device or resource busy". Retry a few times rather than surfacing
     * a transient race as a hard failure.
     */
    @Override
    public void remove(String machineName) {
        ContainerCliException lastFailure = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of("machinectl", "remove", machineName));
            if (result.success()) {
                return;
            }
            lastFailure = new ContainerCliException("Command failed (%d): machinectl remove %s -- %s"
                    .formatted(result.exitCode(), machineName, result.stderr()));
            if (!result.stderr().contains("Device or resource busy")) {
                throw lastFailure;
            }
            sleep(Duration.ofSeconds(3));
        }
        throw lastFailure;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerCliException("Interrupted while waiting to retry machinectl remove", e);
        }
    }

    @Override
    public MachineStatus status(String machineName) {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(10),
                List.of("machinectl", "show", machineName, "--property=State"));
        if (!result.success()) {
            return MachineStatus.NOT_FOUND;
        }
        String state = result.stdout().trim();
        if (state.startsWith("State=running")) {
            return MachineStatus.RUNNING;
        }
        return MachineStatus.STOPPED;
    }

    @Override
    public CommandResult runInMachine(String machineName, List<String> command, Duration timeout,
                                        char[] sudoPasswordOverride, String stdinPayload) {
        char[] password = sudoPasswordOverride != null ? sudoPasswordOverride : sshProperties.password().toCharArray();
        List<String> full = new ArrayList<>(List.of("systemd-run", "--machine=" + machineName, "--pipe", "--quiet", "--wait"));
        full.addAll(command);
        return ssh.execWithSudoPassword(timeout, full, stdinPayload, password);
    }

    @Override
    public CommandResult listContainerUsers(String machineName) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-list-container-users.sh").toString();
        return ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath, machineName));
    }

    // Only exit-code failures are logged here, not "ran fine, no address yet" (empty stdout, exit
    // 0 - the normal, expected shape while a container is still booting/hasn't gotten a DHCP lease
    // yet) - that would fire on every poll tick of every still-booting container, drowning out
    // everything else. A nonzero exit means the script itself couldn't even run (machinectl/nsenter
    // failure), which should never happen routinely and is worth surfacing.
    @Override
    public String getInternalAddress(String machineName) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-get-internal-address.sh").toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath, machineName));
        if (!result.success()) {
            log.warn("nspawnmgr-get-internal-address.sh failed for '{}' (exit {}): stdout={} stderr={}",
                    machineName, result.exitCode(), result.stdout(), result.stderr());
            return "";
        }
        return result.stdout().trim();
    }

    @Override
    public List<String> listMachineImageNames() {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-list-machine-images.sh").toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath));
        if (!result.success()) {
            log.warn("nspawnmgr-list-machine-images.sh failed (exit {}): stdout={} stderr={}",
                    result.exitCode(), result.stdout(), result.stderr());
            return List.of();
        }
        return result.stdout().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public String resolveHostname(String hostname) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-resolve-hostname.sh").toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath, hostname));
        if (!result.success() || result.stdout().isBlank()) {
            throw new ContainerCliException("Could not resolve hostname '" + hostname + "' on the host (exit "
                    + result.exitCode() + "): " + result.stderr());
        }
        return result.stdout().trim();
    }

    @Override
    public MachineBootSettings getBootSettings(String machineName) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-get-machine-boot-settings.sh").toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath, machineName));
        if (!result.success()) {
            throw new ContainerCliException("Could not read boot settings for '" + machineName + "' (exit "
                    + result.exitCode() + "): " + result.stderr());
        }
        Map<String, String> values = new HashMap<>();
        for (String line : result.stdout().lines().toList()) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        boolean autoStart = "true".equals(values.get("ENABLED"));
        String requires = values.get("REQUIRES");
        return new MachineBootSettings(autoStart, (requires == null || requires.isBlank()) ? null : requires);
    }

    @Override
    public void setAutoStart(String machineName, boolean autoStart) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-set-machine-autostart.sh").toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath, machineName, String.valueOf(autoStart)));
        if (!result.success()) {
            throw new ContainerCliException("Could not set auto-start for '" + machineName + "' (exit "
                    + result.exitCode() + "): " + result.stderr());
        }
    }

    @Override
    public void setRequiresMachine(String machineName, String requiresMachineName) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-set-machine-requires.sh").toString();
        List<String> command = (requiresMachineName == null || requiresMachineName.isBlank())
                ? List.of(scriptPath, machineName)
                : List.of(scriptPath, machineName, requiresMachineName);
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), command);
        if (!result.success()) {
            throw new ContainerCliException("Could not set '" + machineName + "'s required machine (exit "
                    + result.exitCode() + "): " + result.stderr());
        }
    }

    // systemd-run --pipe, not machinectl shell: confirmed live, machinectl shell doesn't work
    // non-interactively over a piped SSH exec channel (no PTY) - it never delivers piped stdin to
    // the container and never returns the invoked command's real exit code, appearing to require
    // genuine interactive terminal allocation regardless of how it's invoked. systemd-run --pipe is
    // the same primitive runInMachine already uses, just NOPASSWD (see interface javadoc) with
    // /bin/sh -s reading the script body from stdin instead of an inline command. --unit= gives the
    // transient unit a name we control, so an Abort request can target it later via killTransientUnit
    // below - without it, systemd-run picks an unpredictable auto-generated name.
    @Override
    public AbortableScriptRun startScript(String machineName, String scriptBody, Duration timeout) {
        String unit = "nspawnmgr-script-" + UUID.randomUUID() + ".service";
        List<String> command = List.of("systemd-run", "--machine=" + machineName, "--pipe", "--quiet", "--wait",
                "--unit=" + unit, "/bin/sh", "-s");
        return ssh.startNoPasswordSudoCapturingTimestampedOutput(timeout, command, scriptBody,
                () -> killTransientUnit(machineName, unit));
    }

    // The real kill on Abort: closing the SSH channel (see SshRemoteExecutor.abort()) only stops us
    // waiting on the command, it doesn't reliably kill the remote process by itself, since
    // systemd-run --pipe --wait's own process on the host is what's attached to that channel, not
    // the script running inside the container. This targets the transient unit directly, over a
    // separate SSH connection - best-effort, since the run may already be finishing on its own.
    private void killTransientUnit(String machineName, String unit) {
        try {
            ssh.execNoPasswordSudo(Duration.ofSeconds(10),
                    List.of("systemctl", "--machine=" + machineName, "kill", "--signal=SIGKILL", "--kill-who=all", unit));
        } catch (ContainerCliException e) {
            log.warn("Best-effort kill of transient unit {} on {} failed: {}", unit, machineName, e.getMessage());
        }
    }

    private void run(Duration timeout, String... command) {
        CommandResult result = ssh.execNoPasswordSudo(timeout, List.of(command));
        if (!result.success()) {
            throw new ContainerCliException("Command failed (%d): %s -- %s"
                    .formatted(result.exitCode(), String.join(" ", command), result.stderr()));
        }
    }
}
