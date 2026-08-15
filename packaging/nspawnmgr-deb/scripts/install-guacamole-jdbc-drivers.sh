#!/bin/sh
# Copies Guacamole's own JDBC driver jars into GUACAMOLE_HOME/lib/ — see
# docs/administrator-guide.md §7, "GUACAMOLE_HOME and the auth backend", step 1. Guacamole's JDBC
# auth extension runs in its own webapp classloader, so it needs its own copy of these drivers even
# though nspawnmgr.war already bundles the exact same two jars (mysql-connector-j, postgresql) for
# its own, unrelated database use.
#
# No network access needed: root pom.xml's maven-dependency-plugin execution
# (stage-guacamole-jdbc-drivers) already copies both out of the local Maven repo into
# target/guacamole-jdbc-drivers/ at build time, and the .deb ships that directory verbatim at
# /usr/share/nspawnmgr/guacamole-jdbc-drivers/ — this script just does a plain file copy from there.
#
# Runnable two ways, same convention as setup-sudo-account.sh / install-guacamole-auth-jdbc.sh:
#   1. Standalone, from a repo checkout after `mvn -DskipTests package`:
#      sudo packaging/nspawnmgr-deb/scripts/install-guacamole-jdbc-drivers.sh \
#          --source-dir target/guacamole-jdbc-drivers
#   2. As part of `dpkg -i nspawnmgr*.deb` — debian/postinst calls this same script's installed
#      copy (/usr/lib/nspawnmgr/install-guacamole-jdbc-drivers.sh), which defaults to the .deb's
#      own shipped copy at /usr/share/nspawnmgr/guacamole-jdbc-drivers/.
#
# Idempotent: a plain file copy, safe to re-run (e.g. after a package upgrade bumps driver
# versions) — always overwrites, unlike install-guacamole-auth-jdbc.sh's schema tarball, since
# there's no "don't clobber an admin's customization" concern here.
#
# Usage: install-guacamole-jdbc-drivers.sh [--source-dir DIR] [--target-dir DIR]
set -e

SOURCE_DIR="/usr/share/nspawnmgr/guacamole-jdbc-drivers"
TARGET_DIR="/etc/guacamole/lib"

while [ $# -gt 0 ]; do
    case "$1" in
        --source-dir) SOURCE_DIR="$2"; shift 2 ;;
        --target-dir) TARGET_DIR="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [ "$(id -u)" -ne 0 ]; then
    echo "install-guacamole-jdbc-drivers.sh must be run as root." >&2
    exit 1
fi

if [ ! -f "$SOURCE_DIR/mysql-connector-j.jar" ] || [ ! -f "$SOURCE_DIR/postgresql.jar" ]; then
    echo "install-guacamole-jdbc-drivers.sh: $SOURCE_DIR doesn't contain both driver jars — did the build's stage-guacamole-jdbc-drivers step run?" >&2
    exit 1
fi

mkdir -p "$TARGET_DIR"
cp "$SOURCE_DIR/mysql-connector-j.jar" "$SOURCE_DIR/postgresql.jar" "$TARGET_DIR/"
chown tomcat:tomcat "$TARGET_DIR/mysql-connector-j.jar" "$TARGET_DIR/postgresql.jar" 2>/dev/null \
    || echo "install-guacamole-jdbc-drivers.sh: warning: 'tomcat' user/group not found — leaving driver jars root-owned; Guacamole may not be able to read them." >&2
chmod 644 "$TARGET_DIR/mysql-connector-j.jar" "$TARGET_DIR/postgresql.jar"

echo "install-guacamole-jdbc-drivers.sh: done. Both drivers are now at $TARGET_DIR — Guacamole will"
echo "install-guacamole-jdbc-drivers.sh: pick up whichever one its configured database type needs."
