#!/bin/sh
# Sets up the nspawnmgr_exec sudo-capable SSH account: system user, stored password (first-run
# only), SSH keypair, sudoers grant, and an sshd PasswordAuthentication carve-out if needed. Also
# detects this machine's real hostname into HOST_EXTERNAL_HOSTNAME (first-run only, backfilled on
# upgrade if missing) — unrelated to the sudo account itself, but $ENV_FILE is nspawnmgr's one
# shared static-config file either way. See docs/administrator-guide.md, "The sudo-capable SSH
# account" and §8 "Hostname and the shared session cookie", for the full rationale.
#
# Runnable two ways:
#   1. Standalone, directly from a repo checkout — e.g. to prep a real nspawn host for
#      tools/scripts/start-real-stack.sh without building/installing the .deb at all:
#         sudo packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh
#      (auto-detects the sibling privileged-scripts/ and debian/nspawnmgr.sudoers next to this
#      script's own checkout location — no flags needed.)
#   2. As part of `dpkg -i nspawnmgr*.deb` — debian/postinst calls this same script's installed
#      copy (/usr/lib/nspawnmgr/setup-sudo-account.sh) after dpkg has already unpacked the wrapper
#      scripts and sudoers file to their final locations, so it skips the copy steps and just
#      validates what's already there.
#
# Usage: setup-sudo-account.sh [--privileged-scripts-src DIR] [--sudoers-src FILE]
set -e

SERVICE_USER="nspawnmgr_exec"
SERVICE_HOME="/var/lib/nspawnmgr_exec"
ETC_DIR="/etc/nspawnmgr"
ENV_FILE="$ETC_DIR/nspawnmgr.env"
KEY_FILE="$ETC_DIR/ssh_id_ed25519"
TARGET_SCRIPTS_DIR="/usr/lib/nspawnmgr/privileged"
SUDOERS_FILE="/etc/sudoers.d/nspawnmgr_exec"
SSHD_DROPIN="/etc/ssh/sshd_config.d/nspawnmgr.conf"

PRIVILEGED_SCRIPTS_SRC=""
SUDOERS_SRC=""

while [ $# -gt 0 ]; do
    case "$1" in
        --privileged-scripts-src) PRIVILEGED_SCRIPTS_SRC="$2"; shift 2 ;;
        --sudoers-src) SUDOERS_SRC="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

# Auto-detect repo-checkout siblings if not explicitly given, and they exist. When this script
# runs from its installed (.deb) copy instead, these siblings don't exist at this relative path,
# so it correctly falls back to "assume already installed by dpkg" (skip-copy) mode.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "$PRIVILEGED_SCRIPTS_SRC" ] && [ -d "$SCRIPT_DIR/../privileged-scripts" ]; then
    PRIVILEGED_SCRIPTS_SRC="$SCRIPT_DIR/../privileged-scripts"
fi
if [ -z "$SUDOERS_SRC" ] && [ -f "$SCRIPT_DIR/../debian/nspawnmgr.sudoers" ]; then
    SUDOERS_SRC="$SCRIPT_DIR/../debian/nspawnmgr.sudoers"
fi

if [ "$(id -u)" -ne 0 ]; then
    echo "setup-sudo-account.sh must be run as root." >&2
    exit 1
fi

mkdir -p "$ETC_DIR"

# 1. Service account — idempotent. Real home dir + shell since it's an SSH login target for
#    RealContainerCliExecutor/RealContainerFilesystemProvisioner/RealContainerOutboundAccessManager.
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
    adduser --system --home "$SERVICE_HOME" --shell /bin/bash --group "$SERVICE_USER"
fi

