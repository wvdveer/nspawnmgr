package com.nspawnmgr.cli;

import java.time.Duration;
import java.util.List;

/**
 * Runs machinectl/systemd-nspawn/systemd-run for container lifecycle and one-off
 * exec-in-container commands. Real implementation runs these over SSH as a sudo-capable account
 * (Tomcat itself has no local root/sudo access) via SshRemoteExecutor; the dev profile swaps in a
 * fake from tools/fake-machinectl so this works on Windows.
 */
public interface ContainerCliExecutor {

    void start(String machineName);

    /** machinectl poweroff — graceful shutdown. */
    void stopGraceful(String machineName);

    /** machinectl terminate — hard stop. */
    void stopForce(String machineName);

    /** machinectl reboot — clean in-place restart (reboots the container's own OS; doesn't tear
     *  down and recreate the machine registration/veth the way stop+start would). */
    void restart(String machineName);

    /** machinectl remove — removes the machine's registration (filesystem cleanup is separate). */
    void remove(String machineName);

    /**
     * systemctl freeze on the machine's own service unit ({@code systemd-nspawn@<name>.service}) -
     * suspends every process in the container's cgroup in place via the kernel's cgroup freezer,
     * without tearing anything down. machinectl itself has no native pause/resume concept; this is
     * the modern systemd-native equivalent (systemd 246+). Confirmed live (2026-08-07): a container
     * started via {@code machinectl start} does NOT get its own separate {@code machine-<name>.scope}
     * - systemd-nspawn only creates that when launched outside of a service unit. When launched via
     * the {@code systemd-nspawn@.service} template (which is what {@code machinectl start} does),
     * the service unit itself is the cgroup boundary, so that's what freeze/thaw must target instead
     * - confirmed via {@code systemctl list-units 'systemd-nspawn@*' 'machine-*'} on a real host,
     * which showed only {@code systemd-nspawn@<name>.service} units, no scopes at all.
     */
    void pause(String machineName);

    /** systemctl thaw — reverses {@link #pause}, resuming every suspended process where it left off. */
    void resume(String machineName);

    MachineStatus status(String machineName);

    /**
     * machinectl shell / systemd-run --machine=... --pipe, for provisioning steps. Runs
     * template-authored content as root inside the container, so — unlike every other method on
     * this interface — it requires a sudo password rather than relying on a NOPASSWD grant.
     * {@code sudoPasswordOverride}, if non-null, is used instead of the configured stored password
     * (the admin-approval per-request flow); null falls back to the configured stored password
     * (self-service/stored-secret mode). {@code stdinPayload}, if non-null, is written to the
     * command's stdin — used to feed a container user's password to {@code chpasswd} without ever
     * putting it in the command text itself (unlike the sudo password, which is always
     * server-controlled/generated, this can be arbitrary owner-typed text, so it must never be
     * interpolated into a shell string).
     */
    CommandResult runInMachine(String machineName, List<String> command, Duration timeout,
                                char[] sudoPasswordOverride, String stdinPayload);

    /** Convenience overload with no stdin payload. */
    default CommandResult runInMachine(String machineName, List<String> command, Duration timeout,
                                        char[] sudoPasswordOverride) {
        return runInMachine(machineName, command, timeout, sudoPasswordOverride, null);
    }

    /** Convenience overload for stored-secret mode (no per-request override), no stdin payload. */
    default CommandResult runInMachine(String machineName, List<String> command, Duration timeout) {
        return runInMachine(machineName, command, timeout, null, null);
    }

    /**
     * Lists the container's Linux users ({@code getent passwd} output) — read-only and always safe,
     * so unlike {@link #runInMachine}, this runs under a fixed NOPASSWD wrapper script and never
     * needs a sudo password. Only meaningful while the container is actually up; callers are
     * responsible for falling back to a cache otherwise (see ContainerUserService).
     */
    CommandResult listContainerUsers(String machineName);

    /**
     * Resolves the internal IPv4 address currently assigned to the container's own host0 veth
     * interface, as seen from the host's network namespace — used to talk to the container
     * directly instead of through a host port-forward. Read-only and always safe, so like
     * {@link #listContainerUsers}, this runs under a fixed NOPASSWD wrapper script. Returns ""
     * (never throws) when the container has no address yet (e.g. still booting).
     */
    String getInternalAddress(String machineName);

    /**
     * Starts a stored {@code ContainerScript}'s body inside the container, capturing stdout/stderr
     * with a per-line timestamp (see {@link ScriptRunResult}). Unlike {@link #runInMachine}, this
     * is NOPASSWD — the content here is authored by the container's own owner/shared user, who
     * already has full interactive root-shell access to this same container via Guacamole, so
     * running it through this method grants no new privilege (see docs/administrator-guide.md's
     * trust-boundary section). {@code scriptBody} is written to the invoked shell's stdin, never
     * interpolated into a command string. Returns immediately with a handle that can be awaited
     * (blocking) or aborted from another thread — see {@link AbortableScriptRun}.
     */
    AbortableScriptRun startScript(String machineName, String scriptBody, Duration timeout);

    /** Convenience for callers that just want to block until the run finishes, as before. */
    default ScriptRunResult runScript(String machineName, String scriptBody, Duration timeout) {
        return startScript(machineName, scriptBody, timeout).await();
    }

    /**
     * Lists every machine image name {@code machinectl} knows about on this host - both
     * nspawnmgr-managed ones and anything created outside the app - used by
     * ContainerDiscoveryService to find names not yet tracked in the DB. Read-only and always
     * safe, so like {@link #listContainerUsers}, this runs under a fixed NOPASSWD wrapper script.
     */
    List<String> listMachineImageNames();

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
     * fixed NOPASSWD wrapper script.
     */
    MachineBootSettings getBootSettings(String machineName);

    /**
     * Enables or disables {@code machineName} auto-starting when the HOST itself boots
     * ({@code systemctl enable}/{@code disable} on its own {@code systemd-nspawn@<name>.service}) -
     * unit-file symlink manipulation only, safe for NOPASSWD (same posture as every other
     * always-safe, fixed-shape command on this interface).
     */
    void setAutoStart(String machineName, boolean autoStart);

    /**
     * Sets (or clears, when {@code requiresMachineName} is null/blank) {@code machineName}'s own
     * "requires another machine already started" dependency, via a systemd unit drop-in on its own
     * {@code systemd-nspawn@<name>.service} - a genuine {@code Requires=}+{@code After=}, not just a
     * soft ordering hint: if the required machine fails to start or later stops, systemd stops this
     * one too. Only meaningful when {@link MachineBootSettings#autoStart} is also true for
     * {@code machineName} - caller's own UI/validation concern, not enforced here.
     */
    void setRequiresMachine(String machineName, String requiresMachineName);
}
