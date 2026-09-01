#!/bin/sh
# Bootstraps nspawnmgr's own self-hosted "nspawnmgr" machine at .deb-install time, and reconciles
# it on every subsequent install/upgrade too (called unconditionally from postinst/.install/%post
# on both a fresh install AND an upgrade - see each package's own postinstall script). Already
# root, so (unlike every other privileged-scripts/*.sh here) this never goes through an SSH+sudo
# round-trip; it just builds/updates a container's rootfs the same way postinst used to build the
# HOST's own /opt/tomcat9 and /opt/guacd-bundle, retargeted at /var/lib/machines/nspawnmgr instead.
#
# No arguments - fully opinionated per the design doc: the machine is always named "nspawnmgr".
#
# Two categories of work, treated very differently:
#   - ONE-TIME ONLY (skipped once the machine already exists): cloning the base debian-minimal
#     rootfs, creating the tomcat/guacd system accounts. Re-running either against an
#     already-provisioned, possibly admin-customized machine would be actively destructive
#     (re-extracting the base image over live files could clobber real customization; useradd
#     fails outright on a second run anyway).
#   - ALWAYS RECONCILED (re-applied on every call, fresh install or upgrade alike): JRE package
#     install, the four bundled WARs, guacd's bundle/service/properties, Tomcat's own service
#     unit, the SSH-back credential file, and the .nspawn settings file. These are all
#     package-owned artifacts, not admin customization surface - if a new package version changes
#     any of them, this is what makes that change actually reach an already-provisioned machine on
#     a plain upgrade, not just a fresh install. This is the mechanism upgrade-nspawnmgr.sh relies
#     on: every package's postinst/.install/%post already calls this script unconditionally on
#     both install and upgrade, so making every one of these steps safe to re-run is what lets a
#     new package version's changes actually reach an already-provisioned machine without a full
#     uninstall+reinstall - no separate versioned hook script needed, this one script is the
#     single source of truth for "what should be true inside this machine," reconciled fresh every
#     time it's called.
#
# Known, accepted gap: re-extracting guacd-bundle.tar.gz/apache-tomcat's own tree on reconcile
# overwrites/adds files but never removes ones a newer version dropped - harmless leftover files,
# not worth solving. Tomcat's own extracted tree (/opt/tomcat9 itself, not just the WAR/context
# layer) and the base rootfs clone are deliberately NOT touched on reconcile at all, even though
# Tomcat's binaries are package-owned in principle - conf/ under that same tree is plausible admin
# customization surface (e.g. connector settings), so this stays conservative; a Tomcat *version*
# bump still needs a full reinstall, matching the existing guacd-only-needs-reinstall caveat this
# project already documents for upgrade-nspawnmgr.sh.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-bootstrap-app-machine.sh must be run as root." >&2
    exit 1
fi

MACHINE_NAME="nspawnmgr"
PRIVILEGED_SCRIPTS_DIR="/usr/lib/nspawnmgr/privileged"
TEMPLATES_DIR="/var/lib/nspawnmgr/templates/nspawn"
DEBIAN_TEMPLATE="$TEMPLATES_DIR/debian-minimal.tar.gz"
ROOTFS="/var/lib/machines/$MACHINE_NAME"
TOMCAT_TARBALL="/usr/share/nspawnmgr/apache-tomcat-9.0.120.tar.gz"
NSPAWN_FILE="/etc/systemd/nspawn/$MACHINE_NAME.nspawn"
BOOTSTRAP_MARKER="$ROOTFS/etc/nspawnmgr/.bootstrap-complete"

IS_RECONCILE=0
if [ -e "$ROOTFS" ]; then
    if [ -e "$BOOTSTRAP_MARKER" ]; then
        IS_RECONCILE=1
    else
        # Exists but never finished (confirmed live: a package's postinst can abort this script
        # partway through, e.g. openjdk-17-jre-headless needing /proc mounted - see the JRE install
        # step below) - none of the one-time-only steps below are safely re-runnable against a
        # half-populated rootfs (adduser/useradd in particular fail outright on a second run),
        # so start over cleanly rather than trying to resume or reconcile.
        echo "nspawnmgr-bootstrap-app-machine.sh: $ROOTFS exists but looks like an incomplete prior attempt - removing it and starting over."
        machinectl remove "$MACHINE_NAME" 2>/dev/null || true
        rm -rf "$ROOTFS"
        rm -f "$NSPAWN_FILE"
    fi
