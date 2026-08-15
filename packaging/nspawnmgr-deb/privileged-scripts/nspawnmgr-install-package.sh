#!/bin/sh
# Installs a new package into nspawnmgr's admin package cache, or updates an existing one (upsert,
# keyed on package-manager + filename) - the CI/CD-facing counterpart to the Packages admin page.
# Reads the package file from stdin, same reasoning as nspawnmgr-install-template.sh (a real SSH
# client invokes this directly - e.g. `ssh nspawnmgr_ci@host sudo nspawnmgr-install-package.sh ...
# < xfce4_4.18-2_amd64.deb` - never through nspawnmgr's own SshRemoteExecutor, whose stdin plumbing
# is UTF-8 String-based and not binary-safe).
#
# Deliberately bypasses PackageCacheService/AdminPackageCacheApiController entirely and talks to the
# database directly, the same local-admin-socket technique nspawnmgr-install-template.sh and
# nspawnmgr-setup-database.sh already use - see nspawnmgr-install-template.sh's own comment for why
# (this app has no machine-to-machine HTTP auth at all).
#
# cached_packages.uploaded_by_user_id is NOT NULL (a real FK to users) - unlike templates, which
# have no owner column at all. Rather than requiring CI to already know some existing nspawnmgr
# user's id (meaningless for an automated upload) or relaxing the column to nullable (a schema
# change with no real benefit), this upserts a dedicated 'nspawnmgr-ci' pseudo-user the first time
# it's needed and attributes every CI-installed package to that account - shown in the admin page
# exactly like any other uploader.
#
# Usage:
#   nspawnmgr-install-package.sh --package-manager APT|DNF|APK|PACMAN|ISO --filename NAME
#       [--description TEXT]
#       < package-file
#
# ISO is a real --package-manager value here, not a separate concept - CachedPackage doubles as the
# ISO cache too (a packageManager=ISO row is mounted onto a container rather than installed, see
# ContainerLifecycleService.mountIso), by deliberate choice to keep one upload/cache/CI-publish path
# for both instead of a second parallel one.
set -e

CACHE_ROOT="/var/cache/nspawnmgr/packages"
DB_NAME="nspawnmgr"
CI_EXTERNAL_USER_ID="nspawnmgr-ci"

PACKAGE_MANAGER=""
FILENAME=""
DESCRIPTION=""

while [ $# -gt 0 ]; do
    case "$1" in
        --package-manager) PACKAGE_MANAGER="$2"; shift 2 ;;
        --filename) FILENAME="$2"; shift 2 ;;
        --description) DESCRIPTION="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [ -z "$PACKAGE_MANAGER" ]; then
    echo "--package-manager is required" >&2
    exit 1
fi
if [ -z "$FILENAME" ]; then
    echo "--filename is required" >&2
    exit 1
fi

case "$PACKAGE_MANAGER" in
    APT | DNF | APK | PACMAN | ISO) ;;
    *) echo "Unknown --package-manager: $PACKAGE_MANAGER (known: APT, DNF, APK, PACMAN, ISO)" >&2; exit 1 ;;
esac

# --filename becomes part of a filesystem path below - reject anything that could escape the
# package manager's own uploaded/ directory, same posture as --name in
# nspawnmgr-install-template.sh (and PackageCacheService.sanitizeFilename's own rules, for the
# human-upload path).
case "$FILENAME" in
    */*) echo "--filename may not contain '/'" >&2; exit 1 ;;
esac
case "$FILENAME" in
    .*) echo "--filename may not start with '.'" >&2; exit 1 ;;
esac

PM_SUBDIR="$(printf '%s' "$PACKAGE_MANAGER" | tr '[:upper:]' '[:lower:]')"
TARGET_DIR="$CACHE_ROOT/$PM_SUBDIR/uploaded"
STORED_FILENAME="ci-$FILENAME"
TARGET_FILE="$TARGET_DIR/$STORED_FILENAME"
TMP_FILE="$TARGET_FILE.tmp"

mkdir -p "$TARGET_DIR"
cat > "$TMP_FILE"
SIZE_BYTES="$(wc -c < "$TMP_FILE" | tr -d '[:space:]')"
if [ "$SIZE_BYTES" -eq 0 ]; then
    rm -f "$TMP_FILE"
    echo "stdin was empty - aborting, no package installed/updated" >&2
    exit 1
fi

# --- DB upsert - same local-admin-socket connection pattern as nspawnmgr-install-template.sh (see
# that script's own comment for why no -h/--host flag is used). Only the package file is renamed
# into place *after* both DB writes succeed, so a DB failure never leaves a half-installed package
# on disk, and an existing package being updated stays valid right up until the new one is ready.
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
FILENAME_SQL="$(sql_escape "$FILENAME")"
STORED_FILENAME_SQL="$(sql_escape "$STORED_FILENAME")"
DESCRIPTION_SQL="$(sql_string_or_null "$DESCRIPTION")"

if [ "$DB_VENDOR" = mysql ]; then
    USER_ID="$(mysql -N -B "$DB_NAME" <<SQL
INSERT INTO users (external_user_id, username, role, created_at, updated_at)
VALUES ('$CI_EXTERNAL_USER_ID', '$CI_EXTERNAL_USER_ID', 'USER', NOW(), NOW())
ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id);
SELECT LAST_INSERT_ID();
SQL
)"
    mysql "$DB_NAME" <<SQL
INSERT INTO cached_packages (package_manager, original_filename, stored_filename, description, uploaded_by_user_id, size_bytes, created_at)
VALUES ('$PACKAGE_MANAGER', '$FILENAME_SQL', '$STORED_FILENAME_SQL', $DESCRIPTION_SQL, $USER_ID, $SIZE_BYTES, NOW())
ON DUPLICATE KEY UPDATE
    stored_filename = VALUES(stored_filename),
    description = VALUES(description),
    uploaded_by_user_id = VALUES(uploaded_by_user_id),
    size_bytes = VALUES(size_bytes),
    created_at = VALUES(created_at);
SQL
else
    USER_ID="$(su postgres -c "psql -t -A -v ON_ERROR_STOP=1 -d '$DB_NAME'" <<SQL
INSERT INTO users (external_user_id, username, role, created_at, updated_at)
VALUES ('$CI_EXTERNAL_USER_ID', '$CI_EXTERNAL_USER_ID', 'USER', now(), now())
ON CONFLICT (external_user_id) DO UPDATE SET updated_at = now()
RETURNING id;
SQL
)"
    su postgres -c "psql -v ON_ERROR_STOP=1 -d '$DB_NAME'" <<SQL
INSERT INTO cached_packages (package_manager, original_filename, stored_filename, description, uploaded_by_user_id, size_bytes, created_at)
VALUES ('$PACKAGE_MANAGER', '$FILENAME_SQL', '$STORED_FILENAME_SQL', $DESCRIPTION_SQL, $USER_ID, $SIZE_BYTES, now())
ON CONFLICT (package_manager, stored_filename) DO UPDATE SET
    original_filename = EXCLUDED.original_filename,
    description = EXCLUDED.description,
    uploaded_by_user_id = EXCLUDED.uploaded_by_user_id,
    size_bytes = EXCLUDED.size_bytes,
    created_at = EXCLUDED.created_at;
SQL
fi

mv "$TMP_FILE" "$TARGET_FILE"
chmod 644 "$TARGET_FILE"

echo "nspawnmgr-install-package.sh: installed/updated package '$FILENAME' ($PACKAGE_MANAGER) -> $TARGET_FILE"
