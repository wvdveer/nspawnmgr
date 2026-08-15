#!/bin/sh
# Bootstraps nspawnmgr's own self-hosted "nspawnmgr" machine at .deb-install time. Called directly
# from postinst - already root, so (unlike every other privileged-scripts/*.sh here) this never
# goes through an SSH+sudo round-trip; it just builds a container's rootfs the same way postinst
# used to build the HOST's own /opt/tomcat9 and /opt/guacd-bundle, retargeted at
# /var/lib/machines/nspawnmgr instead. Bakes debian-minimal first if it doesn't exist yet (same
# tarball "Set up debian-minimal" on /admin/templates would produce - see
# SelfHostedMachineDiscoveryService for how it later gets registered as a real Template row), then
# clones it, then - while it's still just an extracted rootfs, not yet booted, using the same
# host-apt-pointed-at-a-foreign-root technique nspawnmgr-create-debian-template.sh already uses -
# installs a JRE, Tomcat, the four bundled WARs, and guacd, all inside that rootfs. Finally writes a
# rewritten copy of nspawnmgr.env (SSH_HOST pointed at nspawnbr0's own address instead of
# 127.0.0.1) plus the SSH private key, so nspawnmgr.war can reach back out to the host's
# nspawnmgr_exec account once it boots for every privileged operation - exactly the same mechanism
# it already uses today, just retargeted from "SSH to itself" to "SSH to its own host".
#
# No arguments - fully opinionated per the design doc: the machine is always named "nspawnmgr".
# Idempotent: does nothing if /var/lib/machines/nspawnmgr already exists (an upgrade re-run of
# postinst should never re-bake/re-clone/restart a machine an admin may have since customized).
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

BOOTSTRAP_MARKER="$ROOTFS/etc/nspawnmgr/.bootstrap-complete"
if [ -e "$ROOTFS" ]; then
    if [ -e "$BOOTSTRAP_MARKER" ]; then
        echo "nspawnmgr-bootstrap-app-machine.sh: $ROOTFS already exists and is fully set up — nothing to do."
        exit 0
    fi
    # Exists but never finished (confirmed live: a package's postinst can abort this script
    # partway through, e.g. openjdk-17-jre-headless needing /proc mounted - see the JRE install
    # step below) - none of the steps below are safely re-runnable against a half-populated
    # rootfs (adduser/useradd in particular fail outright on a second run, they're not
    # idempotent), so start over cleanly rather than trying to resume.
    echo "nspawnmgr-bootstrap-app-machine.sh: $ROOTFS exists but looks like an incomplete prior attempt - removing it and starting over."
    machinectl remove "$MACHINE_NAME" 2>/dev/null || true
    rm -rf "$ROOTFS"
    rm -f "/etc/systemd/nspawn/$MACHINE_NAME.nspawn"
fi

for cmd in chroot mount umount machinectl systemctl tar; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Required command '$cmd' not found." >&2
        exit 1
    fi
done

# 1. Get debian-minimal in place if it doesn't exist yet. Left in TEMPLATES_DIR (not a throwaway
#    temp path) so it's exactly the same tarball the app's own "Set up debian-minimal" button
#    would have produced, and SelfHostedMachineDiscoveryService / the admin's own Templates page
#    can find and register it once the app boots for real.
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

# 2. Clone it into place - same machinectl import-tar nspawnmgr-clone-template.sh itself uses, so
#    this stays a real, ordinary machine indistinguishable from one created through the app later.
echo "nspawnmgr-bootstrap-app-machine.sh: cloning debian-minimal into $ROOTFS..."
"$PRIVILEGED_SCRIPTS_DIR/nspawnmgr-clone-template.sh" "$DEBIAN_TEMPLATE" "$ROOTFS"

