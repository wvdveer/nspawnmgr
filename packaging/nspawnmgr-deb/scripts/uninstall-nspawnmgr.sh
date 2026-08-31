#!/bin/sh
# Fully removes nspawnmgr: the package itself (apt/dnf/pacman, whichever this host uses), plus
# everything the package's own removal step deliberately leaves behind (see debian/postrm's own
# comments for why it's conservative by default — this script exists specifically for when you
# don't want that conservatism, e.g. wiping a test machine clean between install attempts). Shared
# across the .deb/.rpm/Arch packages, not Debian-specific.
#
# Removes:
#   - The package itself (apt purge / dnf remove / pacman -R, whichever applies)
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
#   - /var/lib/nspawnmgr/qemu-sockets (each QEMU VM's HMP monitor Unix socket - transient, QEMU
#     itself recreates it on every start, same "not user data" category as the templates dir
#     above). QEMU VM DISKS (/var/lib/nspawnmgr/qemu-disks) are real user data instead - see the
#     REMOVE_BACKEND_TOOLING section below, not removed unconditionally here.
#   - Any nspawnmgr-managed machine boot settings (auto-start-on-host-boot unit enablement, and the
#     requires-another-machine systemd drop-in - see nspawnmgr-set-machine-autostart.sh/
#     nspawnmgr-set-machine-requires.sh). Left behind by everything else above (this is pure
#     systemd unit-file state, keyed only by machine name, not touched by apt purge or even by
#     removing the containers themselves) - a stale Requires= drop-in from a previous install
#     broke a fresh reinstall outright, failing "machinectl start nspawnmgr" with "A dependency
#     job for systemd-nspawn@nspawnmgr.service failed." because the unit it required no longer
#     existed.
#
# Three more destructive steps are each offered separately, gated by their own y/n prompt (never
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
#   - Removing podman/QEMU (apt purge) if either is installed - these may have been installed via
#     nspawnmgr's own Diagnostics page ("Fix" button on the podman/QEMU checks, see
#     nspawnmgr-install-podman.sh/nspawnmgr-install-qemu.sh), but this script has no way to tell
#     that apart from an install an admin set up themselves for something unrelated to nspawnmgr -
#     the prompt says so explicitly. If confirmed: every podman container ("pod" - see
#     ProvisioningService.provisionPod) is force-removed first, and every QEMU VM too (libvirt
#     domains via virsh if present, undefined with their storage; otherwise stopping/disabling/
#     removing each of nspawnmgr's own per-VM systemd units - /etc/systemd/system/
#     nspawnmgr-qemu-<name>.service, see nspawnmgr-qemu-write-unit.sh - and deleting its own disk
#     under qemu-disks/, falling back to killing any stray qemu-system-x86_64 process for anything
#     not managed that way) - same reasoning as the "remove every container" step above for
#     machinectl: purging the package itself doesn't stop or clean up anything it was managing, and
#     there's nothing else that ever will once the package itself is gone.
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
        "It does NOT touch nspawnmgr's own database, Guacamole's own database," \
        "/var/lib/machines (your actual containers), or podman/QEMU (if installed) by default -" \
        "only the management layer around them (plus the templates used to create them) is" \
        "removed. You'll be asked separately below about those three." \
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

REMOVE_BACKEND_TOOLING=""
if [ -z "$CONFIRMED" ] && { command -v podman >/dev/null 2>&1 || command -v qemu-system-x86_64 >/dev/null 2>&1; }; then
    printf '%s\n' \
        "podman and/or QEMU are installed on this host - possibly via nspawnmgr's own Diagnostics" \
        "page, but this script has no way to tell that apart from an install you set up yourself" \
        "for something unrelated to nspawnmgr. Confirming will also force-remove every podman" \
        "container (pod) and every QEMU machine first - this cannot be undone."
    printf 'Also remove podman and QEMU? [y/N] '
    read -r reply
    case "$reply" in
        y | Y | yes | YES) REMOVE_BACKEND_TOOLING="1" ;;
    esac
fi

echo "uninstall-nspawnmgr.sh: purging the package..."
# Same per-package-manager branch already used below for the optional podman/QEMU removal - this
# script is shared across the .deb/.rpm/Arch packages (see setup-sudo-account.sh's matching
# comment on why these scripts moved out of debian/ specifically so they could be shared), so the
# package-removal command itself has to branch too, not just the rest of the script.
if command -v apt-get >/dev/null 2>&1; then
    apt purge -y nspawnmgr 2>&1 || echo "uninstall-nspawnmgr.sh: apt purge reported an error (continuing - it may already be removed)." >&2
