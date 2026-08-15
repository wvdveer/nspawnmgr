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

    void enableNetworkd(char[] sudoPassword);

    /** One of "ok"/"missing" - see nspawnmgr-diag-check-bridge.sh. No matching fix method: the
     *  shared container bridge is created unconditionally by postinst, not admin-triggered. */
    CommandResult checkBridge();
}
