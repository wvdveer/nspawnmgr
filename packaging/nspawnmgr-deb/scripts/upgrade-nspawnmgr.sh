#!/bin/sh
# Upgrades an existing nspawnmgr install in place, without the destructive
# uninstall-nspawnmgr.sh + fresh-install cycle. Installs the given package file directly (always
# applies its actual content, regardless of whether the recorded installed-version string already
# matches - unlike `apt install <name>=<version>`/`pacman -U`/`dnf install`, a direct local-file
# install isn't skipped as a no-op just because the version string is unchanged, which matters here
# since every build within a dev cycle republishes under the same fixed version - see
# packaging/nspawnmgr-deb/pom.xml's own version property).
#
# That's genuinely the whole job now: every package's own postinst/.install/%post already calls
# nspawnmgr-bootstrap-app-machine.sh unconditionally on both a fresh install AND an upgrade, and
# that script fully reconciles the self-hosted machine's contents on every call (WAR files, guacd's
# bundle/service, Tomcat's own service unit, the SSH-back credential file, the .nspawn port
# mapping) - not just a narrow "refresh the WARs" special case anymore. See that script's own
# header comment for exactly what's always reconciled vs. one-time-only (the base rootfs clone and
# the tomcat/guacd system accounts, which stay untouched on every upgrade to avoid clobbering real
# admin customization or failing outright on a second useradd).
#
# Does NOT touch /var/lib/machines for any OTHER container, or either database.
#
# Usage: sudo upgrade-nspawnmgr.sh <path-to-new-nspawnmgr-package-file>
#   Works with whichever package format this host uses (.deb/.rpm/.pkg.tar.zst) - the install
#   command is the only part that differs, detected the same way uninstall-nspawnmgr.sh's own
#   package-removal step already does.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "upgrade-nspawnmgr.sh must be run as root." >&2
    exit 1
fi

PKG_FILE="${1:-}"
if [ -z "$PKG_FILE" ] || [ ! -f "$PKG_FILE" ]; then
    echo "Usage: sudo upgrade-nspawnmgr.sh <path-to-new-nspawnmgr-package-file>" >&2
    exit 1
fi

echo "upgrade-nspawnmgr.sh: installing $PKG_FILE..."
if command -v dpkg >/dev/null 2>&1; then
    dpkg -i "$PKG_FILE" || {
        echo "upgrade-nspawnmgr.sh: dpkg reported missing dependencies - resolving via apt-get..." >&2
        apt-get install -f -y
    }
elif command -v rpm >/dev/null 2>&1; then
    rpm -Uvh --force "$PKG_FILE"
elif command -v pacman >/dev/null 2>&1; then
    pacman -U --noconfirm "$PKG_FILE"
else
    echo "upgrade-nspawnmgr.sh: no supported package manager found (looked for dpkg, rpm, pacman)." >&2
    exit 1
fi

echo "upgrade-nspawnmgr.sh: done. Check the web UI to confirm nspawnmgr came back up."