elif command -v dnf >/dev/null 2>&1; then
    dnf remove -y nspawnmgr 2>&1 || echo "uninstall-nspawnmgr.sh: dnf remove reported an error (continuing - it may already be removed)." >&2
elif command -v pacman >/dev/null 2>&1; then
    # `nspawnmgr` (plain Arch) and `nspawnmgr-steamos` are separate, mutually-exclusive pkgnames
    # (see packaging/nspawnmgr-steamos/PKGBUILD's own provides/conflicts) - `pacman -R` needs the
    # exact installed name, it doesn't resolve a provides/conflicts target the way installation
    # can. Confirmed live: a bare `pacman -R nspawnmgr` on a SteamOS host with nspawnmgr-steamos
    # actually installed failed with "target not found: nspawnmgr" and silently skipped package
    # removal entirely (everything else in this script still ran, so it wasn't fatal, but the
    # package itself was left registered in pacman's own database). Detect which one is actually
    # installed instead of assuming.
    if pacman -Qi nspawnmgr-steamos >/dev/null 2>&1; then
        pacman -Rns --noconfirm nspawnmgr-steamos 2>&1 || echo "uninstall-nspawnmgr.sh: pacman -R reported an error (continuing - it may already be removed)." >&2
    else
        pacman -Rns --noconfirm nspawnmgr 2>&1 || echo "uninstall-nspawnmgr.sh: pacman -R reported an error (continuing - it may already be removed)." >&2
    fi
else
    echo "uninstall-nspawnmgr.sh: no supported package manager found (looked for apt-get, dnf, pacman) - skipping package removal." >&2
fi

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

if [ -n "$REMOVE_BACKEND_TOOLING" ] && command -v podman >/dev/null 2>&1; then
    echo "uninstall-nspawnmgr.sh: removing all podman containers (pods)..."
    # -f: force-removes a running container too (stop + remove in one step) - unlike the machinectl
    # branch above, there's no separate "terminate, then retry remove on Busy" dance needed here,
    # podman's own -f already does both. Purging the podman package itself doesn't stop or clean up
    # anything it was managing, and once podman is gone nothing else ever will either.
    podman ps -a --format '{{.Names}}' 2>/dev/null | while read -r pod; do
        [ -n "$pod" ] || continue
        echo "  - $pod"
        podman rm -f "$pod" 2>/dev/null || true
    done
fi

if [ -n "$REMOVE_BACKEND_TOOLING" ] && command -v qemu-system-x86_64 >/dev/null 2>&1; then
    echo "uninstall-nspawnmgr.sh: removing QEMU machines..."
    if command -v virsh >/dev/null 2>&1; then
        # libvirt-managed VMs, if that's how they were created - list every domain (defined, not
        # just currently running), same "registered, not just running" posture as the
        # machinectl/podman branches above. --remove-all-storage: same "this is real data loss, you
        # already confirmed it" posture as machinectl's own rootfs rm -rf and podman rm -f's own
        # discarded writable layer above - undefine alone would leave disk images behind forever,
        # the same kind of leftover this whole script exists to actually clean up.
        virsh list --all --name 2>/dev/null | while read -r domain; do
            [ -n "$domain" ] || continue
            echo "  - $domain"
            virsh destroy "$domain" 2>/dev/null || true
            virsh undefine "$domain" --remove-all-storage 2>/dev/null || true
        done
    else
        # No libvirt - nspawnmgr's own QEMU VMs are real per-VM systemd units
        # (/etc/systemd/system/nspawnmgr-qemu-<name>.service - see nspawnmgr-qemu-write-unit.sh),
        # not transient/bare processes, so THIS is the actual registry to enumerate from. Stop,
        # disable, and remove each one, then delete its own disk under qemu-disks/ - real VM data,
        # same "you already confirmed this" posture as machinectl's own rootfs rm -rf and podman
        # rm -f's own discarded writable layer above. pkill stays as a fallback for anything not
        # managed this way (e.g. a QEMU VM someone launched by hand outside nspawnmgr entirely).
        systemctl list-unit-files 'nspawnmgr-qemu-*.service' --no-legend 2>/dev/null | while read -r unit _; do
            [ -n "$unit" ] || continue
            echo "  - $unit"
            systemctl stop "$unit" 2>/dev/null || true
            systemctl disable "$unit" 2>/dev/null || true
            rm -f "/etc/systemd/system/$unit"
        done
        systemctl daemon-reload 2>/dev/null || true
        rm -rf /var/lib/nspawnmgr/qemu-disks
        pkill -f qemu-system-x86_64 2>/dev/null || true
    fi
