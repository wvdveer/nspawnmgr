package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.NetworkDiagnosticsExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stands in for host network introspection on dev machines. Tracks simple in-memory state so the
 * dev stack can exercise the full check -> fail -> fix loop the real page offers, without touching
 * any actual host service/firewall. Starts with systemd-networkd "inactive" and ufw reporting no
 * rules at all, so both fail on first load, matching what a freshly-installed real host looks like
 * before anyone's run the fixes - the same state this session spent hours diagnosing.
 */
@Component
@Profile("dev")
public class FakeNetworkDiagnosticsExecutor implements NetworkDiagnosticsExecutor {

    private volatile boolean networkdActive = false;
    // Both start "not installed" - matches a freshly-installed real host, same reasoning as
    // networkdActive above, and lets the dev stack exercise the v0.2.0 podman/QEMU groundwork's
    // full check -> fail -> fix loop too.
    private volatile boolean podmanInstalled = false;
    private volatile boolean qemuInstalled = false;
    private volatile boolean qemuBridgeConfigured = false;
    private volatile boolean podmanNetworkConfigured = false;

    @Override
    public CommandResult networkdStatus() {
        return new CommandResult(networkdActive ? 0 : 3, networkdActive ? "active\n" : "inactive\n", "");
    }

    @Override
    public CommandResult ufwStatus() {
        return new CommandResult(0, "Status: active\n\nTo                         Action      From\n--                         ------      ----\n", "");
    }

    @Override
    public CommandResult visudoCheck() {
        return new CommandResult(0, "", "");
    }

    @Override
    public CommandResult detectHostAddresses() {
        return new CommandResult(0,
                "2: eth0    inet 192.168.1.50/24 brd 192.168.1.255 scope global eth0\\       valid_lft forever preferred_lft forever\n",
                "");
    }

    @Override
    public CommandResult enableNetworkd(char[] sudoPassword) {
        networkdActive = true;
        return new CommandResult(0, "Created symlink /etc/systemd/system/multi-user.target.wants/systemd-networkd.service.\n", "");
    }

    // Always "ok" - the shared bridge is created unconditionally by postinst now (nothing
    // admin-triggered left to exercise a fail -> fix loop for), and dev mode has no real host
    // networking to check against anyway.
    @Override
    public CommandResult checkBridge() {
        return new CommandResult(0, "ok\n", "");
    }

    @Override
    public CommandResult checkPodman() {
        return new CommandResult(0, podmanInstalled ? "ok\n" : "missing\n", "");
    }

    @Override
    public CommandResult checkQemu() {
        return new CommandResult(0, qemuInstalled ? "ok\n" : "missing\n", "");
    }

    @Override
    public CommandResult installPodman(char[] sudoPassword) {
        podmanInstalled = true;
        return new CommandResult(0, "Reading package lists...\nBuilding dependency tree...\nSetting up podman...\n", "");
    }

    @Override
    public CommandResult installQemu(char[] sudoPassword) {
        qemuInstalled = true;
        return new CommandResult(0, "Reading package lists...\nBuilding dependency tree...\nSetting up qemu-system-x86...\nSetting up qemu-utils...\n", "");
    }

    @Override
    public CommandResult checkPodmanNetwork() {
        return new CommandResult(0, podmanNetworkConfigured ? "ok\n" : "missing\n", "");
    }

    @Override
    public CommandResult configurePodmanNetwork(char[] sudoPassword) {
        podmanNetworkConfigured = true;
        return new CommandResult(0, "nspawnbr0\n", "");
    }

    @Override
    public CommandResult checkQemuBridge() {
        return new CommandResult(0, qemuBridgeConfigured ? "ok\n" : "missing\n", "");
    }

    @Override
    public CommandResult configureQemuBridge(char[] sudoPassword) {
        qemuBridgeConfigured = true;
        return new CommandResult(0, "/etc/qemu/bridge.conf now contains:\nallow nspawnbr0\n", "");
    }
}
