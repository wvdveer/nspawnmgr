#!/bin/sh
# Clones a container template (a gzipped tar, machinectl import-tar-compatible) into a fresh
# machine via `machinectl import-tar`. $1 = source .tar.gz path, $2 = destination machine
# directory (NSPAWN_MACHINES_DIR/<name> - machinectl import-tar itself always targets the fixed
# /var/lib/machines, so only $2's basename is actually used below, but $2 stays a full path so
# Java remains the single source of truth for NSPAWN_MACHINES_DIR, same as before).
# Requires a sudo password (see ContainerFilesystemProvisioner.cloneTemplate) — creation-time only.
set -e
if [ -e "$2" ]; then
    echo "Machine directory already exists" >&2
    exit 1
fi
mkdir -p "$(dirname "$2")"
# No --read-only: confirmed live, it's a bare boolean flag (no "=no"/"=yes" form - "machinectl:
# option '--read-only' doesn't allow an argument") and its mere presence, regardless of any
# attempted value, marks the image read-only. Omitting it entirely gives the writable default we
# want (containers need a writable rootfs to boot).
machinectl import-tar "$1" "$(basename "$2")"
