#!/usr/bin/env bash
# Best-effort wait for both vendor template packages (debian-minimal.tar.gz,
# postgresql-minimal.tar.gz) to exist in Gitea's package registry before
# real-lifecycle-test.sh boots a real container.
#
# Why: arch-package/rpm-package have no `needs:` ordering against real-container-lifecycle (see
# build.yml's own comments - they run on separate runners/checkouts, no shared state to race on)
# and call ensure-vendor-templates.sh, which bakes+publishes these two tarballs from scratch (a
# heavy chroot apt-get/dnf/pacman install) whenever the registry doesn't already have them.
# Confirmed live: that bake, running concurrently on the same physical acer box, starves the
# freshly-created test container's own boot/readiness enough to blow through
# real-lifecycle-test.sh's polling budget - real-container-lifecycle failed with the test
# container stuck in CREATING, then passed cleanly on a bare re-run once the bake had already
# finished and published. This wait sidesteps that race instead of just hoping a re-run fixes it.
#
# Best-effort only, never fails the job: if PACKAGE_REGISTRY_TOKEN isn't set, or the packages
# still aren't there after the timeout (e.g. a genuinely fresh registry that's never had them
# published), proceeds anyway - debian-real.tar.gz (this job's OWN template, see
# setup-container-template.sh) is what the test actually needs; this is purely a
# contention-avoidance courtesy wait, not a real prerequisite.
#
# Checks existence via a ranged GET (`-r 0-0`), not HEAD - confirmed live against the real
# registry that HEAD isn't supported here (405), while a 1-byte ranged GET is (avoids downloading
# the full ~200-340MB file on every poll).
set -uo pipefail

SERVER_URL="${GITHUB_SERVER_URL:-http://acer.patersonst:3000}"
OWNER=ward
PKG_NAME=nspawnmgr-vendor
PKG_VERSION=bookworm
TIMEOUT_SECONDS="${VENDOR_WAIT_TIMEOUT_SECONDS:-600}"
POLL_INTERVAL_SECONDS=20

if [ -z "${PACKAGE_REGISTRY_TOKEN:-}" ]; then
    echo "wait-for-vendor-packages.sh: no PACKAGE_REGISTRY_TOKEN, skipping (nothing to check)."
    exit 0
fi

exists() {
    curl -fsS -o /dev/null -r 0-0 --user "$OWNER:$PACKAGE_REGISTRY_TOKEN" \
        "$SERVER_URL/api/packages/$OWNER/generic/$PKG_NAME/$PKG_VERSION/$1"
}

elapsed=0
while true; do
    if exists debian-minimal.tar.gz && exists postgresql-minimal.tar.gz; then
        echo "wait-for-vendor-packages.sh: both vendor packages present after ${elapsed}s."
        exit 0
    fi
    if [ "$elapsed" -ge "$TIMEOUT_SECONDS" ]; then
        echo "wait-for-vendor-packages.sh: gave up after ${TIMEOUT_SECONDS}s - proceeding anyway (this job doesn't actually need them, just avoiding a concurrent-bake race)." >&2
        exit 0
    fi
    echo "wait-for-vendor-packages.sh: not both present yet, waiting (${elapsed}s elapsed so far)..."
    sleep "$POLL_INTERVAL_SECONDS"
    elapsed=$((elapsed + POLL_INTERVAL_SECONDS))
done
