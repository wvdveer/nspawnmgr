#!/bin/sh
# Extracts the guacamole-auth-jdbc extension from a bundled tarball — see
# docs/administrator-guide.md §7, "GUACAMOLE_HOME and the auth backend", for why this component is
# required (not optional) and why it's never an apt package on any Debian/Ubuntu release, unlike
# guacd/guacamole-tomcat. Bundled the same way as the guacd and Tomcat vendor tarballs — no network
# access needed at install time, and no dependence on archive.apache.org being reachable from the
# target host. The tarball itself was downloaded once, checksum-verified against Apache's own
# .sha256, and committed to packaging/nspawnmgr-deb/vendor/.
#
# Installs into a fixed, version-independent "opinionated" directory rather than one named after
# the extracted tarball's own version, so that:
#   - it stays predictable across upgrades (bumping GUACAMOLE_VERSION and re-vendoring the tarball
#     overwrites the same path rather than leaving old-version leftovers behind under a new name)
#   - /admin/settings' "Schema scripts directory" field (see admin-settings.js) can default to it
#     without needing to know which Guacamole version is installed
#
# Runnable two ways, same convention as setup-sudo-account.sh:
#   1. Standalone, from a repo checkout: sudo packaging/nspawnmgr-deb/scripts/install-guacamole-auth-jdbc.sh
#      (defaults --source-tarball to the vendor/ copy next to this script)
#   2. As part of `dpkg -i nspawnmgr*.deb` — debian/postinst calls this same script's installed
#      copy (/usr/lib/nspawnmgr/install-guacamole-auth-jdbc.sh), which defaults --source-tarball to
#      the .deb's own shipped copy at /usr/share/nspawnmgr/.
#
# Idempotent: does nothing (besides printing a message) if the target directory already looks
# populated, so re-running on upgrade never clobbers an admin's own schema-script edits under
# TARGET_DIR. Use --force to re-extract and overwrite anyway.
#
# Usage: install-guacamole-auth-jdbc.sh [--source-tarball FILE] [--target-dir DIR] [--force]
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GUACAMOLE_VERSION="1.5.5"
SOURCE_TARBALL=""
TARGET_DIR="/etc/guacamole/guacamole-auth-jdbc"
FORCE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --source-tarball) SOURCE_TARBALL="$2"; shift 2 ;;
        --target-dir) TARGET_DIR="$2"; shift 2 ;;
        --force) FORCE="1"; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [ -z "$SOURCE_TARBALL" ]; then
    if [ -f "/usr/share/nspawnmgr/guacamole-auth-jdbc-${GUACAMOLE_VERSION}.tar.gz" ]; then
        SOURCE_TARBALL="/usr/share/nspawnmgr/guacamole-auth-jdbc-${GUACAMOLE_VERSION}.tar.gz"
    else
        SOURCE_TARBALL="$SCRIPT_DIR/../vendor/guacamole-auth-jdbc-${GUACAMOLE_VERSION}.tar.gz"
    fi
fi

if [ "$(id -u)" -ne 0 ]; then
    echo "install-guacamole-auth-jdbc.sh must be run as root." >&2
    exit 1
fi

if [ -z "$FORCE" ] && [ -f "$TARGET_DIR/mysql/schema/001-create-schema.sql" ] \
        && [ -f "$TARGET_DIR/postgresql/schema/001-create-schema.sql" ]; then
    echo "install-guacamole-auth-jdbc.sh: $TARGET_DIR already looks populated — nothing to do (use --force to redo)."
    exit 0
fi

if [ ! -f "$SOURCE_TARBALL" ]; then
    echo "install-guacamole-auth-jdbc.sh: source tarball not found at $SOURCE_TARBALL." >&2
    exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

tar -xzf "$SOURCE_TARBALL" -C "$WORK_DIR"
EXTRACTED_DIR="$WORK_DIR/guacamole-auth-jdbc-${GUACAMOLE_VERSION}"
if [ ! -d "$EXTRACTED_DIR/mysql" ] || [ ! -d "$EXTRACTED_DIR/postgresql" ]; then
    echo "install-guacamole-auth-jdbc.sh: extracted archive didn't contain the expected mysql/postgresql layout." >&2
    exit 1
fi

mkdir -p "$TARGET_DIR"
rm -rf "$TARGET_DIR/mysql" "$TARGET_DIR/postgresql"
cp -r "$EXTRACTED_DIR/mysql" "$EXTRACTED_DIR/postgresql" "$TARGET_DIR/"
echo "$GUACAMOLE_VERSION" > "$TARGET_DIR/VERSION"

# Readable by tomcat: nspawnmgr's schema-scripts-runner (Test button on /admin/settings) reads
# these files as the tomcat user, same as it reads GUACAMOLE_HOME itself.
chown -R tomcat:tomcat "$TARGET_DIR" 2>/dev/null || echo "install-guacamole-auth-jdbc.sh: warning: 'tomcat' user/group not found — leaving $TARGET_DIR root-owned; nspawnmgr may not be able to read it." >&2
find "$TARGET_DIR" -type d -exec chmod 755 {} +
find "$TARGET_DIR" -type f -exec chmod 644 {} +

echo "install-guacamole-auth-jdbc.sh: done. Extension JARs + schema scripts are under $TARGET_DIR."
echo "install-guacamole-auth-jdbc.sh: still manual (depends on your chosen database, §7 step 1): copy"
echo "install-guacamole-auth-jdbc.sh:   $TARGET_DIR/<mysql|postgresql>/guacamole-auth-jdbc-<db>-${GUACAMOLE_VERSION}.jar into GUACAMOLE_HOME/extensions/,"
echo "install-guacamole-auth-jdbc.sh:   and that database's own JDBC driver into GUACAMOLE_HOME/lib/."
