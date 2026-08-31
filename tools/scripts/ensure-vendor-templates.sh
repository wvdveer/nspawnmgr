#!/usr/bin/env bash
# Ensures packaging/nspawnmgr-deb/vendor/{debian-minimal,postgresql-minimal}.tar.gz exist locally -
# needed by BUILD_DEB_BUNDLED/BUILD_ARCH_PKG/BUILD_STEAMOS_PKG/BUILD_RPM (see that vendor
# directory's own README.md). No longer committed to git (~525MB combined - see .gitignore).
#
# For each file:
#   1. Skip it if it's already present locally (repeat local runs don't redo work).
#   2. Otherwise try downloading it from Gitea's generic package registry.
#   3. If that 404s (nobody has published a copy yet, or the Debian release pin changed), bake it
#      locally instead - needs real root (mount/umount/chroot/mknod, a real apt-get install into a
#      fresh rootfs; only confirmed working on a Debian/Ubuntu-family host - see
#      nspawnmgr-create-debian-template.sh's own header comment for the untested chroot fallback on
#      other distros) - then publish the freshly-baked copy back to the registry so the next
#      job/run can just download it instead of baking again.
#
# PACKAGE_REGISTRY_TOKEN (a Gitea access token with package read+write scope) enables both the
# download and the publish-after-baking step; without it, this goes straight to a local bake (with
# no publish afterward) - e.g. a bare local dev build with no CI context.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VENDOR_DIR="$root/packaging/nspawnmgr-deb/vendor"
SERVER_URL="${GITHUB_SERVER_URL:-http://acer.patersonst:3000}"
OWNER=ward
PKG_NAME=nspawnmgr-vendor
PKG_VERSION=bookworm

try_download() {
    local filename="$1"
    local path="$VENDOR_DIR/$filename"

    [ -n "${PACKAGE_REGISTRY_TOKEN:-}" ] || return 1

    echo "ensure-vendor-templates.sh: trying to download $filename from the package registry..."
    if curl -fsSL --user "$OWNER:$PACKAGE_REGISTRY_TOKEN" \
            -o "$path" \
            "$SERVER_URL/api/packages/$OWNER/generic/$PKG_NAME/$PKG_VERSION/$filename"; then
        echo "ensure-vendor-templates.sh: downloaded $filename."
        return 0
    fi
    echo "ensure-vendor-templates.sh: $filename not in the registry (or download failed) - will bake it instead." >&2
    rm -f "$path"
    return 1
}

publish() {
    local filename="$1"
    local path="$VENDOR_DIR/$filename"

    [ -n "${PACKAGE_REGISTRY_TOKEN:-}" ] || return 0

    echo "ensure-vendor-templates.sh: publishing freshly-baked $filename to the registry..."
    curl -s -o /dev/null -X DELETE --user "$OWNER:$PACKAGE_REGISTRY_TOKEN" \
        "$SERVER_URL/api/packages/$OWNER/generic/$PKG_NAME/$PKG_VERSION/$filename" || true
    upload_code="$(curl -s -o /dev/null -w '%{http_code}' --user "$OWNER:$PACKAGE_REGISTRY_TOKEN" \
        --upload-file "$path" \
        "$SERVER_URL/api/packages/$OWNER/generic/$PKG_NAME/$PKG_VERSION/$filename")"
    if [ "$upload_code" = "201" ]; then
        echo "ensure-vendor-templates.sh: published $filename."
    else
        echo "ensure-vendor-templates.sh: publish of $filename failed (HTTP $upload_code) - continuing anyway, this build's own copy is still usable." >&2
    fi
}

mkdir -p "$VENDOR_DIR"
chmod +x "$root/packaging/nspawnmgr-deb/privileged-scripts"/*.sh

if [ ! -f "$VENDOR_DIR/debian-minimal.tar.gz" ]; then
    if ! try_download debian-minimal.tar.gz; then
        echo "ensure-vendor-templates.sh: baking debian-minimal.tar.gz (needs real root)..."
        sudo -n "$root/packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-debian-template.sh" \
            "$VENDOR_DIR/debian-minimal.tar.gz"
        publish debian-minimal.tar.gz
    fi
else
    echo "ensure-vendor-templates.sh: debian-minimal.tar.gz already present, skipping."
fi

if [ ! -f "$VENDOR_DIR/postgresql-minimal.tar.gz" ]; then
    if ! try_download postgresql-minimal.tar.gz; then
        echo "ensure-vendor-templates.sh: baking postgresql-minimal.tar.gz (needs real root, layers on debian-minimal.tar.gz)..."
        sudo -n "$root/packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-postgresql-template.sh" \
            "$VENDOR_DIR/debian-minimal.tar.gz" "$VENDOR_DIR/postgresql-minimal.tar.gz"
        publish postgresql-minimal.tar.gz
    fi
else
    echo "ensure-vendor-templates.sh: postgresql-minimal.tar.gz already present, skipping."
fi