# 3. JRE + apache2-utils (rotatelogs, which tomcat9.service's own ExecStart pipes catalina.sh's
#    output through for log rotation - previously always a host-level apt Depends; now that Tomcat
#    runs inside this container instead of on the host, nothing installs it there unless this script
#    does) - same host-apt-pointed-at-a-foreign-root / chroot-fallback technique
#    nspawnmgr-create-debian-template.sh already uses for openssh-server. openssh-server's own
#    postinst never needed /proc/dev/sys/run bind-mounted for that script's own host-apt branch to
#    work - openjdk-17-jre-headless's own postinst is different: confirmed live, `apt-get -o Dir=`
#    still chroots into $ROOTFS for the dpkg *configure* step via the `-o DPkg::Options::=--root=`
#    flag (same as the plain host-apt path always has), and openjdk's postinst runs `java` itself
#    as part of its own certificate-store setup, which fails outright ("the java command requires
#    a mounted proc fs (/proc)") without a real /proc inside that chroot - openssh-server's own
#    postinst just never happened to touch anything needing one. Mount all four unconditionally in
#    the host-apt branch too, not just the chroot-fallback one, rather than assuming future
#    packages' own postinst scripts won't hit the same thing.
export DEBIAN_FRONTEND=noninteractive
echo "nspawnmgr-bootstrap-app-machine.sh: installing a JRE into $ROOTFS..."
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

# 4. tomcat/guacd system users, inside the container's OWN /etc/passwd - via chroot, same
#    "systemctl enable works chrooted with no running init" precedent
#    nspawnmgr-create-debian-template.sh already established for adduser/usermod too.
chroot "$ROOTFS" adduser --system --home /opt/tomcat9 --shell /usr/sbin/nologin --group tomcat
chroot "$ROOTFS" usermod -aG shadow tomcat
chroot "$ROOTFS" adduser --system --home /nonexistent --no-create-home --group guacd

# 5. Tomcat itself - same extraction postinst used to do against the HOST's own /opt/tomcat9,
#    retargeted at the container's rootfs. No upgrade-preservation branch needed here (unlike
#    postinst's own copy) - this only ever runs once, against a rootfs that doesn't exist yet.
mkdir -p "$ROOTFS/opt/tomcat9"
tar -xzf "$TOMCAT_TARBALL" -C "$ROOTFS/opt/tomcat9" --strip-components=1
rm -rf "$ROOTFS/opt/tomcat9/webapps/manager" "$ROOTFS/opt/tomcat9/webapps/host-manager" \
       "$ROOTFS/opt/tomcat9/webapps/examples" "$ROOTFS/opt/tomcat9/webapps/docs" \
       "$ROOTFS/opt/tomcat9/webapps/ROOT"
chroot "$ROOTFS" chown -R tomcat:tomcat /opt/tomcat9
cp /usr/share/nspawnmgr/tomcat9.service "$ROOTFS/etc/systemd/system/tomcat9.service"
mkdir -p "$ROOTFS/etc/systemd/system/tomcat9.service.d"
cat > "$ROOTFS/etc/systemd/system/tomcat9.service.d/nspawnmgr.conf" <<EOF
[Service]
EnvironmentFile=/etc/nspawnmgr/nspawnmgr.env
EOF

# 6. The four bundled WARs, deployed via context descriptors pointing at a location inside the
#    container - same convention postinst uses on the host, so they survive re-extraction the same
#    way (not that this script ever re-extracts; kept for consistency with the host-side pattern).
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