# 2. Wrapper scripts — only copied in if a source was given (standalone/manual use); when run
#    as part of `dpkg -i`, dpkg has already unpacked these to $TARGET_SCRIPTS_DIR.
if [ -n "$PRIVILEGED_SCRIPTS_SRC" ]; then
    mkdir -p "$TARGET_SCRIPTS_DIR"
    cp "$PRIVILEGED_SCRIPTS_SRC"/*.sh "$TARGET_SCRIPTS_DIR/"
    chmod 750 "$TARGET_SCRIPTS_DIR"/*.sh
    chown root:root "$TARGET_SCRIPTS_DIR"/*.sh
    echo "setup-sudo-account.sh: copied wrapper scripts from $PRIVILEGED_SCRIPTS_SRC into $TARGET_SCRIPTS_DIR."
fi

# 3. Stored sudo/SSH password — first-run only, so later edits (including blanking SSH_PASSWORD to
#    switch to admin-approval mode) survive a re-run. Deliberately NOT a dpkg conffile: conffiles
#    ship with fixed content, but this file's content (a freshly generated secret) doesn't exist
#    until this script runs.
if [ ! -f "$ENV_FILE" ]; then
    GENERATED_PASSWORD="$(openssl rand -base64 24)"
    echo "$SERVICE_USER:$GENERATED_PASSWORD" | chpasswd
    # Best-effort real hostname for HOST_EXTERNAL_HOSTNAME (nspawnmgr.host.external-hostname) — the
    # outward-facing name the /admin/settings URL "Refresh" buttons use, not the SSH_HOST above
    # (always 127.0.0.1 — that's the separate sudo-account transport, unrelated to this). `hostname
    # -f` needs the FQDN to resolve (DNS/hosts), which isn't guaranteed on a fresh box; fall back to
    # the short hostname, which always works.
    DETECTED_HOSTNAME="$(hostname -f 2>/dev/null || hostname)"
    # nspawnmgr.host.public-address (HOST_PUBLIC_ADDRESS) — what guacd/ContainerReadinessPollingService
    # dial to reach *into* a container's forwarded port, not the same thing as HOST_EXTERNAL_HOSTNAME
    # above (that's for outside-facing URLs). A literal 127.0.0.1 (application.yml's own fallback
    # default) breaks on any host whose NAT won't hairpin loopback-destined traffic back to a
    # container — confirmed live: systemd-networkd's own io.systemd.nat table excludes 127.0.0.0/8
    # from its `output` hairpin chain by design, so a self-connection to 127.0.0.1:<port> never
    # reaches the container even though the forward itself is configured correctly. Deliberately NOT
    # `ip route get 1.1.1.1` (what source address would reach the internet) — on a host whose
    # default route for general internet traffic goes over a VPN tunnel, that picks the *tunnel's*
    # address instead of a normal LAN address, confirmed live on a host with a tun-pure VPN
    # interface. List this host's own global-scope addresses directly instead, skipping
    # virtual/tunnel-style interfaces; `hostname -I` is the fallback if that finds nothing.
    DETECTED_PUBLIC_ADDRESS="$(ip -4 -o addr show scope global 2>/dev/null | awk '
        { iface=$2; addr="";
          for (i=1;i<=NF;i++) if ($i=="inet") { addr=$(i+1); sub(/\/.*/,"",addr) }
          if (addr != "" && iface !~ /^(tun|tap|wg|ppp|docker|veth|ve-|br-)/) { print addr; exit } }')"
    if [ -z "$DETECTED_PUBLIC_ADDRESS" ]; then
        DETECTED_PUBLIC_ADDRESS="$(hostname -I 2>/dev/null | awk '{print $1}')"
    fi
    DETECTED_PUBLIC_ADDRESS="${DETECTED_PUBLIC_ADDRESS:-127.0.0.1}"
    # nspawnmgr.crypto.secret-key (APP_SECRET_KEY) — required at boot (SecretEncryptionService
    # throws IllegalStateException if unset), same as the sudo password above, so generate it here
    # too rather than leaving a required setting for the admin to discover only after the app
    # 404s post-install. Matches docs/administrator-guide.md §9's own `openssl rand -base64 32`.
    GENERATED_SECRET_KEY="$(openssl rand -base64 32)"
    # USER_ID_URL/AUTH_LOGIN_URL — both point at auth.war, which (unlike guacamole.war) this .deb
    # always deploys itself at a fixed /auth path in the same bundled Tomcat instance. Left blank,
    # nspawnmgr shows its own static "login required" page instead of ever reaching auth.war at all
    # (see docs/administrator-guide.md §8) — a fresh install otherwise never exercises the
    # auth.war this same package just deployed. Plain http/8080, matching this .deb's own
    # unconfigured defaults (Tomcat's stock port, no HTTPS connector) - update both (or use
    # /admin/settings' "Refresh hostname/port/protocol" buttons) after changing either.
    umask 077
    cat > "$ENV_FILE" <<EOF