fi

if [ -n "$REMOVE_BACKEND_TOOLING" ]; then
    echo "uninstall-nspawnmgr.sh: removing podman and QEMU..."
    # Same host-package-manager detection as nspawnmgr-install-podman.sh/nspawnmgr-install-qemu.sh -
    # whichever one actually installed them is whichever this host has.
    if command -v apt-get >/dev/null 2>&1; then
        apt purge -y podman qemu-system-x86 qemu-utils 2>&1 \
            || echo "uninstall-nspawnmgr.sh: apt purge of podman/QEMU reported an error (continuing - one or both may not have been installed)." >&2
    elif command -v dnf >/dev/null 2>&1; then
        dnf remove -y podman qemu-kvm 2>&1 \
            || echo "uninstall-nspawnmgr.sh: dnf remove of podman/QEMU reported an error (continuing - one or both may not have been installed)." >&2
    elif command -v pacman >/dev/null 2>&1; then
        pacman -Rns --noconfirm podman qemu-system-x86 2>&1 \
            || echo "uninstall-nspawnmgr.sh: pacman -R of podman/QEMU reported an error (continuing - one or both may not have been installed)." >&2
    else
        echo "uninstall-nspawnmgr.sh: no supported package manager found (looked for apt-get, dnf, pacman) - skipping." >&2
    fi
fi

echo "uninstall-nspawnmgr.sh: removing /opt/tomcat9, /etc/nspawnmgr, /etc/guacamole, /var/lib/nspawnmgr/templates, /var/lib/nspawnmgr/qemu-sockets, /var/cache/nspawnmgr/packages..."
# Confirmed live on SteamOS: `rm -rf` on a path that is itself a SYMLINK only unlinks the symlink
# - it does not recurse into whatever the symlink points at. packaging/nspawnmgr-steamos/
# nspawnmgr.install's own _relocate_to_home() makes /etc/nspawnmgr exactly this (a symlink to
# /home/nspawnmgr/etc-nspawnmgr, for SteamOS's tiny root partition) - a bare `rm -rf /etc/nspawnmgr`
# left the real directory (and the stale SSH_PASSWORD/credentials inside it) completely untouched
# on disk, orphaned but still there, and _relocate_to_home() happily re-linked right back to that
# same stale data on the next install. This script is shared verbatim across all three packages
# (.deb, nspawnmgr-arch, nspawnmgr-steamos) and has no SteamOS-specific knowledge of which paths
# might be relocated - resolving symlinks generically here (rather than hardcoding SteamOS's own
# relocation targets) keeps it correct for any current or future relocation scheme, not just this
# one. /var/lib/nspawnmgr/templates and friends below are unaffected - those are subpaths reached
# THROUGH a symlinked parent, which `rm -rf` already follows correctly; only the exact symlinked
# path itself has this problem.
for p in /opt/tomcat9 /etc/nspawnmgr /etc/guacamole; do
    if [ -L "$p" ]; then
        real_target="$(readlink -f "$p")"
        [ -n "$real_target" ] && [ -d "$real_target" ] && rm -rf "$real_target"
    fi
    rm -rf "$p"
done
rm -rf /var/lib/nspawnmgr/templates /var/lib/nspawnmgr/qemu-sockets /var/cache/nspawnmgr/packages

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

echo "uninstall-nspawnmgr.sh: removing dnsmasq config (dnsmasq itself is a package dependency, left installed)..."
rm -f /etc/dnsmasq.d/nspawnmgr.conf
systemctl restart dnsmasq 2>/dev/null || true

echo "uninstall-nspawnmgr.sh: removing the shared container bridge (nspawnbr0)..."
rm -f /etc/systemd/network/70-nspawnmgr-bridge.netdev /etc/systemd/network/70-nspawnmgr-bridge.network
systemctl restart systemd-networkd 2>/dev/null || true

echo "uninstall-nspawnmgr.sh: removing system accounts (tomcat, nspawnmgr_exec, nspawnmgr_ci)..."
userdel --remove tomcat 2>/dev/null || true
userdel --remove nspawnmgr_exec 2>/dev/null || true
userdel --remove nspawnmgr_ci 2>/dev/null || true
rm -f /etc/sudoers.d/nspawnmgr_ci

echo "uninstall-nspawnmgr.sh: done."