# 7. guacd + GUACAMOLE_HOME - guacd moves into this container too (it's not on the "stays on host"
#    list), co-located with guacamole.war in the same network namespace, so the seeded
#    guacd-hostname stays "localhost" unchanged. install-guacamole-auth-jdbc.sh/
#    install-guacamole-jdbc-drivers.sh already support --target-dir, so they're reused as-is,
#    pointed at the container's own rootfs; their own best-effort `chown tomcat` fails harmlessly
#    (the host has no "tomcat" user in this architecture) and is fixed up by the chroot chown pass
#    below, which resolves "tomcat" against the container's OWN /etc/passwd instead.
mkdir -p "$ROOTFS/opt/guacd-bundle"
tar -xzf /usr/share/nspawnmgr/guacd-bundle.tar.gz -C "$ROOTFS/opt"
chroot "$ROOTFS" chown -R root:root /opt/guacd-bundle
find "$ROOTFS/opt/guacd-bundle" -type d -exec chmod 755 {} +
find "$ROOTFS/opt/guacd-bundle" -type f -exec chmod 644 {} +
chmod 755 "$ROOTFS/opt/guacd-bundle/sbin/guacd"
cp /usr/share/nspawnmgr/guacd.service "$ROOTFS/etc/systemd/system/guacd.service"
mkdir -p "$ROOTFS/etc/guacamole"
cat > "$ROOTFS/etc/guacamole/guacamole.properties" <<EOF
guacd-hostname: localhost
guacd-port: 4822
EOF
"$(dirname "$0")/../install-guacamole-auth-jdbc.sh" --target-dir "$ROOTFS/etc/guacamole/guacamole-auth-jdbc" \
    || true
"$(dirname "$0")/../install-guacamole-jdbc-drivers.sh" --target-dir "$ROOTFS/etc/guacamole/lib" \
    || echo "nspawnmgr-bootstrap-app-machine.sh: install-guacamole-jdbc-drivers.sh failed — rerun manually against $ROOTFS/etc/guacamole/lib." >&2
chroot "$ROOTFS" chown -R tomcat:tomcat /etc/guacamole

# 8. Shared /etc/nspawnmgr dirs the app writes into at runtime - same layout/permissions postinst
#    creates on the host today.
mkdir -p "$ROOTFS/etc/nspawnmgr/auth-live" "$ROOTFS/etc/nspawnmgr/db-config"
chroot "$ROOTFS" chown tomcat:tomcat /etc/nspawnmgr/auth-live /etc/nspawnmgr/db-config
chmod 750 "$ROOTFS/etc/nspawnmgr/auth-live" "$ROOTFS/etc/nspawnmgr/db-config"

# 9. Pick the host-side port to forward into the container's Tomcat. 8080 first (today's
#    convention), incrementing past anything already listening - `ss` (iproute2, already relied on
#    for the bridge-ready check above) lists real bound TCP sockets regardless of which local
#    address they're bound to, so this catches "something else is already using 8080" whether that
#    something is bound to 0.0.0.0, a specific address, or [::].
HOST_PORT=8080
while ss -Htln 2>/dev/null | awk '{print $4}' | grep -qE ":${HOST_PORT}\$"; do
    HOST_PORT=$((HOST_PORT + 1))
done
if [ "$HOST_PORT" -ne 8080 ]; then
    echo "nspawnmgr-bootstrap-app-machine.sh: port 8080 is already in use on this host — using $HOST_PORT instead."
fi

