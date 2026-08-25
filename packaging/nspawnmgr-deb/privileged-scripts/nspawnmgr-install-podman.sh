#!/bin/sh
# Installs podman via whatever package manager this HOST itself actually uses - the fix action
# behind the Diagnostics page's podman check (see NetworkDiagnosticsExecutor.installPodman). Host
# package name is "podman" across all three families, so only the manager/command differs here -
# unlike QEMU's own install script, no per-distro binary path quirks to work around.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-install-podman.sh must be run as root." >&2
    exit 1
fi

if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y podman
elif command -v dnf >/dev/null 2>&1; then
    dnf install -y podman
elif command -v pacman >/dev/null 2>&1; then
    pacman -S --noconfirm podman
else
    echo "No supported package manager found on this host (looked for apt-get, dnf, pacman)." >&2
    exit 1
fi
