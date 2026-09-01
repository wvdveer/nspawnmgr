package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.AbortableScriptRun;
import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.MachineBootSettings;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.config.SshProperties;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserMessages;
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
    private final UserMessages messages;

    public RealContainerCliExecutor(SshRemoteExecutor ssh, SshProperties sshProperties, SettingsService settingsService,
                                     UserMessages messages) {
        this.ssh = ssh;
        this.sshProperties = sshProperties;
        this.settingsService = settingsService;
        this.messages = messages;
    }

    @Override
    public void start(String machineName, ContainerBackend backend) {
        if (backend == ContainerBackend.PODMAN) {
            run(Duration.ofSeconds(30), "podman", "start", machineName);
        } else if (backend == ContainerBackend.QEMU) {
            // The VM's own unit file was already fully written (disk/ISO/MAC/VNC-port baked in) by
            // ContainerFilesystemProvisioner.writeQemuUnit at creation time, and rewritten on every
            // ISO mount/eject while stopped - a plain start needs nothing beyond the unit name,
            // exactly like podman/machinectl start.
            run(Duration.ofSeconds(30), "systemctl", "start", qemuUnit(machineName));
        } else {
            run(Duration.ofSeconds(30), "machinectl", "start", machineName);
        }
    }

    @Override
    public void stopGraceful(String machineName, ContainerBackend backend) {
        if (backend == ContainerBackend.PODMAN) {
            run(Duration.ofSeconds(30), "podman", "stop", machineName);
        } else if (backend == ContainerBackend.QEMU) {
            // HMP system_powerdown - an ACPI request the guest OS itself must answer. Does nothing
            // during the from-scratch ISO-installer phase (no OS yet to handle it) - a real,
            // accepted caveat, not a bug; Force stop is the only thing that works at that stage.
            qemuMonitorCommand(machineName, "system_powerdown");
        } else {
            run(Duration.ofSeconds(30), "machinectl", "poweroff", machineName);
        }
    }

    @Override
    public void stopForce(String machineName, ContainerBackend backend) {
        if (backend == ContainerBackend.PODMAN) {
            run(Duration.ofSeconds(15), "podman", "kill", machineName);
        } else if (backend == ContainerBackend.QEMU) {
            run(Duration.ofSeconds(15), "systemctl", "stop", qemuUnit(machineName));
        } else {
            run(Duration.ofSeconds(15), "machinectl", "terminate", machineName);
        }
    }

    @Override
    public void restart(String machineName, ContainerBackend backend) {
        if (backend == ContainerBackend.PODMAN) {
            run(Duration.ofSeconds(30), "podman", "restart", machineName);
        } else if (backend == ContainerBackend.QEMU) {
            run(Duration.ofSeconds(30), "systemctl", "restart", qemuUnit(machineName));
        } else {
            run(Duration.ofSeconds(30), "machinectl", "reboot", machineName);
        }
    }

    @Override
    public void pause(String machineName, ContainerBackend backend) {
        if (backend == ContainerBackend.PODMAN) {
            run(Duration.ofSeconds(15), "podman", "pause", machineName);
            return;
        }
        if (backend == ContainerBackend.QEMU) {
            qemuMonitorCommand(machineName, "stop");
            return;
        }
        // Confirmed live (2026-08-07): a container started via `machinectl start` runs as
        // systemd-nspawn@<name>.service, NOT as a separate machine-<name>.scope - nspawn only
        // creates its own scope when launched outside of a service unit. `systemctl freeze
        // machine-b2.scope` failed with "Unit machine-b2.scope not found" against a real container;
        // `systemctl list-units 'systemd-nspawn@*' 'machine-*'` on the same host showed only the
        // service units, no scopes. freeze/thaw work on any unit with a cgroup, service included.
        run(Duration.ofSeconds(15), "systemctl", "freeze", "systemd-nspawn@" + machineName + ".service");
    }

    @Override
    public void resume(String machineName, ContainerBackend backend) {
        if (backend == ContainerBackend.PODMAN) {
            run(Duration.ofSeconds(15), "podman", "unpause", machineName);
            return;
        }
        if (backend == ContainerBackend.QEMU) {
            qemuMonitorCommand(machineName, "cont");
            return;
        }
        run(Duration.ofSeconds(15), "systemctl", "thaw", "systemd-nspawn@" + machineName + ".service");
    }

    /**
     * machinectl terminate returns once teardown is *initiated*, not once systemd has finished
     * releasing the machine's mount/image — an immediate remove can lose that race with
     * "Could not remove image: Device or resource busy". Retry a few times rather than surfacing
     * a transient race as a hard failure. PODMAN's own `podman rm` doesn't share this race (no
     * separate retry loop) - confirmed by reasoning about podman's synchronous storage-layer
     * teardown, not yet exercised against a real podman host with this exact retry path.
     */
    @Override
    public void remove(String machineName, ContainerBackend backend) {
        if (backend == ContainerBackend.PODMAN) {
            run(Duration.ofSeconds(15), "podman", "rm", machineName);
            return;
        }
        if (backend == ContainerBackend.QEMU) {
            // Stops the unit (if running), deletes the unit file, the qcow2 disk, and the monitor
            // socket - see nspawnmgr-qemu-remove.sh. NOPASSWD: an owner removing their own VM by a
            // fixed name is a fixed-shape lifecycle operation, matching podman rm/machinectl remove
            // both being NOPASSWD despite VM/container *creation* needing a password.
            String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-qemu-remove.sh").toString();
            run(Duration.ofSeconds(20), scriptPath, machineName);
            return;
        }
        ContainerCliException lastFailure = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of("machinectl", "remove", machineName));
            if (result.success()) {
                return;
            }
            lastFailure = new ContainerCliException(messages.get("error.cli.commandFailed",
                    result.exitCode(), "machinectl remove " + machineName, result.stderr()));
            if (!result.stderr().contains("Device or resource busy")) {
                throw lastFailure;
            }
            sleep(Duration.ofSeconds(3));
        }
        throw lastFailure;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerCliException(messages.get("error.cli.interruptedRetryingMachinectlRemove"), e);
        }
    }

    @Override
    public MachineStatus status(String machineName, ContainerBackend backend) {
        if (backend == ContainerBackend.PODMAN) {
            CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(10),
                    List.of("podman", "inspect", machineName, "--format", "{{.State.Status}}"));
            if (!result.success()) {
                return MachineStatus.NOT_FOUND;
            }
            return "running".equals(result.stdout().trim()) ? MachineStatus.RUNNING : MachineStatus.STOPPED;
        }
        if (backend == ContainerBackend.QEMU) {
            // is-active exits nonzero for "inactive"/"failed" too, not just "unit doesn't exist" -
            // stdout still distinguishes the two ("unknown" only for a genuinely missing unit).
            CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(10),
                    List.of("systemctl", "is-active", qemuUnit(machineName)));
            String state = result.stdout().trim();
            if ("unknown".equals(state)) {
                return MachineStatus.NOT_FOUND;
            }
            return "active".equals(state) ? MachineStatus.RUNNING : MachineStatus.STOPPED;
        }
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
    public CommandResult runInMachine(String machineName, ContainerBackend backend, List<String> command, Duration timeout,
                                        char[] sudoPasswordOverride, String stdinPayload) {
        if (backend == ContainerBackend.QEMU) {
            // No guest-exec mechanism exists for QEMU - HMP is hypervisor control, not a way to run
            // arbitrary commands inside the guest OS. Scripts/package-install are simply absent from
            // qemu-detail.html (see the UI layer), so this should never actually be reachable; this
            // exists so a latent caller bug fails loudly instead of silently misbehaving as nspawn.
            throw new ContainerCliException(messages.get("error.cli.qemuNoExecMechanism", "runInMachine"));
        }
        char[] password = sudoPasswordOverride != null ? sudoPasswordOverride : sshProperties.password().toCharArray();
        List<String> full = new ArrayList<>(backend == ContainerBackend.PODMAN
                ? List.of("podman", "exec", "-i", machineName)
                : List.of("systemd-run", "--machine=" + machineName, "--pipe", "--quiet", "--wait"));
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
    public String getInternalAddress(String machineName, ContainerBackend backend) {
        if (backend == ContainerBackend.QEMU) {
            // A QEMU guest has neither a shared network namespace to nsenter into (unlike nspawn)
            // nor its own IPAM to ask (unlike podman) - it's genuine DHCP via systemd-networkd's own
            // [DHCPServer] on nspawnbr0 (see 70-nspawnmgr-bridge.network), so the only way to learn
            // the address is to look up the lease systemd-networkd itself handed out to this VM's
            // deterministic MAC (see nspawnmgr-get-qemu-internal-address.sh - NOT yet verified live
            // against a real systemd-networkd lease-file format).
            String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-get-qemu-internal-address.sh").toString();
            CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath, machineName));
            if (!result.success()) {
                log.warn("nspawnmgr-get-qemu-internal-address.sh failed for '{}' (exit {}): stdout={} stderr={}",
                        machineName, result.exitCode(), result.stdout(), result.stderr());
                return "";
            }
            return result.stdout().trim();
        }
        if (backend == ContainerBackend.PODMAN) {
            // The shared nspawnbr0 network specifically (see nspawnmgr-configure-podman-network.sh) -
            // a podman container could in principle be attached to some other network too, but every
            // one nspawnmgr itself creates uses this one, matching nspawn containers' own address
            // space so DNS/reachability-probing logic downstream doesn't need to care which backend
            // it's talking to.
            CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of("podman", "inspect", machineName,
                    "--format", "{{.NetworkSettings.Networks.nspawnbr0.IPAddress}}"));
            if (!result.success()) {
                log.warn("podman inspect (address) failed for '{}' (exit {}): stdout={} stderr={}",
                        machineName, result.exitCode(), result.stdout(), result.stderr());
                return "";
            }
            return result.stdout().trim();
        }
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
    public List<String> listPodmanContainerNames() {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15),
                List.of("podman", "ps", "-a", "--format", "{{.Names}}"));
        if (!result.success()) {
            log.warn("podman ps -a failed (exit {}): stdout={} stderr={}",
                    result.exitCode(), result.stdout(), result.stderr());
            return List.of();
        }
        return result.stdout().lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public List<String> listQemuVmNames() {
        // list-unit-files, not list-units: a VM's unit file is persistent (written once at
        // creation, rewritten on ISO change) regardless of whether it's currently running, so this
        // finds STOPPED VMs too - list-units would only show currently-active ones.
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15),
                List.of("systemctl", "list-unit-files", "nspawnmgr-qemu-*.service", "--no-legend"));
        if (!result.success()) {
            log.warn("systemctl list-unit-files (qemu) failed (exit {}): stdout={} stderr={}",
                    result.exitCode(), result.stdout(), result.stderr());
            return List.of();
        }
        return result.stdout().lines()
                .map(line -> line.split("\\s+")[0])
                .filter(unit -> unit.startsWith("nspawnmgr-qemu-") && unit.endsWith(".service"))
                .map(unit -> unit.substring("nspawnmgr-qemu-".length(), unit.length() - ".service".length()))
                .filter(name -> !name.isBlank())
                .toList();
    }

    @Override
    public void setQemuVncPassword(String machineName, String password) {
        qemuMonitorCommand(machineName, "set_password vnc " + password);
    }

    @Override
    public void changeQemuCdrom(String machineName, String isoHostPath) {
        qemuMonitorCommand(machineName, "change ide1-cd0 " + isoHostPath);
    }

    @Override
    public void ejectQemuCdrom(String machineName) {
        qemuMonitorCommand(machineName, "eject ide1-cd0");
    }

    @Override
    public String resolveHostname(String hostname) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-resolve-hostname.sh").toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath, hostname));
        if (!result.success() || result.stdout().isBlank()) {
            throw new ContainerCliException(messages.get("error.cli.couldNotResolveHostname",
                    hostname, result.exitCode(), result.stderr()));
        }
        return result.stdout().trim();
    }

    @Override
    public MachineBootSettings getBootSettings(String machineName) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-get-machine-boot-settings.sh").toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath, machineName));
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.couldNotReadBootSettings",
                    machineName, result.exitCode(), result.stderr()));
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
            throw new ContainerCliException(messages.get("error.cli.couldNotSetAutoStart",
                    machineName, result.exitCode(), result.stderr()));
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
            throw new ContainerCliException(messages.get("error.cli.couldNotSetRequiredMachine",
                    machineName, result.exitCode(), result.stderr()));
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
    //
    // PODMAN: `podman exec -i <name> sh -s` is the direct equivalent (stdin piped, real exit code
    // propagated) - but podman exec sessions get no equivalent of a named transient unit, so Abort
    // has no exact analog. killPodmanExecSession below is a narrower, best-effort substitute: the
    // script body is prefixed with `echo $$ > <pidfile>` (portable POSIX sh, no bashisms - unlike
    // `exec -a`, not assumed available in whatever /bin/sh the image provides) so the abort handler
    // can later `kill -9 -<pid>` (negated = the whole process *group*, not just the shell itself,
    // approximating systemd's own --kill-who=all for the common case of a shell spawning children)
    // - documented here as a known-narrower approximation, not a bug, same honest-caveat posture as
    // this session's other real-podman-mechanics claims.
    @Override
    public AbortableScriptRun startScript(String machineName, ContainerBackend backend, String scriptBody, Duration timeout) {
        if (backend == ContainerBackend.QEMU) {
            throw new ContainerCliException(messages.get("error.cli.qemuNoExecMechanism", "startScript"));
        }
        if (backend == ContainerBackend.PODMAN) {
            String pidFile = "/tmp/nspawnmgr-script-" + UUID.randomUUID() + ".pid";
            String markedScriptBody = "echo $$ > " + pidFile + "\n" + scriptBody;
            List<String> command = List.of("podman", "exec", "-i", machineName, "sh", "-s");
            return ssh.startNoPasswordSudoCapturingTimestampedOutput(timeout, command, markedScriptBody,
                    () -> killPodmanExecSession(machineName, pidFile));
        }
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

    /** As {@link #killTransientUnit}, for PODMAN - see {@link #startScript}'s own comment for why
     *  this is a narrower approximation (a process-group kill of the recorded PID, not a real
     *  cgroup-wide unit kill). */
    private void killPodmanExecSession(String machineName, String pidFile) {
        try {
            ssh.execNoPasswordSudo(Duration.ofSeconds(10), List.of("podman", "exec", machineName, "sh", "-c",
                    "kill -9 -\"$(cat " + pidFile + ")\" 2>/dev/null; rm -f " + pidFile));
        } catch (ContainerCliException e) {
            log.warn("Best-effort kill of podman exec session ({}) on {} failed: {}", pidFile, machineName, e.getMessage());
        }
    }

    private static String qemuUnit(String machineName) {
        return "nspawnmgr-qemu-" + machineName + ".service";
    }

    /**
     * Sends one HMP command line to machineName's monitor socket via nspawnmgr-qemu-monitor-exec.sh
     * (see that script's own comment for the socat -T2 relay approach and its "needs live tuning"
     * caveat) - the command text travels as stdin, never interpolated into the sudoers-matched argv.
     * Fire-and-forget from this method's own perspective (callers like stopGraceful/pause/resume
     * don't need the HMP reply text) - logs a warning rather than throwing on failure, since e.g.
     * system_powerdown against a VM with no OS installed yet legitimately does nothing and that's
     * not this method's problem to diagnose.
     */
    private void qemuMonitorCommand(String machineName, String hmpCommand) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-qemu-monitor-exec.sh").toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(10), List.of(scriptPath, machineName), hmpCommand + "\n");
        if (!result.success()) {
            log.warn("QEMU monitor command '{}' failed for '{}' (exit {}): stdout={} stderr={}",
                    hmpCommand, machineName, result.exitCode(), result.stdout(), result.stderr());
        }
    }

    private void run(Duration timeout, String... command) {
        CommandResult result = ssh.execNoPasswordSudo(timeout, List.of(command));
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.commandFailed",
                    result.exitCode(), String.join(" ", command), result.stderr()));
        }
    }
}
