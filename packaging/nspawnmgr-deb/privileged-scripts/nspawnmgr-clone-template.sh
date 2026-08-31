#!/bin/sh
# Clones a container template (a gzipped tar, from nspawnmgr-pack-machine-as-template.sh) into a
# fresh machine via plain tar extraction. $1 = source .tar.gz path, $2 = destination machine
# directory (NSPAWN_MACHINES_DIR/<name>).
# Requires a sudo password (see ContainerFilesystemProvisioner.cloneTemplate) — creation-time only.
#
# Deliberately NOT `machinectl import-tar` - confirmed live on Fedora 43/systemd 258 (real QEMU
# host, packaging/nspawnmgr-rpm/ install verification): systemd-importd itself fails ("Failed to
# run event loop: Transport endpoint is not connected", then on retry "Transfer process failed
# with exit code 1" right after a btrfs quota-hierarchy ioctl warning it claims to just be
# "ignoring") independent of anything nspawnmgr does - reproduces identically via `importctl -m
# import-tar`, the tool machinectl's own deprecation notice points at as the replacement. A plain
# extraction has no systemd-importd/D-Bus dependency at all, and machinectl still recognizes the
# result as an ordinary directory-backed image afterward (confirmed live:
# `machinectl list-images` lists it same as import-tar's own output would). Same fix already
# applied to nspawnmgr-bootstrap-app-machine.sh's own install-time clone of debian-minimal - this
# is the shared script both that script and this live "create container from template" feature
# used to both hit the same way, extending the fix here too.
set -e
if [ -e "$2" ]; then
    echo "Machine directory already exists" >&2
    exit 1
fi
mkdir -p "$2"
tar -xpzf "$1" -C "$2"
# On an SELinux-enforcing host (Fedora/RHEL), a plain tar extraction leaves every file labeled
# with whatever type its parent directory's own default happens to be (generic var_lib_t here),
# NOT the systemd_machined_var_lib_t type policy actually expects under /var/lib/machines/<name> -
# machinectl import-tar would have set this correctly itself via systemd-importd, but the plain
# tar extraction above (see this file's own comment on why import-tar can't be used) has no
# SELinux awareness at all. Confirmed live (real Fedora 43 host, Enforcing mode,
# packaging/nspawnmgr-rpm/ verification): without this, `systemd-nspawn` fails outright with
# "Failed to register machine: Access denied" (a real AVC: `avc: denied { write } ... tcontext=
# ...var_lib_t ... permissive=0`) the moment the cloned machine tries to start. No-op (and
# harmless) on non-SELinux hosts (Debian/Arch) via the command -v guard.
if command -v restorecon >/dev/null 2>&1; then
    restorecon -R "$2"
fi
