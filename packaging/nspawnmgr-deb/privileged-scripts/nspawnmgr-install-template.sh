#!/bin/sh
# Installs a new container template, or updates an existing one (upsert, keyed on --name) - the
# CI/CD-facing counterpart to the Templates admin page. Reads the template tarball from stdin (this
# script is invoked directly by a real SSH client - e.g. `ssh nspawnmgr_ci@host sudo
# nspawnmgr-install-template.sh ... < template.tar.gz` - never through nspawnmgr's own
# SshRemoteExecutor, whose stdin plumbing is UTF-8 String-based and not binary-safe; going straight
# over a real SSH session means raw tarball bytes are fine).
#
# Deliberately bypasses TemplateService/AdminTemplateApiController entirely and talks to the
# database directly, the same way nspawnmgr-setup-database.sh already does (local admin socket -
# "sudo mysql" / "su postgres -c psql" - no separate DB credential needed). This app has no
# machine-to-machine HTTP auth at all (Basic auth and form login are both explicitly disabled), so
# going through the web layer isn't an option without inventing one; this script's own sudoers
# grant (a separate, narrowly-scoped nspawnmgr_ci account - see nspawnmgr-ci.sudoers) is the trust
# boundary instead.
#
# Usage:
#   nspawnmgr-install-template.sh --name NAME --package-manager APT|DNF|APK|PACMAN
#       [--description TEXT] [--backend SYSTEMD_NSPAWN]
#       [--install-ssh-command CMD] [--install-xrdp-command CMD]
#       [--rdp-capable true|false] [--active true|false]
#       < template.tar.gz
set -e

TEMPLATES_DIR="/var/lib/nspawnmgr/templates"
DB_NAME="nspawnmgr"

NAME=""
DESCRIPTION=""
BACKEND="SYSTEMD_NSPAWN"
PACKAGE_MANAGER=""
INSTALL_SSH_COMMAND=""
INSTALL_XRDP_COMMAND=""
RDP_CAPABLE="true"
ACTIVE="true"

while [ $# -gt 0 ]; do
    case "$1" in
        --name) NAME="$2"; shift 2 ;;
        --description) DESCRIPTION="$2"; shift 2 ;;
        --backend) BACKEND="$2"; shift 2 ;;
        --package-manager) PACKAGE_MANAGER="$2"; shift 2 ;;
        --install-ssh-command) INSTALL_SSH_COMMAND="$2"; shift 2 ;;
        --install-xrdp-command) INSTALL_XRDP_COMMAND="$2"; shift 2 ;;
        --rdp-capable) RDP_CAPABLE="$2"; shift 2 ;;
        --active) ACTIVE="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [ -z "$NAME" ]; then
    echo "--name is required" >&2
    exit 1
fi
if [ -z "$PACKAGE_MANAGER" ]; then
    echo "--package-manager is required" >&2
    exit 1
fi

# --name becomes part of a filesystem path below - reject anything that could escape TEMPLATES_DIR
# or otherwise isn't a plain identifier, before it's used for anything.
case "$NAME" in
    [a-zA-Z0-9]*) ;;
    *) echo "--name must start with a letter or digit" >&2; exit 1 ;;
esac
case "$NAME" in
    *[!a-zA-Z0-9_-]*) echo "--name may only contain letters, digits, '-' and '_'" >&2; exit 1 ;;
esac

# Keep in sync with ContainerBackend.templateSubdirectory() (src/main/java/com/nspawnmgr/domain/
# ContainerBackend.java) - only SYSTEMD_NSPAWN exists today; add cases here when podman/qemu do.
case "$BACKEND" in
    SYSTEMD_NSPAWN) BACKEND_SUBDIR="nspawn" ;;
    *) echo "Unknown --backend: $BACKEND (known: SYSTEMD_NSPAWN)" >&2; exit 1 ;;
esac

case "$PACKAGE_MANAGER" in
    APT | DNF | APK | PACMAN) ;;
    *) echo "Unknown --package-manager: $PACKAGE_MANAGER (known: APT, DNF, APK, PACMAN)" >&2; exit 1 ;;
esac

case "$RDP_CAPABLE" in
    true | false) ;;
    *) echo "--rdp-capable must be 'true' or 'false'" >&2; exit 1 ;;
esac
case "$ACTIVE" in
    true | false) ;;
    *) echo "--active must be 'true' or 'false'" >&2; exit 1 ;;
esac

TARGET_DIR="$TEMPLATES_DIR/$BACKEND_SUBDIR"
TARGET_FILE="$TARGET_DIR/$NAME.tar.gz"
TMP_FILE="$TARGET_FILE.tmp"

mkdir -p "$TARGET_DIR"
cat > "$TMP_FILE"
if ! gzip -t "$TMP_FILE" 2>/dev/null; then
    rm -f "$TMP_FILE"
    echo "stdin was not a valid gzip stream - aborting, no template installed/updated" >&2
    exit 1
fi

