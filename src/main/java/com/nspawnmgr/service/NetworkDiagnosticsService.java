package com.nspawnmgr.service;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.NetworkDiagnosticsExecutor;
import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.ContainerPortMapping;
import com.nspawnmgr.domain.PortMappingProtocol;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerPortMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Checks host prerequisites known to silently break container networking - the exact chain
 * diagnosed live, the hard way, in one very long session: a NAT hairpin exclusion for
 * 127.0.0.1 in systemd-networkd's own NAT table (only actually fixable by making sure
 * networkd itself is running - see checkNetworkd), a host firewall with no rule for a
 * container's forwarded port mappings, and HOST_PUBLIC_ADDRESS still pointing at loopback. See
 * docs/administrator-guide.md's HOST_PUBLIC_ADDRESS section for the full story.
 */
@Service
public class NetworkDiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(NetworkDiagnosticsService.class);

    public enum Status { OK, WARN, FAIL }

    /** {@code log} is only ever populated by a fix method's own response (the fix command's raw
     *  output), and only while the check is still failing afterward - once a fix actually
     *  resolves the check, there's nothing left worth showing, see the 5-arg constructor below
     *  used by every plain check-building method. */
    public record DiagnosticCheck(String id, String label, Status status, String detail, boolean fixable, String log) {
        public DiagnosticCheck(String id, String label, Status status, String detail, boolean fixable) {
            this(id, label, status, detail, fixable, null);
        }
    }

    private static final String[] EXCLUDED_INTERFACE_PREFIXES =
            {"tun", "tap", "wg", "ppp", "docker", "veth", "ve-", "br-"};

    private final NetworkDiagnosticsExecutor executor;
    private final SettingsService settingsService;
    private final AuditLogService auditLogService;
    private final ContainerPortMappingRepository portMappingRepository;

    public NetworkDiagnosticsService(NetworkDiagnosticsExecutor executor, SettingsService settingsService,
                                      AuditLogService auditLogService, ContainerPortMappingRepository portMappingRepository) {
        this.executor = executor;
        this.settingsService = settingsService;
        this.auditLogService = auditLogService;
        this.portMappingRepository = portMappingRepository;
    }

    public List<DiagnosticCheck> runChecks() {
        List<DiagnosticCheck> checks = new ArrayList<>();
        checks.add(checkNetworkd());
        checks.add(checkUfw());
        checks.add(checkHostAddress());
        checks.add(checkGuacd());
        checks.add(checkBridge());
        checks.add(checkSudoers());
        checks.add(checkPodman());
        checks.add(checkQemu());
        checks.add(checkPodmanNetwork());
        checks.add(checkQemuBridge());
        checks.forEach(NetworkDiagnosticsService::logIfNotOk);
        return checks;
    }

    /**
     * Logs every problem a check finds, not just the fix actions admins choose to take - the
     * whole point of this page is to leave a trace instead of a container silently failing with
     * nothing to go on, same reasoning as the WARN logging added to
     * RealContainerReadinessChecker/ContainerReadinessPollingService this same session.
     */
    private static void logIfNotOk(DiagnosticCheck check) {
        if (check.status() != Status.OK) {
            log.warn("Network diagnostics: {} [{}] - {}", check.label(), check.status(), check.detail());
        }
    }

    /**
     * sudoPassword is null unless the UI is in admin-approval mode (see
     * settingsService.sshApprovalRequired() - the page only renders a password field at all in
     * that case). Otherwise the executor falls back to the stored sudo secret itself, same as
     * every other privileged action in this app when not in approval mode.
     */
    public DiagnosticCheck fixNetworkd(User actingAdmin, char[] sudoPassword) {
        CommandResult fixResult;
        try {
            fixResult = executor.enableNetworkd(sudoPassword);
        } finally {
            zero(sudoPassword);
        }
        auditLogService.log(actingAdmin, AuditAction.UPDATED, AuditTargetType.SYSTEM, null, "network-diagnostics",
                "enabled systemd-networkd");
        DiagnosticCheck result = checkNetworkd();
        logIfNotOk(result);
        return result.fixable() ? withLog(result, commandLog(fixResult)) : result;
    }

    /**
     * detectHostAddresses() itself is a NOPASSWD-tier read-only command, so sudoPassword (if any)
     * isn't actually threaded through to the executor here - accepted only for API consistency
     * with the other two fix endpoints.
     */
    public DiagnosticCheck fixHostAddress(User actingAdmin, char[] sudoPassword) {
        zero(sudoPassword);
        String before = settingsService.hostPublicAddress();
        String detected = detectHostAddress();
        if (detected == null) {
            DiagnosticCheck failure = new DiagnosticCheck("host-address", "HOST_PUBLIC_ADDRESS", Status.FAIL,
                    "Could not auto-detect a non-loopback address on this host - set it manually in Settings.", false);
            logIfNotOk(failure);
            return failure;
        }
        settingsService.updateHostPublicAddress(detected, actingAdmin);
        auditLogService.log(actingAdmin, AuditAction.UPDATED, AuditTargetType.SYSTEM, null, "network-diagnostics",
                "HOST_PUBLIC_ADDRESS changed from '" + before + "' to '" + detected + "'");
        DiagnosticCheck result = checkHostAddress();
        logIfNotOk(result);
        return result;
    }

    /** As {@link #fixNetworkd} - a plain apt-get install, no extra state to reconcile afterward. */
    public DiagnosticCheck fixPodman(User actingAdmin, char[] sudoPassword) {
        CommandResult fixResult;
        try {
            fixResult = executor.installPodman(sudoPassword);
        } finally {
            zero(sudoPassword);
        }
        auditLogService.log(actingAdmin, AuditAction.UPDATED, AuditTargetType.SYSTEM, null, "network-diagnostics",
                "installed podman");
        DiagnosticCheck result = checkPodman();
        logIfNotOk(result);
        return result.fixable() ? withLog(result, commandLog(fixResult)) : result;
    }

    /** As {@link #fixPodman}, for QEMU. */
    public DiagnosticCheck fixQemu(User actingAdmin, char[] sudoPassword) {
        CommandResult fixResult;
        try {
            fixResult = executor.installQemu(sudoPassword);
        } finally {
            zero(sudoPassword);
        }
        auditLogService.log(actingAdmin, AuditAction.UPDATED, AuditTargetType.SYSTEM, null, "network-diagnostics",
                "installed QEMU");
        DiagnosticCheck result = checkQemu();
        logIfNotOk(result);
        return result.fixable() ? withLog(result, commandLog(fixResult)) : result;
    }

    /** As {@link #fixPodman}. Only ever reachable when {@link #checkPodmanNetwork} already
     *  confirmed netavark is new enough (see that method's own comment for the exact 1.14
     *  threshold) - so unlike before that check knew the precise version, this is now genuinely
     *  expected to succeed; a failure here would be a real bug, not the expected common case. */
    public DiagnosticCheck fixPodmanNetwork(User actingAdmin, char[] sudoPassword) {
        CommandResult fixResult;
        try {
            fixResult = executor.configurePodmanNetwork(sudoPassword);
        } finally {
            zero(sudoPassword);
        }
        auditLogService.log(actingAdmin, AuditAction.UPDATED, AuditTargetType.SYSTEM, null, "network-diagnostics",
                "configured podman network for nspawnbr0");
        DiagnosticCheck result = checkPodmanNetwork();
        logIfNotOk(result);
        return result.fixable() ? withLog(result, commandLog(fixResult)) : result;
    }

    /** As {@link #fixPodmanNetwork}, for QEMU - a plain ACL entry, so unlike that one this is
     *  expected to actually succeed regardless of installed version. */
    public DiagnosticCheck fixQemuBridge(User actingAdmin, char[] sudoPassword) {
        CommandResult fixResult;
        try {
            fixResult = executor.configureQemuBridge(sudoPassword);
        } finally {
            zero(sudoPassword);
        }
        auditLogService.log(actingAdmin, AuditAction.UPDATED, AuditTargetType.SYSTEM, null, "network-diagnostics",
                "allow-listed nspawnbr0 in /etc/qemu/bridge.conf");
        DiagnosticCheck result = checkQemuBridge();
        logIfNotOk(result);
        return result.fixable() ? withLog(result, commandLog(fixResult)) : result;
    }

    /** Reattaches a fix command's captured output to an already-built check - used only while the
     *  check is still failing after the fix attempt (see the DiagnosticCheck record's own javadoc
     *  on {@code log}); a resolved check is returned as-is, with no log, so the admin-page JS's own
     *  "clear the log once the Fix button disappears" behavior falls out naturally. */
    private static DiagnosticCheck withLog(DiagnosticCheck check, String log) {
        return new DiagnosticCheck(check.id(), check.label(), check.status(), check.detail(), check.fixable(), log);
    }

    /** Combines stdout+stderr into one displayable blob, stderr appended after stdout (matching
     *  how a terminal naturally interleaves them close enough for a human reading it after the
     *  fact) - blank segments are dropped rather than left as empty lines. */
    private static String commandLog(CommandResult result) {
        StringBuilder log = new StringBuilder();
        if (!result.stdout().isBlank()) {
            log.append(result.stdout().stripTrailing());
        }
        if (!result.stderr().isBlank()) {
            if (log.length() > 0) {
                log.append('\n');
            }
            log.append(result.stderr().stripTrailing());
        }
        return log.toString();
    }

    private static void zero(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    private DiagnosticCheck checkNetworkd() {
        CommandResult result = executor.networkdStatus();
        boolean active = "active".equals(result.stdout().trim());
        return new DiagnosticCheck("networkd", "systemd-networkd active",
                active ? Status.OK : Status.FAIL,
                active ? "systemd-networkd is active."
                        : "systemd-networkd is not active - a container's SSH/RDP port forward will never be "
                        + "populated without it (confirmed live: the NAT map stays permanently empty).",
                !active);
    }

    /**
     * Checks every container's currently-configured custom port mapping (the only host-forwarded
     * ports left — SSH/RDP dial a container's internal veth address directly, no host forward
     * involved) against ufw's active rules. No automatic fix: unlike the old fixed SSH/RDP ranges,
     * this is a variable, per-container list, so — same posture as {@link #checkSudoers} — an
     * admin resolves it by hand (typically {@code ufw allow <port>/<proto>}).
     */
    private DiagnosticCheck checkUfw() {
        CommandResult result = executor.ufwStatus();
        String stdout = result.stdout();
        if (!stdout.contains("Status: active")) {
            return new DiagnosticCheck("ufw-ports", "ufw forwarded-port coverage", Status.OK,
                    "ufw is not active on this host - nothing to check.", false);
        }
        List<ContainerPortMapping> mappings = portMappingRepository.findAllWithContainer();
        List<String> uncovered = new ArrayList<>();
        for (ContainerPortMapping mapping : mappings) {
            if (!coversPort(stdout, mapping.getHostPort(), mapping.getProtocol())) {
                String containerName = mapping.getContainer().getName();
                uncovered.add(containerName + " (host port " + mapping.getHostPort() + "/"
                        + mapping.getProtocol().name().toLowerCase() + ")");
                log.warn("Network diagnostics: ufw has no rule covering forwarded port {}/{} for container '{}'",
                        mapping.getHostPort(), mapping.getProtocol().name().toLowerCase(), containerName);
            }
        }
        boolean covered = uncovered.isEmpty();
        return new DiagnosticCheck("ufw-ports", "ufw forwarded-port coverage",
                covered ? Status.OK : Status.FAIL,
                covered ? "ufw allows all " + mappings.size() + " currently-mapped container port(s)."
                        : "ufw is active but has no rule covering: " + String.join(", ", uncovered)
                        + " - a default-reject policy will silently block these forwards.",
                false);
    }

    private boolean coversPort(String ufwStatusOutput, int port, PortMappingProtocol protocol) {
        String token = port + "/" + protocol.name().toLowerCase();
        for (String line : ufwStatusOutput.split("\n")) {
            if (line.contains("ALLOW IN") && line.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private DiagnosticCheck checkHostAddress() {
        String address = settingsService.hostPublicAddress();
        boolean loopback = "127.0.0.1".equals(address) || "localhost".equalsIgnoreCase(address);
        return new DiagnosticCheck("host-address", "HOST_PUBLIC_ADDRESS not loopback",
                loopback ? Status.WARN : Status.OK,
                loopback ? "HOST_PUBLIC_ADDRESS is '" + address + "' - some hosts (confirmed live: systemd-networkd's "
                        + "own NAT table) refuse to hairpin loopback-destined traffic back into a container, even "
                        + "though the port forward itself is configured correctly."
                        : "HOST_PUBLIC_ADDRESS is '" + address + "', not loopback.",
                loopback);
    }

    /**
     * Containers can only resolve each other by name (see docs/administrator-guide.md's "Resolving
     * containers by name") once the shared container bridge (nspawnbr0) is actually up with its
     * expected address - postinst creates it unconditionally, so this check exists to catch the
     * rare case where that failed (or an admin removed it by hand) rather than to drive an
     * admin-triggered fix; there's nothing to offer a Fix button for here.
     */
    private DiagnosticCheck checkBridge() {
        CommandResult result = executor.checkBridge();
        boolean ok = "ok".equals(result.stdout().trim());
        return new DiagnosticCheck("bridge", "Shared container bridge (nspawnbr0)",
                ok ? Status.OK : Status.FAIL,
                ok ? "nspawnbr0 is up with address 10.100.0.1/24 - containers can resolve each other by name."
                        : "nspawnbr0 doesn't exist yet or doesn't have the expected address - containers can't "
                        + "resolve each other by name. Check that systemd-networkd is active (see the check above) "
                        + "and that /etc/systemd/network/70-nspawnmgr-bridge.netdev/.network are present; "
                        + "reinstalling the .deb re-applies them.",
                false);
    }

    /** Part of the v0.2.0 podman/QEMU backend groundwork - detects whether podman is installed at
     *  all, ahead of any actual podman-backed container support. Fixable via apt-get install. */
    private DiagnosticCheck checkPodman() {
        CommandResult result = executor.checkPodman();
        boolean ok = "ok".equals(result.stdout().trim());
        return new DiagnosticCheck("podman", "podman installed", ok ? Status.OK : Status.FAIL,
                ok ? "podman is installed on this host."
                        : "podman is not installed - needed for the podman container/VM backend (not yet available "
                        + "to use, still being built).",
                !ok);
    }

    /** As {@link #checkPodman}, but a plain boolean for callers that just need to gate a UI element
     *  (the Templates admin page's "New Pod" button - pulling/creating a podman template needs real
     *  podman commands to run on the host, so offering that button before podman is installed just
     *  leads to a confusing failure partway through) rather than render a full diagnostic row. Same
     *  single NOPASSWD check script, no separate host round-trip. */
    public boolean isPodmanInstalled() {
        return "ok".equals(executor.checkPodman().stdout().trim());
    }

    /** As {@link #isPodmanInstalled}, for QEMU - gates the container list page's "New QEMU" button. */
    public boolean isQemuInstalled() {
        return "ok".equals(executor.checkQemu().stdout().trim());
    }

    /** As {@link #checkPodman}, for QEMU. */
    private DiagnosticCheck checkQemu() {
        CommandResult result = executor.checkQemu();
        boolean ok = "ok".equals(result.stdout().trim());
        return new DiagnosticCheck("qemu", "QEMU installed", ok ? Status.OK : Status.FAIL,
                ok ? "qemu-system-x86_64 is installed on this host."
                        : "QEMU is not installed - needed for the QEMU container/VM backend (not yet available to "
                        + "use, still being built).",
                !ok);
    }

    /** Correct configuration (not just "installed") for podman means a network attached to
     *  nspawnbr0, so podman containers can eventually be reachable by name alongside
     *  systemd-nspawn ones - see {@link #fixPodmanNetwork} for the host-local-IPAM approach, which
     *  needs netavark 1.14+ for the bridge driver's own "mode=unmanaged" option (attach to an
     *  existing bridge instead of creating/managing one) - confirmed against netavark's own
     *  RELEASE_NOTES.md, PR #1090. An earlier revision of this app used netavark's DHCP IPAM driver
     *  instead (which also needs 1.14+, for a *different* reason landing in that same release -
     *  DHCP-in-unmanaged-mode support, PR #868) - abandoned after confirming live on yoga
     *  2026-08-16 that it can never work at all, on any netavark version, due to a kernel TX/RX
     *  bridge-isolation limitation (containers/netavark#1416, closed as a duplicate of #1008): a
     *  DHCP server bound to the bridge device itself (which is how this app's own
     *  70-nspawnmgr-bridge.network configures its DHCP server, for systemd-nspawn containers) is
     *  unreachable from netavark's own host-netns DHCP proxy. The 1.14 threshold below is entirely
     *  about mode=unmanaged now, unrelated to DHCP.
     *
     *  <p>With the exact threshold confirmed, the check script itself detects a too-old netavark
     *  and reports it as genuinely not fixable (mapped to WARN, not FAIL - this isn't a
     *  misconfiguration, it's an environment limitation with no fix this app can offer) rather
     *  than making the admin click Fix just to get a raw netavark error. Confirmed live (yoga,
     *  2026-08-15): Linux Mint 22.1's stock podman 4.9.3 ships netavark 1.4.0, and there's no
     *  trustworthy way to install a newer podman there either - the old Kubic/openSUSE-Build-
     *  Service repo that used to provide one for Debian/Ubuntu has been discontinued and no longer
     *  ships podman at all; the only alternatives are building from source or an individual's
     *  unofficial OBS project, neither appropriate for an automated Fix action to depend on. */
    private DiagnosticCheck checkPodmanNetwork() {
        CommandResult result = executor.checkPodmanNetwork();
        String state = result.stdout().trim();
        return switch (state) {
            case "ok" -> new DiagnosticCheck("podman-network", "podman uses nspawnbr0", Status.OK,
                    "A podman network is attached to nspawnbr0.", false);
            case "too-old" -> new DiagnosticCheck("podman-network", "podman uses nspawnbr0", Status.WARN,
                    "This host's netavark is older than the 1.14 that podman needs to attach to nspawnbr0 "
                    + "(bridge mode=unmanaged) - and there's no reliable way to install a newer podman on this "
                    + "host's own Linux distribution/release today. No fix available here; podman container "
                    + "creation will need a newer host once that's actually built.", false);
            default -> new DiagnosticCheck("podman-network", "podman uses nspawnbr0", Status.FAIL,
                    "No podman network is attached to nspawnbr0 yet - this host's podman/netavark looks new enough "
                    + "to support it (bridge mode=unmanaged) - try Fix.", true);
        };
    }

    /** As {@link #checkPodmanNetwork}, for QEMU - a plain ACL entry, no IP allocation involved, so
     *  unlike podman's own check this one has no real caveats about when the fix can/can't work. */
    private DiagnosticCheck checkQemuBridge() {
        CommandResult result = executor.checkQemuBridge();
        boolean ok = "ok".equals(result.stdout().trim());
        return new DiagnosticCheck("qemu-bridge", "QEMU allowed to use nspawnbr0", ok ? Status.OK : Status.FAIL,
                ok ? "/etc/qemu/bridge.conf allows nspawnbr0."
                        : "QEMU's own bridge-helper ACL (/etc/qemu/bridge.conf) doesn't allow nspawnbr0 yet - "
                        + "\"-netdev bridge,br=nspawnbr0\" would be refused until it does.",
                !ok);
    }

    private static final String GUACD_TEST_PROTOCOL = "ssh";

    /**
     * Live check from nspawnmgr's own process against whatever guacd-hostname/guacd-port
     * guacamole.properties currently has configured. guacamole.war runs in the SAME Tomcat
     * instance as nspawnmgr (same host, same process's network namespace), so this is exactly what
     * guacamole.war itself does on every session start.
     *
     * <p>A bare TCP connect alone isn't enough - confirmed live twice this session: once with
     * guacd-hostname resolving differently depending on which machine asked, and again with guacd
     * itself reachable and accepting connections fine while its "ssh"/"rdp" protocol plugins
     * (libguac-client-ssh.so/libguac-client-rdp.so, dlopen()'d by filename at runtime, not linked
     * at build time - a different, less certain resolution path than however guacd itself started)
     * failed to load, both reporting "Support for protocol ... is not installed". So this sends a
     * real Guacamole protocol handshake ({@code select,ssh}) and checks for a proper {@code args}
     * response, not just that something answered on the port. Only checks "ssh" - "rdp" (if used)
     * loads via the identical mechanism from the same guacd build, so a working "ssh" plugin load
     * is representative of both. No automatic fix: too many possible root causes (guacd not
     * running, wrong hostname/port, a bind-address mismatch, a firewall rule, a plugin-loading
     * failure) for one canned action - same posture as {@link #checkSudoers}.
     */
    private DiagnosticCheck checkGuacd() {
        Map<String, String> props = settingsService.readGuacamoleProperties();
        String hostname = props.getOrDefault("guacd-hostname", "").trim();
        String portText = props.getOrDefault("guacd-port", "").trim();
        if (hostname.isEmpty() || portText.isEmpty()) {
            return new DiagnosticCheck("guacd-connectivity", "guacd reachable from Guacamole", Status.WARN,
                    "guacd-hostname/guacd-port aren't configured yet in guacamole.properties.", false);
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return new DiagnosticCheck("guacd-connectivity", "guacd reachable from Guacamole", Status.FAIL,
                    "guacd-port ('" + portText + "') is not a valid number.", false);
        }
        String failure = trySelectProtocol(hostname, port, GUACD_TEST_PROTOCOL);
        return new DiagnosticCheck("guacd-connectivity", "guacd reachable from Guacamole",
                failure == null ? Status.OK : Status.FAIL,
                failure == null ? "Connected to guacd at " + hostname + ":" + port
                        + " and it loaded the \"" + GUACD_TEST_PROTOCOL + "\" protocol plugin successfully."
                        : "guacd at " + hostname + ":" + port + " - " + failure + ". Since guacamole.war runs in the "
                        + "same Tomcat instance as nspawnmgr, it would hit this same failure. Check that guacd is "
                        + "actually running ('systemctl status guacd'), that guacd-hostname/guacd-port here match "
                        + "what it's actually bound to ('ss -tlnp | grep guacd'), and - if it connected but the "
                        + "protocol load itself failed - guacd's own log for the dlopen() error "
                        + "('journalctl -u guacd').",
                false);
    }

    /**
     * Sends a raw {@code select,<protocol>} Guacamole protocol instruction and checks for a
     * successful {@code args} response - the same handshake guacamole.war's own tunnel opens on
     * every session start. Returns {@code null} on success, or a human-readable failure reason.
     */
    private String trySelectProtocol(String host, int port, String protocol) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(3000);
            String select = "6.select," + protocol.length() + "." + protocol + ";";
            socket.getOutputStream().write(select.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[256];
            int read = socket.getInputStream().read(buffer);
            if (read <= 0) {
                return "connected, but the connection closed with no response to selecting \"" + protocol + "\"";
            }
            String response = new String(buffer, 0, read, StandardCharsets.US_ASCII);
            if (response.startsWith("4.args,")) {
                return null;
            }
            return "connected, but selecting \"" + protocol + "\" got an unexpected response instead of the usual "
                    + "parameter list: " + response;
        } catch (IOException e) {
            return "could not connect (" + e.getMessage() + ")";
        }
    }

    private DiagnosticCheck checkSudoers() {
        CommandResult result = executor.visudoCheck();
        return new DiagnosticCheck("sudoers", "sudoers file validity", result.success() ? Status.OK : Status.FAIL,
                result.success() ? "/etc/sudoers.d/nspawnmgr_exec parses cleanly."
                        : "visudo -cf failed: " + result.stderr()
                        + " - check for a colliding Cmnd_Alias name in another file under /etc/sudoers.d/ "
                        + "(no safe automatic fix; this needs to be resolved by hand).",
                false);
    }

    /** Mirrors setup-sudo-account.sh's own `ip -4 -o addr show scope global | awk ...` detection. */
    private String detectHostAddress() {
        CommandResult result = executor.detectHostAddresses();
        if (!result.success()) {
            return null;
        }
        for (String line : result.stdout().split("\n")) {
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length < 2) {
                continue;
            }
            String iface = tokens[1];
            if (isExcludedInterface(iface)) {
                continue;
            }
            for (int i = 0; i < tokens.length - 1; i++) {
                if ("inet".equals(tokens[i])) {
                    String addr = tokens[i + 1];
                    int slash = addr.indexOf('/');
                    return slash >= 0 ? addr.substring(0, slash) : addr;
                }
            }
        }
        return null;
    }

    private boolean isExcludedInterface(String iface) {
        for (String prefix : EXCLUDED_INTERFACE_PREFIXES) {
            if (iface.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