SSH_HOST=127.0.0.1
SSH_USERNAME=$SERVICE_USER
SSH_PASSWORD=$GENERATED_PASSWORD
SSH_PRIVATE_KEY_PATH=$KEY_FILE
NSPAWN_PRIVILEGED_SCRIPTS_DIR=$TARGET_SCRIPTS_DIR
HOST_EXTERNAL_HOSTNAME=$DETECTED_HOSTNAME
HOST_PUBLIC_ADDRESS=$DETECTED_PUBLIC_ADDRESS
APP_SECRET_KEY=$GENERATED_SECRET_KEY
USER_ID_URL=http://$DETECTED_HOSTNAME:8080/auth/userinfo
AUTH_LOGIN_URL=http://$DETECTED_HOSTNAME:8080/auth/login
SPRING_PROFILES_ACTIVE=prod
EOF
    unset GENERATED_PASSWORD GENERATED_SECRET_KEY
    chmod 640 "$ENV_FILE"
    chown root:"$SERVICE_USER" "$ENV_FILE"
    echo "setup-sudo-account.sh: generated a fresh sudo/SSH password for $SERVICE_USER in $ENV_FILE."
    echo "setup-sudo-account.sh: generated a fresh APP_SECRET_KEY in $ENV_FILE."
    echo "setup-sudo-account.sh: pointed USER_ID_URL/AUTH_LOGIN_URL at this host's bundled auth.war in $ENV_FILE."
    echo "setup-sudo-account.sh: detected outward-facing hostname '$DETECTED_HOSTNAME' — set HOST_EXTERNAL_HOSTNAME in $ENV_FILE (override there, or in /admin/settings, if that's wrong)."
    echo "setup-sudo-account.sh: detected this host's address as '$DETECTED_PUBLIC_ADDRESS' — set HOST_PUBLIC_ADDRESS in $ENV_FILE (override there, or in /admin/settings, if that's wrong)."
    echo "setup-sudo-account.sh: not using the .deb/systemd drop-in? source $ENV_FILE into nspawnmgr's environment yourself."
    echo "setup-sudo-account.sh: to switch to admin-approval mode instead, blank SSH_PASSWORD in $ENV_FILE."
else
    echo "setup-sudo-account.sh: $ENV_FILE already exists, leaving it untouched."
    # Backfill only: an upgrade from a version that predates HOST_EXTERNAL_HOSTNAME/APP_SECRET_KEY/
    # USER_ID_URL/AUTH_LOGIN_URL would otherwise never get a value at all, since the block above
    # only runs on a truly fresh install. Never overwrites an existing line — an admin may have
    # deliberately customized it since (or, for APP_SECRET_KEY, already has real encrypted data at
    # rest keyed to it).
    if ! grep -q '^HOST_EXTERNAL_HOSTNAME=' "$ENV_FILE"; then
        DETECTED_HOSTNAME="$(hostname -f 2>/dev/null || hostname)"
        echo "HOST_EXTERNAL_HOSTNAME=$DETECTED_HOSTNAME" >> "$ENV_FILE"
        echo "setup-sudo-account.sh: backfilled HOST_EXTERNAL_HOSTNAME=$DETECTED_HOSTNAME into $ENV_FILE (upgrade from an older version)."
    fi
    if ! grep -q '^HOST_PUBLIC_ADDRESS=' "$ENV_FILE"; then
        DETECTED_PUBLIC_ADDRESS="$(ip -4 -o addr show scope global 2>/dev/null | awk '
            { iface=$2; addr="";
              for (i=1;i<=NF;i++) if ($i=="inet") { addr=$(i+1); sub(/\/.*/,"",addr) }
              if (addr != "" && iface !~ /^(tun|tap|wg|ppp|docker|veth|ve-|br-)/) { print addr; exit } }')"
        if [ -z "$DETECTED_PUBLIC_ADDRESS" ]; then
            DETECTED_PUBLIC_ADDRESS="$(hostname -I 2>/dev/null | awk '{print $1}')"
        fi
        DETECTED_PUBLIC_ADDRESS="${DETECTED_PUBLIC_ADDRESS:-127.0.0.1}"
        echo "HOST_PUBLIC_ADDRESS=$DETECTED_PUBLIC_ADDRESS" >> "$ENV_FILE"
        echo "setup-sudo-account.sh: backfilled HOST_PUBLIC_ADDRESS=$DETECTED_PUBLIC_ADDRESS into $ENV_FILE (upgrade from an older version — see this script's own comment for why the previous 127.0.0.1 default can silently break SSH/RDP into a container on some hosts)."
    fi
    if ! grep -q '^APP_SECRET_KEY=' "$ENV_FILE"; then
        GENERATED_SECRET_KEY="$(openssl rand -base64 32)"
        echo "APP_SECRET_KEY=$GENERATED_SECRET_KEY" >> "$ENV_FILE"
        unset GENERATED_SECRET_KEY
        echo "setup-sudo-account.sh: backfilled a fresh APP_SECRET_KEY into $ENV_FILE (upgrade from an older version)."
    fi
    if ! grep -q '^USER_ID_URL=' "$ENV_FILE" && ! grep -q '^AUTH_LOGIN_URL=' "$ENV_FILE"; then
        DETECTED_HOSTNAME="$(hostname -f 2>/dev/null || hostname)"
        cat >> "$ENV_FILE" <<EOF
