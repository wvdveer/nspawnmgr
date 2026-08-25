package com.nspawnmgr.cli;

/**
 * Host-level introspection/fix commands for the network-diagnostics admin page — mirrors
 * {@link ContainerOutboundAccessManager}'s Real/Fake split. Each check method is a single
 * fixed-shape, read-only command (NOPASSWD-sudoers-gated); each fix method changes host state
 * (systemd-networkd, ufw) and requires a fresh sudo password per call, same tier as
 * creation-time-only operations elsewhere in this codebase.
 */
public interface NetworkDiagnosticsExecutor {

    CommandResult networkdStatus();

    CommandResult ufwStatus();

    CommandResult visudoCheck();

    /** {@code ip -4 -o addr show scope global} - same command setup-sudo-account.sh's own
     * auto-detection already runs, just surfaced here for the diagnostics page to re-check. */
    CommandResult detectHostAddresses();

    /** @return the raw command result (success or not) - never throws for a normal command
     *  failure (e.g. systemd-networkd not installed), only for a genuine SSH-level problem, so the
     *  caller can show what actually happened rather than just a generic error. */
    CommandResult enableNetworkd(char[] sudoPassword);

    /** One of "ok"/"missing" - see nspawnmgr-diag-check-bridge.sh. No matching fix method: the
     *  shared container bridge is created unconditionally by postinst, not admin-triggered. */
    CommandResult checkBridge();

    /** One of "ok"/"missing" - see nspawnmgr-diag-check-podman.sh. Part of the v0.2.0 podman/QEMU
     *  backend groundwork: detects whether podman is installed on this host at all, ahead of any
     *  actual podman-backed container support. */
    CommandResult checkPodman();

    /** As {@link #checkPodman}, for QEMU - see nspawnmgr-diag-check-qemu.sh. */
    CommandResult checkQemu();

    /** Installs podman via the host's own package manager - see nspawnmgr-install-podman.sh.
     *  A real package install, not a config flip, so callers should budget a much longer timeout
     *  than the other fix methods here. As {@link #enableNetworkd}, never throws for a normal
     *  command failure (e.g. apt-get itself failing) - only for a genuine SSH-level problem. */
    CommandResult installPodman(char[] sudoPassword);

    /** As {@link #installPodman}, for QEMU - see nspawnmgr-install-qemu.sh. */
    CommandResult installQemu(char[] sudoPassword);

    /** One of "ok"/"missing" - see nspawnmgr-diag-check-podman-network.sh. Detects whether a
     *  podman network is already attached to nspawnbr0 (podman's own bridge driver "unmanaged
     *  mode", the prerequisite for podman containers to eventually be reachable by name alongside
     *  systemd-nspawn ones on the same bridge). */
    CommandResult checkPodmanNetwork();

    /** Attaches a podman network to nspawnbr0 with netavark's host-local IPAM (its own local
     *  address pool, restricted via --ip-range to 10.100.0.192-10.100.0.254 - the exact complement
     *  of the DHCP pool 70-nspawnmgr-bridge.network's own [DHCPServer] reserves for systemd-nspawn
     *  containers, so the two allocators can't collide). DHCP-based IPAM was tried first and
     *  abandoned - confirmed live on yoga 2026-08-16 that it can never work at all (any netavark
     *  version) due to a kernel TX/RX bridge-isolation limitation (containers/netavark#1416,
     *  duplicate of #1008): a DHCP server bound to the bridge device itself, as
     *  70-nspawnmgr-bridge.network configures, is unreachable from netavark's own host-netns DHCP
     *  proxy. Still needs netavark 1.14+ for the bridge driver's own mode=unmanaged option, well
     *  beyond Debian/Ubuntu's own stock podman package, so this is expected to fail cleanly with a
     *  clear error on most hosts today rather than actually succeed - see
     *  nspawnmgr-configure-podman-network.sh. */
    CommandResult configurePodmanNetwork(char[] sudoPassword);

    /** One of "ok"/"missing" - see nspawnmgr-diag-check-qemu-bridge.sh. Detects whether QEMU's own
     *  bridge-helper ACL (/etc/qemu/bridge.conf) allows nspawnbr0 - the prerequisite for
     *  {@code -netdev bridge,br=nspawnbr0} to work at all. Unlike {@link #checkPodmanNetwork},
     *  this one IS safely auto-fixable: it's a plain ACL entry, no IP allocation involved, so
     *  there's no collision risk with nspawnbr0's own DHCP server to worry about. */
    CommandResult checkQemuBridge();

    /** Allow-lists nspawnbr0 in /etc/qemu/bridge.conf - see nspawnmgr-configure-qemu-bridge.sh. */
    CommandResult configureQemuBridge(char[] sudoPassword);
}