fi

for cmd in chroot mount umount machinectl systemctl tar; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Required command '$cmd' not found." >&2
        exit 1
    fi
done

# Determines which host port forwards to this machine's Tomcat. On reconcile, reuse whatever's
# already configured in $NSPAWN_FILE rather than re-searching - re-searching could silently pick a
# *different* free port than last time (e.g. if something else now happens to hold 8080), moving
# the whole web UI to a new port out from under bookmarks/AUTH_LOGIN_URL on a routine upgrade. Only
# falls through to a fresh search if reconciling but no parseable existing mapping was found
# (defensive - e.g. a hand-edited .nspawn file).
pick_host_port() {
    if [ "$IS_RECONCILE" -eq 1 ] && [ -f "$NSPAWN_FILE" ]; then
        existing="$(grep -oE '^Port=tcp:[0-9]+:8080$' "$NSPAWN_FILE" | head -1 | sed -E 's/^Port=tcp:([0-9]+):8080$/\1/')"
        if [ -n "$existing" ]; then
            echo "$existing"
            return
        fi
    fi
    port=8080
    while ss -Htln 2>/dev/null | awk '{print $4}' | grep -qE ":${port}\$"; do
        port=$((port + 1))
    done
    echo "$port"
}

# The SSH-back credentials - a rewritten copy of the host's own nspawnmgr.env with SSH_HOST changed
# from 127.0.0.1 (today's "SSH to itself" default) to nspawnbr0's own fixed address (see
# 70-nspawnmgr-bridge.network's Address=10.100.0.1/24); HOST_PUBLIC_ADDRESS changed the same way -
# it's what guacd (living inside this container too) dials to reach a custom port mapping's
# *host*-forwarded port, and those forwards are bound on the bare host's own network stack, not any
# particular container's, so guacd needs the bridge address to reach them now, the same reason
# SSH_HOST changes. Every other key (NSPAWN_PRIVILEGED_SCRIPTS_DIR included) names a path on the
# SSH *target* (the host), not the local filesystem, so those stay correct unchanged regardless of
# where the JVM making the SSH call itself lives.
#
# USER_ID_URL and AUTH_LOGIN_URL need DIFFERENT treatment, not the same port-only substitution -
# confirmed live this bit a real install (endless login loop, nspawnmgr.war -> auth.war -> back to
# nspawnmgr.war): AUTH_LOGIN_URL is browser-facing (the redirect a real browser follows), so it
# correctly needs the external DETECTED_HOSTNAME:HOST_PORT the browser can actually reach.
# USER_ID_URL is a server-to-server call - nspawnmgr.war calling auth.war's own /auth/userinfo -
# and since self-hosting put both WARs in the exact same Tomcat/JVM inside this container, that
# call has no reason to leave the container at all. Rewritten unconditionally to the container's
# own loopback instead - routing it back out through the veth, the host's Port= forward, and back
# in (hairpin NAT) is needlessly fragile and breaks outright whenever HOST_PORT != 8080.
refresh_credential_file() {
    sed -e 's/^SSH_HOST=.*/SSH_HOST=10.100.0.1/' \
        -e 's/^HOST_PUBLIC_ADDRESS=.*/HOST_PUBLIC_ADDRESS=10.100.0.1/' \
        -e 's|^USER_ID_URL=.*|USER_ID_URL=http://localhost:8080/auth/userinfo|' \
        -e "s/^\(AUTH_LOGIN_URL=http:\/\/[^:]*\):8080/\1:${HOST_PORT}/" \
        /etc/nspawnmgr/nspawnmgr.env > "$ROOTFS/etc/nspawnmgr/nspawnmgr.env"
    cp /etc/nspawnmgr/ssh_id_ed25519 "$ROOTFS/etc/nspawnmgr/ssh_id_ed25519"
    chroot "$ROOTFS" chown root:tomcat /etc/nspawnmgr/nspawnmgr.env /etc/nspawnmgr/ssh_id_ed25519
    chmod 640 "$ROOTFS/etc/nspawnmgr/nspawnmgr.env" "$ROOTFS/etc/nspawnmgr/ssh_id_ed25519"
}

