package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerOutboundAccessManager;
import com.nspawnmgr.domain.ContainerOutboundAllowlistEntry;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserMessages;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a dedicated "NSPAWNMGR-OUTBOUND" iptables chain, jumped to from the top of FORWARD, that
 * holds per-container rules for outbound internet access: an ACCEPT rule for each outbound
 * allowlist entry, followed by a catch-all DROP rule if the container's outboundEnabled is false.
 * (If outboundEnabled is true, neither is needed - nothing to block.)
 *
 * <p>Every sync() flushes and rebuilds this container's rules from scratch, rather than diffing
 * against whatever's already there - simpler to reason about and self-healing if a veth name gets
 * reused between container restarts, at the cost of a few extra round trips (fine: these are
 * infrequent admin operations per SshRemoteExecutor's own docs, not a hot path).
 *
 * <p>Blocking is keyed on the container's actual host-side veth interface, discovered at
 * apply-time via the veth's peer ifindex rather than assumed from the container name -
 * systemd-nspawn doesn't name the host-side interface predictably (see docs/administrator-guide.md's
 * Container networking section, and {@code project_veth_naming_unpredictable} in memory). <b>This
 * discovery sequence, and the rule flush/rebuild logic below, have not yet been verified against a
 * real nspawn host</b> - confirm both there before relying on this in production.
 *
 * <p>Called from every container start and outbound-setting toggle — always NOPASSWD, never gated
 * by approval mode. Shells out to fixed wrapper scripts under
 * {@code NspawnProperties.privilegedScriptsDir()} (shipped by the .deb, see
 * packaging/nspawnmgr-deb/privileged-scripts/) rather than inlining scripts, so the sudoers grant
 * can match on an exact path instead of wildcard-matching script text.
 */
@Component
@Profile("!dev")
public class RealContainerOutboundAccessManager implements ContainerOutboundAccessManager {

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;
    private final UserMessages messages;

    public RealContainerOutboundAccessManager(SettingsService settingsService, SshRemoteExecutor ssh, UserMessages messages) {
        this.settingsService = settingsService;
        this.ssh = ssh;
        this.messages = messages;
    }

    @Override
    public void sync(String machineName, boolean outboundEnabled, List<ContainerOutboundAllowlistEntry> allowlist) {
        ensureChain();
        String veth = discoverVeth(machineName);
        flushRulesFor(veth);
        if (!outboundEnabled) {
            for (ContainerOutboundAllowlistEntry entry : allowlist) {
                addAcceptRule(veth, entry);
            }
            addDropRule(veth);
        }
    }

    private void ensureChain() {
        runWrapper(Duration.ofSeconds(10), "nspawnmgr-outbound-ensure-chain.sh");
    }

    private String discoverVeth(String machineName) {
        CommandResult result = runWrapperRaw(Duration.ofSeconds(15), "nspawnmgr-outbound-discover-veth.sh", machineName);
        String veth = result.stdout().trim();
        if (!result.success() || veth.isEmpty()) {
            throw new ContainerCliException(messages.get("error.cli.failedToDiscoverVeth", machineName, result.stderr()));
        }
        return veth;
    }

    /** Deletes every existing rule in the chain whose input interface is this veth, in one pass. */
    private void flushRulesFor(String veth) {
        runWrapper(Duration.ofSeconds(15), "nspawnmgr-outbound-flush-rules.sh", veth);
    }

    private void addAcceptRule(String veth, ContainerOutboundAllowlistEntry entry) {
        runWrapper(Duration.ofSeconds(10), "nspawnmgr-outbound-add-accept-rule.sh",
                veth, entry.getDestinationHost(), entry.getProtocol().name().toLowerCase(),
                String.valueOf(entry.getDestinationPort()));
    }

    private void addDropRule(String veth) {
        runWrapper(Duration.ofSeconds(10), "nspawnmgr-outbound-add-drop-rule.sh", veth);
    }

    private void runWrapper(Duration timeout, String scriptName, String... args) {
        CommandResult result = runWrapperRaw(timeout, scriptName, args);
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.firewallCommandFailed", scriptName, result.stderr()));
        }
    }

    private CommandResult runWrapperRaw(Duration timeout, String scriptName, String... args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(settingsService.nspawnPrivilegedScriptsDir(), scriptName).toString());
        command.addAll(List.of(args));
        return ssh.execNoPasswordSudo(timeout, command);
    }
}