# --- DB upsert, keyed on the unique 'name' column - same local-admin-socket connection pattern as
# nspawnmgr-setup-database.sh (no separate DB credential needed; see that script's own comment for
# why no -h/--host flag is used). Only the tarball path is renamed into place *after* this succeeds
# (see below), so a DB failure never leaves a half-installed template on disk.
DB_VENDOR=""
if [ -f /etc/nspawnmgr/db-config/db.properties ]; then
    DB_VENDOR="$(sed -n 's/^DB_VENDOR=//p' /etc/nspawnmgr/db-config/db.properties | tail -1)"
elif [ -f /etc/nspawnmgr/nspawnmgr.env ]; then
    DB_VENDOR="$(sed -n 's/^DB_VENDOR=//p' /etc/nspawnmgr/nspawnmgr.env | tail -1)"
fi
if [ -z "$DB_VENDOR" ]; then
    rm -f "$TMP_FILE"
    echo "Couldn't determine DB_VENDOR from /etc/nspawnmgr/db-config/db.properties or /etc/nspawnmgr/nspawnmgr.env" >&2
    exit 1
fi
case "$DB_VENDOR" in
    mysql | postgresql) ;;
    *) rm -f "$TMP_FILE"; echo "Unknown DB_VENDOR: $DB_VENDOR" >&2; exit 1 ;;
esac

# SQL-escape (double up single quotes) every free-form value; NAME/BACKEND/PACKAGE_MANAGER/
# RDP_CAPABLE/ACTIVE are already validated above and need no escaping.
sql_escape() {
    printf '%s' "$1" | sed "s/'/''/g"
}
sql_string_or_null() {
    if [ -z "$1" ]; then
        printf 'NULL'
    else
        printf "'%s'" "$(sql_escape "$1")"
    fi
}
NAME_SQL="$(sql_escape "$NAME")"
DESCRIPTION_SQL="$(sql_string_or_null "$DESCRIPTION")"
SOURCE_PATH_SQL="$(sql_escape "$NAME")"
INSTALL_SSH_COMMAND_SQL="$(sql_string_or_null "$INSTALL_SSH_COMMAND")"
INSTALL_XRDP_COMMAND_SQL="$(sql_string_or_null "$INSTALL_XRDP_COMMAND")"
RDP_CAPABLE_SQL="$(printf '%s' "$RDP_CAPABLE" | tr '[:lower:]' '[:upper:]')"
ACTIVE_SQL="$(printf '%s' "$ACTIVE" | tr '[:lower:]' '[:upper:]')"

if [ "$DB_VENDOR" = mysql ]; then
    mysql "$DB_NAME" <<SQL
INSERT INTO templates (name, description, source_path, backend, package_manager, install_ssh_command, install_xrdp_command, rdp_capable, active)
VALUES ('$NAME_SQL', $DESCRIPTION_SQL, '$SOURCE_PATH_SQL', '$BACKEND', '$PACKAGE_MANAGER', $INSTALL_SSH_COMMAND_SQL, $INSTALL_XRDP_COMMAND_SQL, $RDP_CAPABLE_SQL, $ACTIVE_SQL)
ON DUPLICATE KEY UPDATE
    description = VALUES(description),
    source_path = VALUES(source_path),
    backend = VALUES(backend),
    package_manager = VALUES(package_manager),
    install_ssh_command = VALUES(install_ssh_command),
    install_xrdp_command = VALUES(install_xrdp_command),
    rdp_capable = VALUES(rdp_capable),
    active = VALUES(active);
SQL
else
    su postgres -c "psql -v ON_ERROR_STOP=1 -d '$DB_NAME'" <<SQL
INSERT INTO templates (name, description, source_path, backend, package_manager, install_ssh_command, install_xrdp_command, rdp_capable, active)
VALUES ('$NAME_SQL', $DESCRIPTION_SQL, '$SOURCE_PATH_SQL', '$BACKEND', '$PACKAGE_MANAGER', $INSTALL_SSH_COMMAND_SQL, $INSTALL_XRDP_COMMAND_SQL, $RDP_CAPABLE_SQL, $ACTIVE_SQL)
ON CONFLICT (name) DO UPDATE SET
    description = EXCLUDED.description,
    source_path = EXCLUDED.source_path,
    backend = EXCLUDED.backend,
    package_manager = EXCLUDED.package_manager,
    install_ssh_command = EXCLUDED.install_ssh_command,
    install_xrdp_command = EXCLUDED.install_xrdp_command,
    rdp_capable = EXCLUDED.rdp_capable,
    active = EXCLUDED.active;
SQL
fi

# Only now, with the DB row confirmed, replace the tarball - an existing template being updated
# stays valid right up until the new one is fully ready.
mv "$TMP_FILE" "$TARGET_FILE"
chmod 644 "$TARGET_FILE"

echo "nspawnmgr-install-template.sh: installed/updated template '$NAME' -> $TARGET_FILE"
