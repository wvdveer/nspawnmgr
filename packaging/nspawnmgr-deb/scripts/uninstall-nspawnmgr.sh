#!/bin/sh
# Fully removes nspawnmgr: apt purge, plus everything purge deliberately leaves behind (see
# debian/postrm's own comments for why it's conservative by default — this script exists
# specifically for when you don't want that conservatism, e.g. wiping a test machine clean between
# install attempts).
#
# Removes:
#   - The package itself (apt purge -y nspawnmgr)
#   - /opt/tomcat9 (the whole bundled Tomcat instance, including conf/server.xml edits/logs -
#     postrm never touches this since a real deployment might hold real customization)
#   - /etc/nspawnmgr (the sudo/SSH credentials, generated APP_SECRET_KEY, auth-live/db-config) and
#     /etc/guacamole (GUACAMOLE_HOME - guacamole.properties, the auth-jdbc extension/schema
#     scripts, JDBC driver jars)
#   - The tomcat and nspawnmgr_exec system accounts (postrm never removes nspawnmgr_exec at all -
#     it's normally the only credential your containers stay reachable through, which is exactly
#     why a real production removal shouldn't do this without thinking about it first), plus the
#     separate, opt-in nspawnmgr_ci account and its sudoers file if you ever ran
#     setup-ci-template-account.sh (harmless no-ops if you didn't)
#   - /var/lib/nspawnmgr/templates (TEMPLATES_DIR - template tarballs, including anything the
#     "Set up debian-minimal" button downloaded). Left behind by a plain "apt purge" the same way
#     everything else in this list is, which bit us directly: a leftover debian-minimal.tar.gz
#     from a previous install survived a purge+reinstall cycle and made the button's "must not
#     already exist" safety check in nspawnmgr-create-debian-template.sh fail on the next attempt.
#   - /var/cache/nspawnmgr/packages (the host-side package cache - both nspawnmgr's own automatic
#     RDP/VNC/desktop-manager download cache and the admin-uploaded package cache). Safely
#     re-creatable/re-downloadable, same category as the templates dir above, not user data.
#   - Any nspawnmgr-managed machine boot settings (auto-start-on-host-boot unit enablement, and the
#     requires-another-machine systemd drop-in - see nspawnmgr-set-machine-autostart.sh/
#     nspawnmgr-set-machine-requires.sh). Left behind by everything else above (this is pure
#     systemd unit-file state, keyed only by machine name, not touched by apt purge or even by
#     removing the containers themselves) - a stale Requires= drop-in from a previous install
#     broke a fresh reinstall outright, failing "machinectl start nspawnmgr" with "A dependency
#     job for systemd-nspawn@nspawnmgr.service failed." because the unit it required no longer
#     existed.
#
# Two more destructive steps are each offered separately, gated by their own y/n prompt (never
# implied by --yes below, and skipped entirely if --yes is given - only ever done interactively):
#   - Dropping the nspawnmgr/guacamole databases (and their DB users) - only supported when
#     DB_URL (read from /etc/nspawnmgr/db-config/db.properties, falling back to
#     /etc/nspawnmgr/nspawnmgr.env) points at localhost/127.0.0.1, using the same "sudo mysql"/
#     "su postgres -c psql" local-admin mechanism nspawnmgr-setup-database.sh already uses -
#     read BEFORE the config files below are removed, since that's the only place these
#     credentials live.
#   - Removing every container image machinectl knows about, running or not (terminate + remove
#     each, plus its /var/lib/machines rootfs and .nspawn settings file) - this is real container
#     data, not just nspawnmgr's own management layer around it.
#
# Does NOT touch: /var/lib/machines or the databases unless you explicitly confirm one of the two
# prompts above - by default this script still only removes the management layer, same as before.
#
# Usage: sudo uninstall-nspawnmgr.sh [--yes]
#   --yes: skip ALL confirmation prompts, including the two above (defaults to NOT dropping
#          databases/containers - for scripted/CI use, where silently destroying data on a "yes"
#          to a generic prompt would be a nasty surprise)
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "uninstall-nspawnmgr.sh must be run as root." >&2
    exit 1
fi

CONFIRMED=""
while [ $# -gt 0 ]; do
    case "$1" in
        --yes) CONFIRMED="1"; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

# Read DB connection details now, before anything below might remove the files that hold them.
# db.properties (DatabaseSetupWizardServlet's own file) takes priority; nspawnmgr.env (a manually
# or wizard-backfilled DB_URL/DB_VENDOR) is the fallback. Best-effort only - if neither exists, the
# drop-databases prompt below is simply never offered.
DB_VENDOR=""
DB_HOST=""
if [ -f /etc/nspawnmgr/db-config/db.properties ]; then
    DB_URL_RAW="$(sed -n 's/^DB_URL=//p' /etc/nspawnmgr/db-config/db.properties | tail -1)"
    DB_VENDOR="$(sed -n 's/^DB_VENDOR=//p' /etc/nspawnmgr/db-config/db.properties | tail -1)"
    # db.properties is written by java.util.Properties#store (DatabaseSetupWizardServlet), which
    # backslash-escapes ':' (and '=', '!', '#') in values - "jdbc:mysql://localhost:3306/db" is
    # written out as "jdbc\:mysql\://localhost\:3306/db". Left un-undone, the DB_HOST regex below
    # never matches (no literal ':' to anchor on), so DB_HOST ends up as the whole raw escaped
    # string - never equal to "localhost", even when the DB genuinely is local.
    DB_URL_RAW="$(printf '%s' "$DB_URL_RAW" | sed 's/\\\(.\)/\1/g')"
    DB_VENDOR="$(printf '%s' "$DB_VENDOR" | sed 's/\\\(.\)/\1/g')"
