#!/bin/sh
# As nspawnmgr-diag-check-podman.sh, for QEMU (see NetworkDiagnosticsExecutor.checkQemu). Checks
# qemu-system-x86_64 specifically, not just any qemu-* binary - that's the actual VM launcher this
# app would eventually shell out to, not e.g. qemu-img alone. Also checks Fedora/RHEL's own
# non-PATH location (/usr/libexec/qemu-kvm, from the qemu-kvm package - confirmed via research,
# not yet verified live against a real Fedora/RHEL host) - see nspawnmgr-install-qemu.sh's own
# comment for why that distro family's package doesn't put a qemu-system-x86_64 on PATH at all.
# Prints exactly one of:
#   ok       - qemu-system-x86_64 is on PATH, or Fedora/RHEL's own /usr/libexec/qemu-kvm exists.
#   missing  - neither is present - see nspawnmgr-install-qemu.sh for the matching fix.
set -e
if command -v qemu-system-x86_64 >/dev/null 2>&1 || [ -x /usr/libexec/qemu-kvm ]; then
    echo "ok"
else
    echo "missing"
fi
