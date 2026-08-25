#!/bin/sh
# Allow-lists nspawnbr0 in QEMU's own bridge-helper ACL - the fix behind the Diagnostics page's
# "QEMU allowed to use nspawnbr0" check (see NetworkDiagnosticsExecutor.configureQemuBridge).
# Safe to run repeatedly - appends the allow line only if an equivalent one isn't already there.
# Prints the resulting file so the Diagnostics page's own fix-log display shows something
# meaningful, not just silence on success.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-configure-qemu-bridge.sh must be run as root." >&2
    exit 1
fi

mkdir -p /etc/qemu
touch /etc/qemu/bridge.conf
if ! grep -qE '^allow[[:space:]]+(nspawnbr0|all)[[:space:]]*$' /etc/qemu/bridge.conf; then
    echo "allow nspawnbr0" >> /etc/qemu/bridge.conf
fi

echo "/etc/qemu/bridge.conf now contains:"
cat /etc/qemu/bridge.conf
