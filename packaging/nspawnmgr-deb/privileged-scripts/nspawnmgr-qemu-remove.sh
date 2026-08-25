#!/bin/sh
# Removes VM $1 entirely: stops its unit if active, deletes the unit file, its qcow2 disk, and its
# monitor socket. NOPASSWD - an owner removing their own VM by a fixed name is a fixed-shape
# lifecycle operation, matching podman rm/machinectl remove both being NOPASSWD despite VM/container
# *creation* needing a password (see project_qemu_lifecycle_design_corrections memory).
# $1 = VM name.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-qemu-remove.sh must be run as root." >&2
    exit 1
fi

name="$1"
unit="nspawnmgr-qemu-$name.service"

systemctl stop "$unit" 2>/dev/null || true
systemctl disable "$unit" 2>/dev/null || true
rm -f "/etc/systemd/system/$unit"
systemctl daemon-reload

rm -f "/var/lib/nspawnmgr/qemu-disks/$name.qcow2"
rm -f "/var/lib/nspawnmgr/qemu-sockets/$name.monitor.sock"