USER_ID_URL=http://$DETECTED_HOSTNAME:8080/auth/userinfo
AUTH_LOGIN_URL=http://$DETECTED_HOSTNAME:8080/auth/login
EOF
        echo "setup-sudo-account.sh: backfilled USER_ID_URL/AUTH_LOGIN_URL pointing at this host's bundled auth.war into $ENV_FILE (upgrade from an older version)."
    fi
fi

# 4. SSH keypair — generated unconditionally, regardless of mode, so switching to admin-approval
#    mode later doesn't also require setting up transport auth from scratch (see
#    SshRemoteExecutor.connect() and SshPropertiesValidator).
if [ ! -f "$KEY_FILE" ]; then
    ssh-keygen -t ed25519 -N '' -f "$KEY_FILE" -C nspawnmgr >/dev/null
fi
chown root:root "$KEY_FILE" "$KEY_FILE.pub"
chmod 600 "$KEY_FILE"
chmod 644 "$KEY_FILE.pub"
# SshRemoteExecutor.connect() opens $KEY_FILE directly from inside Tomcat's own JVM process (the
# tomcat user, not root) on every SSH call, since stored-secret mode always has
# SSH_PRIVATE_KEY_PATH set alongside SSH_PASSWORD - root:root mode 600 above leaves tomcat with no
# read access at all. Best-effort here (the tomcat user/group may not exist yet if this is being
# run standalone before Tomcat's own setup, e.g. following docs/administrator-guide.md §3 before
# §6) - the .deb's postinst does this unconditionally afterward, once tomcat is guaranteed to
# exist; a manual (non-.deb) install needs to re-run this chown by hand once Tomcat is set up, if
# this script ran before that point.
if id tomcat >/dev/null 2>&1; then
    chown root:tomcat "$KEY_FILE"
    chmod 640 "$KEY_FILE"
fi

install -d -m 700 -o "$SERVICE_USER" -g "$SERVICE_USER" "$SERVICE_HOME/.ssh"
touch "$SERVICE_HOME/.ssh/authorized_keys"
if ! grep -qxF "$(cat "$KEY_FILE.pub")" "$SERVICE_HOME/.ssh/authorized_keys"; then
    cat "$KEY_FILE.pub" >> "$SERVICE_HOME/.ssh/authorized_keys"
fi
chmod 600 "$SERVICE_HOME/.ssh/authorized_keys"
chown "$SERVICE_USER:$SERVICE_USER" "$SERVICE_HOME/.ssh/authorized_keys"

# 5. Sudoers — copy in if a source was given, then always validate whatever ends up at
#    $SUDOERS_FILE before trusting it (cheap insurance against a bad file locking out sudo).
if [ -n "$SUDOERS_SRC" ]; then
    install -m 440 -o root -g root "$SUDOERS_SRC" "$SUDOERS_FILE"
fi
if ! visudo -cf "$SUDOERS_FILE" >/dev/null 2>&1; then
    echo "setup-sudo-account.sh: $SUDOERS_FILE failed visudo validation — removing it. Sudo access" >&2
    echo "setup-sudo-account.sh: for $SERVICE_USER will NOT work until this is fixed." >&2
    rm -f "$SUDOERS_FILE"
    exit 1
fi
echo "setup-sudo-account.sh: $SUDOERS_FILE is in place and valid."

# 6. If sshd is configured to reject password auth globally (a common hardening default), carve
#    out an exception just for this account — otherwise stored-secret mode silently can't
#    authenticate at all, with a failure mode that doesn't obviously point back at sshd_config.
if [ -d /etc/ssh/sshd_config.d ] && grep -Eqs '^\s*PasswordAuthentication\s+no' /etc/ssh/sshd_config /etc/ssh/sshd_config.d/*.conf 2>/dev/null; then
    cat > "$SSHD_DROPIN" <<EOF
# Installed by nspawnmgr's setup-sudo-account.sh: password auth is disabled globally on this host,
# but $SERVICE_USER needs it (or SSH_PRIVATE_KEY_PATH set instead — see docs/administrator-guide.md).
Match User $SERVICE_USER
    PasswordAuthentication yes
EOF
    systemctl reload ssh 2>/dev/null || systemctl reload sshd 2>/dev/null || true
    echo "setup-sudo-account.sh: global PasswordAuthentication no detected — added $SSHD_DROPIN for $SERVICE_USER."
fi

echo "setup-sudo-account.sh: done."