elif [ -f /etc/nspawnmgr/nspawnmgr.env ]; then
    DB_URL_RAW="$(sed -n 's/^DB_URL=//p' /etc/nspawnmgr/nspawnmgr.env | tail -1)"
    DB_VENDOR="$(sed -n 's/^DB_VENDOR=//p' /etc/nspawnmgr/nspawnmgr.env | tail -1)"
fi
# DB_URL_RAW looks like jdbc:mysql://host:port/database or jdbc:postgresql://host:port/database
if [ -n "${DB_URL_RAW:-}" ]; then
    DB_HOST="$(echo "$DB_URL_RAW" | sed -E 's#^jdbc:[a-zA-Z]+://([^:/]+).*#\1#')"
fi

if [ -z "$CONFIRMED" ]; then
    printf '%s\n' \
        "This will completely remove nspawnmgr: the package, /opt/tomcat9, /etc/nspawnmgr," \
        "/etc/guacamole, /var/lib/nspawnmgr/templates, /var/cache/nspawnmgr/packages, the" \
        "tomcat/nspawnmgr_exec/nspawnmgr_ci system accounts (the last one only if you ever set it up)," \
        "and any machine boot settings (auto-start, requires-another-machine) nspawnmgr configured" \
        "on your behalf." \
        "" \
        "It does NOT touch nspawnmgr's own database, Guacamole's own database, or" \
        "/var/lib/machines (your actual containers) by default - only the management layer" \
        "around them (plus the templates used to create them) is removed. You'll be asked" \
        "separately below about those two." \
        ""
    printf 'Continue? [y/N] '
    read -r reply
    case "$reply" in
        y | Y | yes | YES) ;;
        *) echo "Aborted."; exit 1 ;;
    esac
fi

DROP_DATABASES=""
if [ -z "$CONFIRMED" ] && [ -n "$DB_VENDOR" ]; then
    if [ "$DB_HOST" = "localhost" ] || [ "$DB_HOST" = "127.0.0.1" ]; then
        printf 'Also drop the nspawnmgr and guacamole databases and their DB users (%s, local)? This cannot be undone. [y/N] ' "$DB_VENDOR"
        read -r reply
        case "$reply" in
            y | Y | yes | YES) DROP_DATABASES="1" ;;
        esac
    else
        echo "uninstall-nspawnmgr.sh: DB_URL points at a non-local host ($DB_HOST) - dropping databases automatically isn't supported for that; drop them yourself if you want them gone." >&2
    fi
fi

REMOVE_CONTAINERS=""
if [ -z "$CONFIRMED" ] && command -v machinectl >/dev/null 2>&1; then
    printf 'Also remove every container currently registered with machinectl (terminate + remove each)? This cannot be undone. [y/N] '
    read -r reply
    case "$reply" in
        y | Y | yes | YES) REMOVE_CONTAINERS="1" ;;
    esac
fi

echo "uninstall-nspawnmgr.sh: purging the package..."
apt purge -y nspawnmgr 2>&1 || echo "uninstall-nspawnmgr.sh: apt purge reported an error (continuing - it may already be removed)." >&2

if [ -n "$DROP_DATABASES" ]; then
    echo "uninstall-nspawnmgr.sh: dropping the nspawnmgr and guacamole databases and their users..."
    if [ "$DB_VENDOR" = mysql ]; then
        mysql -e "DROP DATABASE IF EXISTS \`nspawnmgr\`; DROP USER IF EXISTS 'nspawnmgr'@'localhost'; DROP DATABASE IF EXISTS \`guacamole\`; DROP USER IF EXISTS 'guacamole'@'localhost';" \
            || echo "uninstall-nspawnmgr.sh: dropping databases failed (continuing)." >&2
    elif [ "$DB_VENDOR" = postgresql ]; then
        su postgres -c 'psql -v ON_ERROR_STOP=1' <<SQL || echo "uninstall-nspawnmgr.sh: dropping databases failed (continuing)." >&2
DROP DATABASE IF EXISTS "nspawnmgr";
DROP ROLE IF EXISTS "nspawnmgr";
DROP DATABASE IF EXISTS "guacamole";
DROP ROLE IF EXISTS "guacamole";
SQL
    else
        echo "uninstall-nspawnmgr.sh: unknown DB_VENDOR '$DB_VENDOR' - skipping database drop." >&2
    fi
