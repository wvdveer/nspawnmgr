#!/bin/sh
# Reports whether podman is installed on this host - part of the v0.2.0 podman/QEMU backend
# groundwork (see NetworkDiagnosticsExecutor.checkPodman), ahead of any actual podman-backed
# container support. Prints exactly one of:
#   ok       - podman is on PATH.
#   missing  - podman is not installed - see nspawnmgr-install-podman.sh for the matching fix.
set -e
if command -v podman >/dev/null 2>&1; then
    echo "ok"
else
    echo "missing"
fi
