package com.nspawnmgr.cli;

import com.nspawnmgr.domain.ContainerBackend;

import java.time.Duration;
import java.util.List;

/**
 * Runs machinectl/systemd-nspawn/systemd-run (SYSTEMD_NSPAWN backend) or podman (PODMAN backend)
 * for container lifecycle and one-off exec-in-container commands - see each method's own javadoc
 * for the exact command each backend maps to. Real implementation runs these over SSH as a
 * sudo-capable account (Tomcat itself has no local root/sudo access) via SshRemoteExecutor; the dev
 * profile swaps in a fake from tools/fake-machinectl so this works on Windows.
 *
 * <p>Machine-boot-settings methods ({@link #getBootSettings}/{@link #setAutoStart}/
 * {@link #setRequiresMachine}) and {@link #listContainerUsers} stay SYSTEMD_NSPAWN-only (no
 * {@code backend} parameter) - these are machinectl-specific concepts (unit-file autostart,
 * getent-passwd-in-a-mounted-rootfs) with no podman equivalent wired up yet, and were never asked
 * for on pods.
 */
public interface ContainerCliExecutor {

    void start(String machineName, ContainerBackend backend);

    /** machinectl poweroff (SYSTEMD_NSPAWN) / podman stop (PODMAN) — graceful shutdown. */
    void stopGraceful(String machineName, ContainerBackend backend);

    /** machinectl terminate (SYSTEMD_NSPAWN) / podman kill (PODMAN) — hard stop. */
    void stopForce(String machineName, ContainerBackend backend);

    /** machinectl reboot (SYSTEMD_NSPAWN) / podman restart (PODMAN) — clean in-place restart
     *  (reboots the container's own OS/process; doesn't tear down and recreate the machine
     *  registration/veth the way stop+start would). */
    void restart(String machineName, ContainerBackend backend);

    /** machinectl remove (SYSTEMD_NSPAWN) / podman rm (PODMAN) — removes the machine's/container's
     *  registration (filesystem cleanup is separate). */
    void remove(String machineName, ContainerBackend backend);

    /**
     * systemctl freeze on the machine's own service unit ({@code systemd-nspawn@<name>.service})
     * for SYSTEMD_NSPAWN - suspends every process in the container's cgroup in place via the
     * kernel's cgroup freezer, without tearing anything down. machinectl itself has no native
     * pause/resume concept; this is the modern systemd-native equivalent (systemd 246+). Confirmed
     * live (2026-08-07): a container started via {@code machinectl start} does NOT get its own
     * separate {@code machine-<name>.scope} - systemd-nspawn only creates that when launched
     * outside of a service unit. When launched via the {@code systemd-nspawn@.service} template
     * (which is what {@code machinectl start} does), the service unit itself is the cgroup
     * boundary, so that's what freeze/thaw must target instead - confirmed via
     * {@code systemctl list-units 'systemd-nspawn@*' 'machine-*'} on a real host, which showed only
     * {@code systemd-nspawn@<name>.service} units, no scopes at all. PODMAN uses its own native
     * {@code podman pause}.
     */
    void pause(String machineName, ContainerBackend backend);

    /** systemctl thaw (SYSTEMD_NSPAWN) / podman unpause (PODMAN) — reverses {@link #pause}. */
    void resume(String machineName, ContainerBackend backend);

    MachineStatus status(String machineName, ContainerBackend backend);

    /**
     * machinectl shell / systemd-run --machine=... --pipe (SYSTEMD_NSPAWN), or podman exec -i
     * (PODMAN), for provisioning steps. Runs template-authored content as root inside the
     * container, so — unlike every other method on this interface — it requires a sudo password
     * rather than relying on a NOPASSWD grant. {@code sudoPasswordOverride}, if non-null, is used
     * instead of the configured stored password (the admin-approval per-request flow); null falls
     * back to the configured stored password (self-service/stored-secret mode). {@code
     * stdinPayload}, if non-null, is written to the command's stdin — used to feed a container
     * user's password to {@code chpasswd} without ever putting it in the command text itself
     * (unlike the sudo password, which is always server-controlled/generated, this can be
     * arbitrary owner-typed text, so it must never be interpolated into a shell string).
     */
    CommandResult runInMachine(String machineName, ContainerBackend backend, List<String> command, Duration timeout,
                                char[] sudoPasswordOverride, String stdinPayload);

