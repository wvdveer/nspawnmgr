#!/bin/sh
# Packs a machine's current rootfs into a machinectl import-tar-compatible gzipped tar - backs the
# container detail page's "Create template from this machine" button. The caller is responsible
# for only invoking this while the machine is STOPPED (packing a live rootfs risks an inconsistent
# archive as files change mid-tar) - this script itself doesn't check.
#
# $1 = machine's rootfs dir (host-visible path, e.g. /var/lib/machines/<name>)
# $2 = target .tar.gz path (must not already exist) - a machinectl import-tar-compatible gzipped
# tar, the same convention every "Set up X-minimal" bake script already produces.
set -e
ROOTFS="$1"
TARGET="$2"

if [ -e "$TARGET" ]; then
    echo "Target file already exists" >&2
    exit 1
fi
if [ ! -d "$ROOTFS" ]; then
    echo "No rootfs found at $ROOTFS" >&2
    exit 1
fi

mkdir -p "$(dirname "$TARGET")"
tar -czf "$TARGET" --numeric-owner -C "$ROOTFS" .