# The four bundled WARs, deployed via context descriptors pointing at a location inside the
# container - re-copying on reconcile is exactly how a new package version's app-code changes
# reach an already-provisioned machine (this was upgrade-nspawnmgr.sh's own original, narrower
# purpose - now folded in here so every package variant gets it for free, not just the .deb).
refresh_wars() {
    mkdir -p "$ROOTFS/usr/share/nspawnmgr" "$ROOTFS/opt/tomcat9/conf/Catalina/localhost"
    cp /usr/share/nspawnmgr/nspawnmgr.war /usr/share/nspawnmgr/auth.war \
       /usr/share/nspawnmgr/ROOT.war /usr/share/nspawnmgr/guacamole.war "$ROOTFS/usr/share/nspawnmgr/"
    cat > "$ROOTFS/opt/tomcat9/conf/Catalina/localhost/nspawnmgr.xml" <<EOF
<Context docBase="/usr/share/nspawnmgr/nspawnmgr.war" />
EOF
    cat > "$ROOTFS/opt/tomcat9/conf/Catalina/localhost/auth.xml" <<EOF
<Context docBase="/usr/share/nspawnmgr/auth.war" />
EOF
    cat > "$ROOTFS/opt/tomcat9/conf/Catalina/localhost/guacamole.xml" <<EOF
<Context docBase="/usr/share/nspawnmgr/guacamole.war" />
EOF
    cat > "$ROOTFS/opt/tomcat9/conf/Catalina/localhost/ROOT.xml" <<EOF
<Context docBase="/usr/share/nspawnmgr/ROOT.war" />
EOF
    chroot "$ROOTFS" chown tomcat:tomcat /opt/tomcat9/conf/Catalina/localhost/nspawnmgr.xml \
        /opt/tomcat9/conf/Catalina/localhost/auth.xml /opt/tomcat9/conf/Catalina/localhost/guacamole.xml \
        /opt/tomcat9/conf/Catalina/localhost/ROOT.xml
}

# tomcat9.service itself - package-owned, safe to always overwrite (unlike Tomcat's own extracted
# tree, this unit file has no admin-customization story of its own).
refresh_tomcat_unit() {
    cp /usr/share/nspawnmgr/tomcat9.service "$ROOTFS/etc/systemd/system/tomcat9.service"
    mkdir -p "$ROOTFS/etc/systemd/system/tomcat9.service.d"
    cat > "$ROOTFS/etc/systemd/system/tomcat9.service.d/nspawnmgr.conf" <<EOF
[Service]
EnvironmentFile=/etc/nspawnmgr/nspawnmgr.env
EOF
}