fi

if [ -n "$REMOVE_CONTAINERS" ]; then
    echo "uninstall-nspawnmgr.sh: removing all containers (registrations, machine images, and .nspawn settings)..."
    # machinectl list-images, not machinectl list - the latter only shows currently-running
    # machines, leaving every stopped container's image on disk untouched. machinectl remove only
    # removes the registration (same reason ContainerLifecycleService.delete() also separately
    # calls nspawnmgr-delete-machine-files.sh) - the actual rootfs directory needs its own rm -rf.
    machinectl list-images --no-legend --full 2>/dev/null | awk '{print $1}' | while read -r machine; do
        [ -n "$machine" ] || continue
        echo "  - $machine"
        machinectl terminate "$machine" 2>/dev/null || true
        # machinectl terminate returns once teardown is *initiated*, not once systemd has finished
        # releasing the machine's mounts - an immediate remove can lose that race with "Device or
        # resource busy", which then surfaces downstream as rm's own confusing "Directory not
        # empty" (rm can't unlink a still-mounted entry). Retry a few times before giving up, same
        # as RealContainerCliExecutor.remove() already does for the normal delete-via-UI path.
        attempt=1
        while [ "$attempt" -le 10 ]; do
            err="$(machinectl remove "$machine" 2>&1)" && break
            case "$err" in
                *[Bb]usy*) ;;
                *) break ;;
            esac
            sleep 3
            attempt=$((attempt + 1))
        done
        rm -rf "/var/lib/machines/$machine"
        rm -f "/etc/systemd/nspawn/$machine.nspawn"
    done
fi

echo "uninstall-nspawnmgr.sh: removing /opt/tomcat9, /etc/nspawnmgr, /etc/guacamole, /var/lib/nspawnmgr/templates, /var/cache/nspawnmgr/packages..."
rm -rf /opt/tomcat9 /etc/nspawnmgr /etc/guacamole /var/lib/nspawnmgr/templates /var/cache/nspawnmgr/packages

echo "uninstall-nspawnmgr.sh: removing any leftover systemd units..."
rm -f /etc/systemd/system/tomcat9.service /etc/systemd/system/guacd.service
rm -rf /etc/systemd/system/tomcat9.service.d
systemctl daemon-reload 2>/dev/null || true

# Machine settings (auto-start-on-host-boot / requires-another-machine, set via /admin/settings'
# Machine settings panel - see nspawnmgr-set-machine-autostart.sh/nspawnmgr-set-machine-requires.sh)
# live entirely in systemd's own unit-enablement state and per-unit drop-in files, keyed only by
# machine name - nothing above touches this: not apt purge, not removing /var/lib/nspawnmgr, and
# not even removing the containers themselves in the REMOVE_CONTAINERS branch above (machinectl
# remove never touches systemd unit enablement/drop-ins at all). Left behind, this broke a real
# reinstall: the self-hosted "nspawnmgr" machine's own Requires= drop-in survived a previous
# install's uninstall, and the fresh install's own "machinectl start nspawnmgr" failed outright
# ("A dependency job for systemd-nspawn@nspawnmgr.service failed.") because the database machine
# unit it pointed at no longer existed. Enumerated rather than assumed to be just "nspawnmgr"/the
# database machine - any managed container could have had either setting configured via the UI.
echo "uninstall-nspawnmgr.sh: removing any nspawnmgr-managed machine boot settings (auto-start, requires-another-machine)..."
for dropin in /etc/systemd/system/systemd-nspawn@*.service.d/nspawnmgr-requires.conf; do
    [ -e "$dropin" ] || continue
    rm -f "$dropin"
    rmdir "$(dirname "$dropin")" 2>/dev/null || true
done
systemctl list-unit-files 'systemd-nspawn@*.service' --no-legend 2>/dev/null | awk '$2 == "enabled" {print $1}' | while read -r unit; do
    [ -n "$unit" ] || continue
    systemctl disable "$unit" 2>/dev/null || true
done
systemctl daemon-reload 2>/dev/null || true

echo "uninstall-nspawnmgr.sh: removing dnsmasq config (dnsmasq itself is an apt dependency, left installed)..."
rm -f /etc/dnsmasq.d/nspawnmgr.conf
systemctl restart dnsmasq 2>/dev/null || true

echo "uninstall-nspawnmgr.sh: removing the shared container bridge (nspawnbr0)..."
rm -f /etc/systemd/network/70-nspawnmgr-bridge.netdev /etc/systemd/network/70-nspawnmgr-bridge.network
systemctl restart systemd-networkd 2>/dev/null || true

echo "uninstall-nspawnmgr.sh: removing system accounts (tomcat, nspawnmgr_exec, nspawnmgr_ci)..."
deluser --remove-home tomcat 2>/dev/null || true
deluser --remove-home nspawnmgr_exec 2>/dev/null || true
deluser --remove-home nspawnmgr_ci 2>/dev/null || true
rm -f /etc/sudoers.d/nspawnmgr_ci

echo "uninstall-nspawnmgr.sh: done."