    /** Convenience overload with no stdin payload. */
    default CommandResult runInMachine(String machineName, ContainerBackend backend, List<String> command, Duration timeout,
                                        char[] sudoPasswordOverride) {
        return runInMachine(machineName, backend, command, timeout, sudoPasswordOverride, null);
    }

    /** Convenience overload for stored-secret mode (no per-request override), no stdin payload. */
    default CommandResult runInMachine(String machineName, ContainerBackend backend, List<String> command, Duration timeout) {
        return runInMachine(machineName, backend, command, timeout, null, null);
    }

    /**
     * Lists the container's Linux users ({@code getent passwd} output) — read-only and always safe,
     * so unlike {@link #runInMachine}, this runs under a fixed NOPASSWD wrapper script and never
     * needs a sudo password. Only meaningful while the container is actually up; callers are
     * responsible for falling back to a cache otherwise (see ContainerUserService). SYSTEMD_NSPAWN
     * only - see this interface's own javadoc.
     */
    CommandResult listContainerUsers(String machineName);

    /**
     * Resolves the internal IPv4 address currently assigned to the container's own network
     * interface, as seen from the host's network namespace — used to talk to the container
     * directly instead of through a host port-forward. Read-only and always safe, so like
     * {@link #listContainerUsers}, this runs under a fixed NOPASSWD wrapper script/command. Returns
     * "" (never throws) when the container has no address yet (e.g. still booting).
     */
    String getInternalAddress(String machineName, ContainerBackend backend);

    /**
     * Starts a stored {@code ContainerScript}'s body inside the container, capturing stdout/stderr
     * with a per-line timestamp (see {@link ScriptRunResult}). Unlike {@link #runInMachine}, this
     * is NOPASSWD — the content here is authored by the container's own owner/shared user, who
     * already has full interactive root-shell access to this same container via Guacamole, so
     * running it through this method grants no new privilege (see docs/administrator-guide.md's
     * trust-boundary section). {@code scriptBody} is written to the invoked shell's stdin, never
     * interpolated into a command string. Returns immediately with a handle that can be awaited
     * (blocking) or aborted from another thread — see {@link AbortableScriptRun}. PODMAN's abort
     * path has no exact analog to SYSTEMD_NSPAWN's named-transient-unit kill (see the real
     * implementation's own comment).
     */
    AbortableScriptRun startScript(String machineName, ContainerBackend backend, String scriptBody, Duration timeout);

    /** Convenience for callers that just want to block until the run finishes, as before. */
    default ScriptRunResult runScript(String machineName, ContainerBackend backend, String scriptBody, Duration timeout) {
        return startScript(machineName, backend, scriptBody, timeout).await();
    }

    /**
     * Lists every machine image name {@code machinectl} knows about on this host - both
     * nspawnmgr-managed ones and anything created outside the app - used by
     * ContainerDiscoveryService to find names not yet tracked in the DB. Read-only and always
     * safe, so like {@link #listContainerUsers}, this runs under a fixed NOPASSWD wrapper script.
     */
    List<String> listMachineImageNames();

    /**
     * As {@link #listMachineImageNames}, for podman containers ({@code podman ps -a}) - used by
     * ContainerDiscoveryService's podman discovery pass. Read-only and always safe, direct NOPASSWD
     * invocation (no wrapper script needed, same posture as the raw {@code podman inspect}/{@code
     * podman start} etc. commands this interface's other methods dispatch to).
     */
    List<String> listPodmanContainerNames();

    /**
     * As {@link #listMachineImageNames}, for QEMU VMs ({@code systemctl list-unit-files
     * nspawnmgr-qemu-*.service}) - used by ContainerDiscoveryService's QEMU discovery pass.
     * Deliberately list-unit-files, not list-units: a VM's unit file is written once at creation and
     * persists regardless of run state, so this finds STOPPED VMs too. Read-only and always safe,
     * direct NOPASSWD invocation.
     */
    List<String> listQemuVmNames();