# guacd + GUACAMOLE_HOME - guacd moves into this container too (it's not on the "stays on host"
# list), co-located with guacamole.war in the same network namespace, so the seeded guacd-hostname
# stays "localhost" unchanged. install-guacamole-auth-jdbc.sh/install-guacamole-jdbc-drivers.sh
# already support --target-dir, so they're reused as-is, pointed at the container's own rootfs;
# their own best-effort `chown tomcat` fails harmlessly (the host has no "tomcat" user in this
# architecture) and is fixed up by the chroot chown pass below, which resolves "tomcat" against the
# container's OWN /etc/passwd instead.
refresh_guacd() {
    mkdir -p "$ROOTFS/opt/guacd-bundle"
    tar -xzf /usr/share/nspawnmgr/guacd-bundle.tar.gz -C "$ROOTFS/opt"
    chroot "$ROOTFS" chown -R root:root /opt/guacd-bundle
    find "$ROOTFS/opt/guacd-bundle" -type d -exec chmod 755 {} +
    find "$ROOTFS/opt/guacd-bundle" -type f -exec chmod 644 {} +
    chmod 755 "$ROOTFS/opt/guacd-bundle/sbin/guacd"
    cp /usr/share/nspawnmgr/guacd.service "$ROOTFS/etc/systemd/system/guacd.service"
    mkdir -p "$ROOTFS/etc/guacamole"
    # Seed ONLY if missing - never overwrite unconditionally. Real bug found live: this file also
    # holds the auth-jdbc PostgreSQL extension's own connection properties (postgresql-hostname/
    # -port/-database/-username/-password), added later by the DB setup wizard, not by anything
    # this script re-runs. Blindly `cat >`-ing it on every reconcile wiped those out, leaving
    # guacd-hostname/guacd-port as the only two lines - the extension itself still "loaded" (that
    # only checks the jar/manifest), but every real login then failed with Guacamole's own
    # "Authentication attempt ignored because the relevant authentication provider could not be
    # loaded" once it actually tried to open a DB connection with no postgresql-* properties left
    # to open it with.
    if [ ! -f "$ROOTFS/etc/guacamole/guacamole.properties" ]; then
        cat > "$ROOTFS/etc/guacamole/guacamole.properties" <<EOF
guacd-hostname: localhost
guacd-port: 4822
EOF
    fi
    "$(dirname "$0")/../install-guacamole-auth-jdbc.sh" --target-dir "$ROOTFS/etc/guacamole/guacamole-auth-jdbc" \
        || true
    "$(dirname "$0")/../install-guacamole-jdbc-drivers.sh" --target-dir "$ROOTFS/etc/guacamole/lib" \
        || echo "nspawnmgr-bootstrap-app-machine.sh: install-guacamole-jdbc-drivers.sh failed — rerun manually against $ROOTFS/etc/guacamole/lib." >&2
    chroot "$ROOTFS" chown -R tomcat:tomcat /etc/guacamole
}

# .nspawn settings - PrivateUsers=identity and Bridge=nspawnbr0 like every other managed container
# (see NspawnSettingsRenderer for why PrivateUsers is pinned to identity rather than left at
# systemd's own default), plus a Port= forward to the container's own :8080 using HOST_PORT (see
# pick_host_port() above for why this is preserved, not re-searched, on reconcile).
write_nspawn_settings() {
    mkdir -p /etc/systemd/nspawn
    cat > "$NSPAWN_FILE" <<EOF
[Exec]
PrivateUsers=identity
[Network]
Bridge=nspawnbr0
Port=tcp:${HOST_PORT}:8080
EOF
}

# 1. Get debian-minimal in place if it doesn't exist yet. Left in TEMPLATES_DIR (not a throwaway
#    temp path) so it's exactly the same tarball the app's own "Set up debian-minimal" button
#    would have produced, and SelfHostedMachineDiscoveryService / the admin's own Templates page
#    can find and register it once the app boots for real. Orthogonal to IS_RECONCILE - this is
#    about TEMPLATES_DIR, not $ROOTFS, and runs the same way regardless.
#
#    The "bundled" .deb variant (packaging/nspawnmgr-deb-bundled) ships a pre-baked copy at
#    BUNDLED_DEBIAN_MINIMAL - prefer that (a plain copy, no network access needed at all) over
#    baking fresh; the "online" variant (packaging/nspawnmgr-deb, unchanged default) never stages
#    that file, so this always falls through to baking there, identical to before this fallback
#    was added.
mkdir -p "$TEMPLATES_DIR"
BUNDLED_DEBIAN_MINIMAL="/usr/share/nspawnmgr/debian-minimal.tar.gz"
if [ ! -e "$DEBIAN_TEMPLATE" ]; then
    if [ -e "$BUNDLED_DEBIAN_MINIMAL" ]; then
        echo "nspawnmgr-bootstrap-app-machine.sh: using the bundled debian-minimal image..."
        cp "$BUNDLED_DEBIAN_MINIMAL" "$DEBIAN_TEMPLATE"
    else
        echo "nspawnmgr-bootstrap-app-machine.sh: baking debian-minimal..."
        "$PRIVILEGED_SCRIPTS_DIR/nspawnmgr-create-debian-template.sh" "$DEBIAN_TEMPLATE"
    fi
