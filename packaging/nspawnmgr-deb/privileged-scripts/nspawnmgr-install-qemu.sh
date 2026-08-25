#!/bin/sh
# As nspawnmgr-install-podman.sh, for QEMU - unlike podman, package name AND binary location
# genuinely differ by distro family here, not just the package manager command:
#   - Debian/Ubuntu (apt-get): qemu-system-x86 (provides /usr/bin/qemu-system-x86_64) + qemu-utils
#     (qemu-img).
#   - Fedora/RHEL (dnf): qemu-kvm - confirmed via research (not yet verified against a real Fedora
#     host live): installs its binary at /usr/libexec/qemu-kvm, NOT /usr/bin/qemu-system-x86_64 -
#     see nspawnmgr-diag-check-qemu.sh/nspawnmgr-diag-check-qemu-bridge.sh for how the checks
#     account for this path difference.
#   - Arch (pacman): qemu-system-x86 - same package name as Debian's, and (unlike Fedora/RHEL)
#     also provides /usr/bin/qemu-system-x86_64 directly, no path quirk to work around.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-install-qemu.sh must be run as root." >&2
    exit 1
fi

if command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y qemu-system-x86 qemu-utils
elif command -v dnf >/dev/null 2>&1; then
    dnf install -y qemu-kvm
elif command -v pacman >/dev/null 2>&1; then
    pacman -S --noconfirm qemu-system-x86
else
    echo "No supported package manager found on this host (looked for apt-get, dnf, pacman)." >&2
    exit 1
fi