    /**
     * Re-applies {@code password} as VM {@code machineName}'s VNC password via HMP {@code
     * set_password vnc} - QEMU doesn't persist this across process restarts, so every start/restart
     * needs a fresh call (see ContainerLifecycleService/ProvisioningService#provisionQemu). NOPASSWD,
     * same fixed-shape-command-text-never-in-argv posture as every other QEMU monitor command.
     */
    void setQemuVncPassword(String machineName, String password);

    /**
     * Live-swaps VM {@code machineName}'s CD-ROM media via HMP {@code change ide1-cd0 <path>} -
     * takes effect immediately, no restart needed. Only meaningful while the VM is RUNNING; callers
     * are responsible for checking that first (see ContainerIsoMounter's QEMU branch) - this is the
     * "boot-device switch" half of ISO mounting, the other half being
     * ContainerFilesystemProvisioner#writeQemuUnit persisting the choice for the VM's *next* boot.
     */
    void changeQemuCdrom(String machineName, String isoHostPath);

    /** As {@link #changeQemuCdrom}, for ejecting (HMP {@code eject ide1-cd0}). */
    void ejectQemuCdrom(String machineName);

    /**
     * Resolves {@code hostname} to an address using the HOST's own name resolution (DNS,
     * {@code /etc/hosts}, NSS modules such as mDNS/WINS if configured) - used by
     * ContainerSessionService to connect an EXTERNAL host by a hostname Guacamole's own SSH/RDP/VNC
     * client (now running inside the self-hosted {@code nspawnmgr} container - see the self-hosted
     * architecture docs) has no way to resolve on its own: that container's only DNS path is
     * nspawnmgr's own dnsmasq (container names + public upstream only), with zero visibility into
     * the host's own LAN-local name resolution. Read-only and always safe, so like
     * {@link #listContainerUsers}, this runs under a fixed NOPASSWD wrapper script.
     *
     * <p>Unlike {@link #getInternalAddress}, a resolution failure here throws rather than returning
     * ""  - an unresolvable hostname at connect time is a real, actionable problem for whoever's
     * trying to connect, not a routine "still booting" case worth swallowing and retrying silently.
     *
     * @throws ContainerCliException if {@code hostname} doesn't resolve, or the host is unreachable
     */
    String resolveHostname(String hostname);

    /**
     * Reads {@code machineName}'s own host-boot-time settings live from the host - see
     * {@link MachineBootSettings}'s own javadoc for why this is never cached in nspawnmgr's own
     * database. Read-only and always safe, so like {@link #listContainerUsers}, this runs under a
     * fixed NOPASSWD wrapper script. SYSTEMD_NSPAWN only - see this interface's own javadoc.
     */
    MachineBootSettings getBootSettings(String machineName);

    /**
     * Enables or disables {@code machineName} auto-starting when the HOST itself boots
     * ({@code systemctl enable}/{@code disable} on its own {@code systemd-nspawn@<name>.service}) -
     * unit-file symlink manipulation only, safe for NOPASSWD (same posture as every other
     * always-safe, fixed-shape command on this interface). SYSTEMD_NSPAWN only.
     */
    void setAutoStart(String machineName, boolean autoStart);

    /**
     * Sets (or clears, when {@code requiresMachineName} is null/blank) {@code machineName}'s own
     * "requires another machine already started" dependency, via a systemd unit drop-in on its own
     * {@code systemd-nspawn@<name>.service} - a genuine {@code Requires=}+{@code After=}, not just a
     * soft ordering hint: if the required machine fails to start or later stops, systemd stops this
     * one too. Only meaningful when {@link MachineBootSettings#autoStart} is also true for
     * {@code machineName} - caller's own UI/validation concern, not enforced here. SYSTEMD_NSPAWN only.
     */
    void setRequiresMachine(String machineName, String requiresMachineName);
}