fi

if [ "$IS_RECONCILE" -eq 0 ]; then
    # 2. Clone it into place - ONE-TIME ONLY, see this file's own header comment for why this never
    #    re-runs against an already-provisioned machine. Deliberately NOT going through
    #    nspawnmgr-clone-template.sh's own `machinectl import-tar` here - confirmed live on Fedora
    #    43/systemd 258 (real QEMU host, packaging/nspawnmgr-rpm/ install verification):
    #    systemd-importd itself fails ("Failed to run event loop: Transport endpoint is not
    #    connected", then on retry "Transfer process failed with exit code 1" right after a btrfs
    #    quota-hierarchy ioctl warning it claims to just be "ignoring") independent of anything
    #    nspawnmgr does. A plain extraction is simpler, has no systemd-importd/D-Bus dependency at
    #    all, and machinectl still recognizes the result as an ordinary directory-backed image
    #    afterward (confirmed live: `machinectl list-images` lists it same as import-tar's own
    #    output would).
    echo "nspawnmgr-bootstrap-app-machine.sh: cloning debian-minimal into $ROOTFS..."
    mkdir -p "$ROOTFS"
    tar -xpzf "$DEBIAN_TEMPLATE" -C "$ROOTFS"
    # See nspawnmgr-clone-template.sh's own comment on this exact same restorecon call for the full
    # SELinux mislabeling explanation - confirmed live, this install-time clone needs it too, not
    # just the clone-template.sh path.
    if command -v restorecon >/dev/null 2>&1; then
        restorecon -R "$ROOTFS"
    fi
fi

if [ "$IS_RECONCILE" -eq 1 ]; then
    # Stop the machine before touching its rootfs below - some of it (the JRE package install in
    # particular) needs the host's own /proc/dev/sys/run bind-mounted into $ROOTFS, which would be
    # unsafe/racy against a still-live container. `machinectl stop` returns once the stop is
    # *issued*, not once the container has actually finished tearing down - poll briefly (same
    # 10s/0.5s pattern postinst already uses for the bridge coming up) before proceeding, but don't
    # hard-fail if it's still not fully stopped - best-effort, matching this script's existing risk
    # tolerance elsewhere.
    echo "nspawnmgr-bootstrap-app-machine.sh: stopping $MACHINE_NAME to reconcile its contents..."
    machinectl stop "$MACHINE_NAME" 2>/dev/null || true
    i=1
    while [ "$i" -le 20 ] && machinectl show "$MACHINE_NAME" >/dev/null 2>&1; do
        sleep 0.5
        i=$((i + 1))
    done
    machinectl show "$MACHINE_NAME" >/dev/null 2>&1 \
        && echo "nspawnmgr-bootstrap-app-machine.sh: $MACHINE_NAME didn't fully stop within 10s - continuing anyway." >&2
fi

