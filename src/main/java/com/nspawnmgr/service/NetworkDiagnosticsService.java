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

    // nspawnbr0 (the shared container bridge - see docs/administrator-guide.md's "Resolving
    // containers by name") is a real bug fix, not a defensive addition: confirmed live, its own
    // address (10.100.0.1) doesn't start with any of the other prefixes here ("br-" doesn't match
    // a literal "nspawnbr0"), so it was never actually excluded - detectHostAddress() could pick
    // it as "the" host address on a host where nspawnbr0 happens to enumerate before the real
    // external NIC in `ip addr show`'s own ordering (not guaranteed to always lose that race).
    // That's just as broken as the literal-loopback case this whole feature exists to catch - an
    // address reachable only from inside the container bridge's own network, not from a real
    // client outside the host - but checkHostAddress()'s own OK/loopback check has no way to catch
    // it, since 10.100.0.1 isn't textually "127.0.0.1"/"localhost" either.
    private static final String[] EXCLUDED_INTERFACE_PREFIXES =
            {"tun", "tap", "wg", "ppp", "docker", "veth", "ve-", "br-", "nspawnbr"};

    private final NetworkDiagnosticsExecutor executor;
    private final SettingsService settingsService;
    private final AuditLogService auditLogService;
    private final ContainerPortMappingRepository portMappingRepository;
    private final UserMessages messages;

    public NetworkDiagnosticsService(NetworkDiagnosticsExecutor executor, SettingsService settingsService,
                                      AuditLogService auditLogService, ContainerPortMappingRepository portMappingRepository,
                                      UserMessages messages) {
        this.executor = executor;
        this.settingsService = settingsService;
        this.auditLogService = auditLogService;
        this.portMappingRepository = portMappingRepository;
        this.messages = messages;
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
                    messages.get("diag.hostAddress.couldNotAutoDetect"), false);
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
        return new DiagnosticCheck("networkd", messages.get("diag.networkd.label"),
                active ? Status.OK : Status.FAIL,
                active ? messages.get("diag.networkd.ok") : messages.get("diag.networkd.fail"),
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
            return new DiagnosticCheck("ufw-ports", messages.get("diag.ufw.label"), Status.OK,
                    messages.get("diag.ufw.inactive"), false);
        }
        List<ContainerPortMapping> mappings = portMappingRepository.findAllWithContainer();
        List<String> uncovered = new ArrayList<>();
        for (ContainerPortMapping mapping : mappings) {
            if (!coversPort(stdout, mapping.getHostPort(), mapping.getProtocol())) {
                String containerName = mapping.getContainer().getName();
                uncovered.add(messages.get("diag.ufw.uncoveredEntry", containerName, mapping.getHostPort(),
                        mapping.getProtocol().name().toLowerCase()));
                log.warn("Network diagnostics: ufw has no rule covering forwarded port {}/{} for container '{}'",
                        mapping.getHostPort(), mapping.getProtocol().name().toLowerCase(), containerName);
            }
        }
        boolean covered = uncovered.isEmpty();
        return new DiagnosticCheck("ufw-ports", messages.get("diag.ufw.label"),
                covered ? Status.OK : Status.FAIL,
                covered ? messages.get("diag.ufw.ok", mappings.size())
                        : messages.get("diag.ufw.fail", String.join(", ", uncovered)),
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

    // nspawnbr0's own fixed bridge address (see docs/administrator-guide.md's "Resolving
    // containers by name") - only reachable from inside the container bridge's own network, not
    // from a real client outside the host, so it's just as broken as a literal loopback address
    // for this check's purposes even though it isn't textually "127.0.0.1"/"localhost". Confirmed
    // live: detectHostAddress()'s own EXCLUDED_INTERFACE_PREFIXES gap (fixed above) let this get
    // auto-detected and stored as HOST_PUBLIC_ADDRESS on a real host - this check needs to flag an
    // already-affected install too, not just prevent new ones once the detection bug is fixed.
    private static final String BRIDGE_ADDRESS = "10.100.0.1";

    private DiagnosticCheck checkHostAddress() {
        String address = settingsService.hostPublicAddress();
        boolean loopback = isLoopbackAddress(address);
        boolean bridgeInternal = BRIDGE_ADDRESS.equals(address);
        boolean broken = loopback || bridgeInternal;
        String detail;
        if (loopback) {
            detail = messages.get("diag.hostAddress.loopback", address);
        } else if (bridgeInternal) {
            detail = messages.get("diag.hostAddress.bridgeInternal", address);
        } else {
            detail = messages.get("diag.hostAddress.ok", address);
        }
        return new DiagnosticCheck("host-address", messages.get("diag.hostAddress.label"),
                broken ? Status.WARN : Status.OK, detail, broken);
    }

    /** Any address in 127.0.0.0/8, not just the literal "127.0.0.1" - confirmed live this matters:
     *  Debian's own convention maps a host's short hostname to 127.0.1.1 (not 127.0.0.1) in
     *  /etc/hosts, and that address suffers the exact same NAT-hairpin problem this check exists
     *  to catch. Parses the dotted-decimal form directly rather than via InetAddress.getByName()
     *  (which would trigger a real DNS lookup - and block this synchronous check on network I/O -
     *  for anything that isn't already a literal IP), so a genuine hostname or malformed value
     *  just falls through to "not loopback" instead of being resolved. */
    private static boolean isLoopbackAddress(String address) {
        if ("localhost".equalsIgnoreCase(address)) {
            return true;
        }
        String[] octets = address.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            return Integer.parseInt(octets[0]) == 127;
        } catch (NumberFormatException e) {
            return false;
        }
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
        return new DiagnosticCheck("bridge", messages.get("diag.bridge.label"),
                ok ? Status.OK : Status.FAIL,
                ok ? messages.get("diag.bridge.ok") : messages.get("diag.bridge.fail"),
                false);
    }

    /** Part of the v0.2.0 podman/QEMU backend groundwork - detects whether podman is installed at
     *  all, ahead of any actual podman-backed container support. Fixable via apt-get install. */
    private DiagnosticCheck checkPodman() {
        CommandResult result = executor.checkPodman();
        boolean ok = "ok".equals(result.stdout().trim());
        return new DiagnosticCheck("podman", messages.get("diag.podman.label"), ok ? Status.OK : Status.FAIL,
                ok ? messages.get("diag.podman.ok") : messages.get("diag.podman.fail"),
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
        return new DiagnosticCheck("qemu", messages.get("diag.qemu.label"), ok ? Status.OK : Status.FAIL,
                ok ? messages.get("diag.qemu.ok") : messages.get("diag.qemu.fail"),
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
        String label = messages.get("diag.podmanNetwork.label");
        return switch (state) {
            case "ok" -> new DiagnosticCheck("podman-network", label, Status.OK,
                    messages.get("diag.podmanNetwork.ok"), false);
            case "too-old" -> new DiagnosticCheck("podman-network", label, Status.WARN,
                    messages.get("diag.podmanNetwork.tooOld"), false);
            default -> new DiagnosticCheck("podman-network", label, Status.FAIL,
                    messages.get("diag.podmanNetwork.fail"), true);
        };
    }

    /** As {@link #checkPodmanNetwork}, for QEMU - a plain ACL entry, no IP allocation involved, so
     *  unlike podman's own check this one has no real caveats about when the fix can/can't work. */
    private DiagnosticCheck checkQemuBridge() {
        CommandResult result = executor.checkQemuBridge();
        boolean ok = "ok".equals(result.stdout().trim());
        return new DiagnosticCheck("qemu-bridge", messages.get("diag.qemuBridge.label"), ok ? Status.OK : Status.FAIL,
                ok ? messages.get("diag.qemuBridge.ok") : messages.get("diag.qemuBridge.fail"),
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
        String label = messages.get("diag.guacd.label");
        if (hostname.isEmpty() || portText.isEmpty()) {
            return new DiagnosticCheck("guacd-connectivity", label, Status.WARN,
                    messages.get("diag.guacd.notConfigured"), false);
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return new DiagnosticCheck("guacd-connectivity", label, Status.FAIL,
                    messages.get("diag.guacd.invalidPort", portText), false);
        }
        String failure = trySelectProtocol(hostname, port, GUACD_TEST_PROTOCOL);
        return new DiagnosticCheck("guacd-connectivity", label,
                failure == null ? Status.OK : Status.FAIL,
                failure == null ? messages.get("diag.guacd.ok", hostname, port, GUACD_TEST_PROTOCOL)
                        : messages.get("diag.guacd.fail", hostname, port, failure),
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
                return messages.get("diag.guacd.protocolClosedNoResponse", protocol);
            }
            String response = new String(buffer, 0, read, StandardCharsets.US_ASCII);
            if (response.startsWith("4.args,")) {
                return null;
            }
            return messages.get("diag.guacd.protocolUnexpectedResponse", protocol, response);
        } catch (IOException e) {
            return messages.get("diag.guacd.couldNotConnect", e.getMessage());
        }
    }

    private DiagnosticCheck checkSudoers() {
        CommandResult result = executor.visudoCheck();
        return new DiagnosticCheck("sudoers", messages.get("diag.sudoers.label"), result.success() ? Status.OK : Status.FAIL,
                result.success() ? messages.get("diag.sudoers.ok") : messages.get("diag.sudoers.fail", result.stderr()),
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
