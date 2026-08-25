#!/bin/sh
# Reports whether QEMU's own bridge-helper ACL (/etc/qemu/bridge.conf, consulted by
# qemu-bridge-helper - see https://wiki.qemu.org/Features/HelperNetworking) allows nspawnbr0 - the
# prerequisite for `-netdev bridge,br=nspawnbr0` to work at all, regardless of whether QEMU itself
# is eventually invoked as root or unprivileged. See NetworkDiagnosticsExecutor.checkQemuBridge.
# Same Fedora/RHEL /usr/libexec/qemu-kvm fallback as nspawnmgr-diag-check-qemu.sh - see that
# script's own comment.
# Prints exactly one of:
#   ok       - /etc/qemu/bridge.conf allows nspawnbr0 (or all bridges).
#   missing  - QEMU isn't installed, or the allow-list doesn't cover nspawnbr0 yet.
set -e
if ! command -v qemu-system-x86_64 >/dev/null 2>&1 && [ ! -x /usr/libexec/qemu-kvm ]; then
    echo "missing"
    exit 0
fi
if [ -f /etc/qemu/bridge.conf ] && grep -qE '^allow[[:space:]]+(nspawnbr0|all)[[:space:]]*$' /etc/qemu/bridge.conf; then
    echo "ok"
else
    echo "missing"
fi