# 3. JRE + apache2-utils (rotatelogs, which tomcat9.service's own ExecStart pipes catalina.sh's
#    output through for log rotation - previously always a host-level apt Depends; now that Tomcat
#    runs inside this container instead of on the host, nothing installs it there unless this
#    script does) - same host-apt-pointed-at-a-foreign-root / chroot-fallback technique
#    nspawnmgr-create-debian-template.sh already uses for openssh-server. Safe to always re-run:
#    apt-get install on an already-installed package is a normal idempotent no-op, and if a future
#    release needs a newer JRE, re-running this on reconcile picks that up automatically too -
#    openjdk-17-jre-headless's own postinst runs `java` itself as part of its own certificate-store
#    setup, which fails outright ("the java command requires a mounted proc fs (/proc)") without a
#    real /proc inside the chroot - openssh-server's own postinst just never happened to touch
#    anything needing one. Mount all four unconditionally in the host-apt branch too, not just the
#    chroot-fallback one, rather than assuming future packages' own postinst scripts won't hit the
#    same thing.
export DEBIAN_FRONTEND=noninteractive
echo "nspawnmgr-bootstrap-app-machine.sh: installing/reconciling the JRE in $ROOTFS..."
mkdir -p "$ROOTFS/run"
mount --bind /run "$ROOTFS/run"
mount --bind /dev "$ROOTFS/dev"
mount -t proc proc "$ROOTFS/proc"
mount -t sysfs sys "$ROOTFS/sys"
jre_install_cleanup() {
    umount "$ROOTFS/sys" 2>/dev/null || true
    umount "$ROOTFS/proc" 2>/dev/null || true
    umount "$ROOTFS/dev" 2>/dev/null || true
    umount "$ROOTFS/run" 2>/dev/null || true
}
trap jre_install_cleanup EXIT
if command -v apt-get >/dev/null 2>&1; then
    APT_OPTS="-o Dir=$ROOTFS -o Dir::State::status=$ROOTFS/var/lib/dpkg/status -o APT::Sandbox::User=root -o DPkg::Options::=--root=$ROOTFS"
    apt-get $APT_OPTS update
    apt-get $APT_OPTS install -y default-jre-headless apache2-utils
else
    cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
    chroot "$ROOTFS" apt-get update
    chroot "$ROOTFS" apt-get install -y default-jre-headless apache2-utils
fi
jre_install_cleanup
trap - EXIT

if [ "$IS_RECONCILE" -eq 0 ]; then
    # 4. tomcat/guacd system users, inside the container's OWN /etc/passwd - ONE-TIME ONLY,
    #    useradd/usermod are not idempotent and fail outright on a second run; the accounts already
    #    exist from the original bootstrap on every reconcile call. Plain `useradd`/`usermod` (not
    #    Debian's `adduser` wrapper), same portability reasoning as setup-sudo-account.sh's own
    #    useradd switch - AND invoked by absolute path (/usr/sbin/useradd, not a bare command
    #    name). Confirmed live: on a host whose own sudoers `secure_path` doesn't include /usr/sbin
    #    (SteamOS's own default has no sbin directories at all), a bare `chroot "$ROOTFS" useradd
    #    ...` fails even though useradd genuinely exists at /usr/sbin/useradd inside that rootfs -
    #    chroot still does a PATH lookup using whatever PATH it inherited from the *caller*, not
    #    the target rootfs.
    chroot "$ROOTFS" /usr/sbin/useradd -r -d /opt/tomcat9 -s /usr/sbin/nologin -U tomcat
    chroot "$ROOTFS" /usr/sbin/usermod -aG shadow tomcat
    chroot "$ROOTFS" /usr/sbin/useradd -r -d /nonexistent -M -U guacd

    # 5. Tomcat itself - same extraction postinst used to do against the HOST's own /opt/tomcat9,
    #    retargeted at the container's rootfs. ONE-TIME ONLY (unlike the WARs/unit file below) -
    #    see this file's own header comment for why: conf/ under this same tree is plausible admin
    #    customization surface, so a Tomcat *version* bump still needs a full reinstall.
    mkdir -p "$ROOTFS/opt/tomcat9"
    tar -xzf "$TOMCAT_TARBALL" -C "$ROOTFS/opt/tomcat9" --strip-components=1
    rm -rf "$ROOTFS/opt/tomcat9/webapps/manager" "$ROOTFS/opt/tomcat9/webapps/host-manager" \
           "$ROOTFS/opt/tomcat9/webapps/examples" "$ROOTFS/opt/tomcat9/webapps/docs" \
           "$ROOTFS/opt/tomcat9/webapps/ROOT"
    chroot "$ROOTFS" chown -R tomcat:tomcat /opt/tomcat9
fi

# 6. tomcat9.service + the four bundled WARs - always reconciled, see refresh_tomcat_unit()/
#    refresh_wars()'s own comments above for why these two specifically are safe/desirable to
#    always overwrite.
refresh_tomcat_unit
refresh_wars