# 10. The SSH-back credentials - a rewritten copy of the host's own nspawnmgr.env with SSH_HOST
#     changed, from 127.0.0.1 (today's "SSH to itself" default) to nspawnbr0's own fixed address
#     (see 70-nspawnmgr-bridge.network's Address=10.100.0.1/24); HOST_PUBLIC_ADDRESS changed the
#     same way - it's what guacd (now living inside this container too) dials to reach a custom
#     port mapping's *host*-forwarded port (ordinary SSH/RDP/VNC always dial a container's own
#     internal address directly, bypassing this entirely - see ContainerPortMappingService for the
#     one case that doesn't), and those forwards are bound on the bare host's own network stack,
#     not any particular container's, so guacd needs the bridge address to reach them now, the same
#     reason SSH_HOST changes. Every other key (NSPAWN_PRIVILEGED_SCRIPTS_DIR included) names a path
#     on the SSH *target* (the host), not the local filesystem, so those stay correct unchanged
#     regardless of where the JVM making the SSH call itself lives.
#
#     USER_ID_URL and AUTH_LOGIN_URL need DIFFERENT treatment, not the same port-only substitution -
#     confirmed live this bit a real install (endless login loop, nspawnmgr.war -> auth.war -> back
#     to nspawnmgr.war): AUTH_LOGIN_URL is browser-facing (the redirect a real browser follows), so
#     it correctly needs the external DETECTED_HOSTNAME:HOST_PORT the browser can actually reach.
#     USER_ID_URL is a server-to-server call - nspawnmgr.war calling auth.war's own /auth/userinfo -
#     and since self-hosting put both WARs in the exact same Tomcat/JVM inside this container, that
#     call has no reason to leave the container at all. Routing it back out through the veth, the
#     host's Port= forward, and back in (hairpin NAT) is needlessly fragile - many bridge/firewall
#     setups don't support hairpinning to begin with - and breaks outright whenever HOST_PORT != 8080
#     (the container's own Tomcat always listens on 8080 internally, regardless of which host port
#     got forwarded to it). Rewritten unconditionally to the container's own loopback instead.
sed -e 's/^SSH_HOST=.*/SSH_HOST=10.100.0.1/' \
    -e 's/^HOST_PUBLIC_ADDRESS=.*/HOST_PUBLIC_ADDRESS=10.100.0.1/' \
    -e 's|^USER_ID_URL=.*|USER_ID_URL=http://localhost:8080/auth/userinfo|' \
    -e "s/^\(AUTH_LOGIN_URL=http:\/\/[^:]*\):8080/\1:${HOST_PORT}/" \
    /etc/nspawnmgr/nspawnmgr.env > "$ROOTFS/etc/nspawnmgr/nspawnmgr.env"
cp /etc/nspawnmgr/ssh_id_ed25519 "$ROOTFS/etc/nspawnmgr/ssh_id_ed25519"
chroot "$ROOTFS" chown root:tomcat /etc/nspawnmgr/nspawnmgr.env /etc/nspawnmgr/ssh_id_ed25519
chmod 640 "$ROOTFS/etc/nspawnmgr/nspawnmgr.env" "$ROOTFS/etc/nspawnmgr/ssh_id_ed25519"

# 11. .nspawn settings - PrivateUsers=identity and Bridge=nspawnbr0 like every other managed
#     container (see NspawnSettingsRenderer for why PrivateUsers is pinned to identity rather than
#     left at systemd's own default), plus a Port= forward from the chosen host port to the
#     container's own :8080, so this host's own address keeps serving nspawnmgr on a predictable
#     port exactly like today's non-self-hosted deployment, even though Tomcat itself is no longer
#     bound to the host's own network namespace at all - same Port= mechanism
#     ContainerPortMappingService/NspawnSettingsRenderer use for ordinary managed containers, just
#     written directly rather than through the app (which isn't running yet at this point in the
#     install).
mkdir -p /etc/systemd/nspawn
cat > "/etc/systemd/nspawn/$MACHINE_NAME.nspawn" <<EOF
[Exec]
PrivateUsers=identity
[Network]
Bridge=nspawnbr0
Port=tcp:${HOST_PORT}:8080
EOF

# 12. Enable the container's own services (unit-file symlink manipulation only - no running init
#     needed, matching nspawnmgr-create-debian-template.sh's own "systemctl enable ssh" precedent),
#     mark this rootfs as fully set up (see the BOOTSTRAP_MARKER check at the top of this script),
#     and start the machine.
chroot "$ROOTFS" systemctl enable tomcat9
chroot "$ROOTFS" systemctl enable guacd
touch "$BOOTSTRAP_MARKER"
echo "nspawnmgr-bootstrap-app-machine.sh: starting $MACHINE_NAME..."
machinectl start "$MACHINE_NAME"

echo "nspawnmgr-bootstrap-app-machine.sh: done. nspawnmgr is now running from its own machine ($MACHINE_NAME)."
echo "nspawnmgr-bootstrap-app-machine.sh: browse to http://<this host's address>:${HOST_PORT}/ to continue setup."