# 7. guacd's bundle/service/properties - always reconciled, see refresh_guacd()'s own comment.
refresh_guacd

# 8. Shared /etc/nspawnmgr dirs the app writes into at runtime - idempotent either way (mkdir -p +
#    chown/chmod), so this just always runs regardless of IS_RECONCILE, same layout/permissions
#    postinst creates on the host today.
mkdir -p "$ROOTFS/etc/nspawnmgr/auth-live" "$ROOTFS/etc/nspawnmgr/db-config"
chroot "$ROOTFS" chown tomcat:tomcat /etc/nspawnmgr/auth-live /etc/nspawnmgr/db-config
chmod 750 "$ROOTFS/etc/nspawnmgr/auth-live" "$ROOTFS/etc/nspawnmgr/db-config"

# 8b. TranslationService's own lang/ dir - always reconciled from the package's own bundled copy
#     (/usr/share/nspawnmgr/lang, see nspawnmgr-deb/pom.xml), same posture as refresh_wars() above:
#     a package upgrade should deliver newer/fixed translations. An admin's own hand-added file for
#     an extra language lives ONLY inside the machine's rootfs (never touched by the host-side
#     package), so it survives this - only the shipped *.json files here get overwritten, nothing
#     under this directory is ever deleted.
mkdir -p "$ROOTFS/etc/nspawnmgr/lang"
cp /usr/share/nspawnmgr/lang/*.json "$ROOTFS/etc/nspawnmgr/lang/"
chroot "$ROOTFS" chown -R tomcat:tomcat /etc/nspawnmgr/lang
chmod 750 "$ROOTFS/etc/nspawnmgr/lang"

# 9. Pick the host-side port to forward into the container's Tomcat - see pick_host_port()'s own
#    comment above for why this is preserved (not re-searched) on reconcile.
HOST_PORT="$(pick_host_port)"
if [ "$HOST_PORT" -ne 8080 ]; then
    echo "nspawnmgr-bootstrap-app-machine.sh: using host port $HOST_PORT (8080 is taken, or this is the machine's existing mapping)."
fi

# 10. The SSH-back credentials - always reconciled, see refresh_credential_file()'s own comment.
refresh_credential_file

# 11. .nspawn settings - always reconciled (using the preserved/searched HOST_PORT from step 9),
#     see write_nspawn_settings()'s own comment.
write_nspawn_settings

# 12. Enable the container's own services - unit-file symlink manipulation only, no running init
#     needed or wanted; also idempotent, a no-op if already enabled. Uses `systemctl --root=`
#     (run directly on the host, not chrooted) rather than `chroot "$ROOTFS" systemctl enable`,
#     which is what nspawnmgr-create-debian-template.sh's own "systemctl enable ssh" precedent
#     still uses - that one only ever runs against a virgin, never-booted extraction. Confirmed
#     live: on RECONCILE specifically, chrooting into a rootfs that has genuinely been booted as a
#     real systemd-nspawn machine before (unlike a fresh extraction) can make a chrooted
#     `systemctl enable` try to reach a D-Bus "machine transport" instead of falling back to pure
#     offline unit-file manipulation - "Failed to connect to system scope bus via machine
#     transport: No such file or directory". `--root=` is the purpose-built systemd flag for
#     exactly this "manipulate a foreign root's unit files, no bus at all" case, so it can't hit
#     any transport error by construction, regardless of the target rootfs's own boot history.
#     Mark this rootfs as fully set up (only matters the first time - already set on every
#     reconcile call), and (re)start the machine.
systemctl --root="$ROOTFS" enable tomcat9
systemctl --root="$ROOTFS" enable guacd
touch "$BOOTSTRAP_MARKER"
echo "nspawnmgr-bootstrap-app-machine.sh: starting $MACHINE_NAME..."
machinectl start "$MACHINE_NAME"

echo "nspawnmgr-bootstrap-app-machine.sh: done. nspawnmgr is now running from its own machine ($MACHINE_NAME)."
echo "nspawnmgr-bootstrap-app-machine.sh: browse to http://<this host's address>:${HOST_PORT}/ to continue setup."
