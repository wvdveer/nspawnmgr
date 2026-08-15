#!/bin/sh
# Provisions a self-hosted Debian database machine for the first-boot setup wizard
# (DatabaseSetupWizardServlet) - the self-host-only replacement for the old "point at an existing/
# remote database server with root credentials" flow (RemoteRootDbChannel, deleted). Called over
# SSH+sudo by the wizard's own PreSpringSshClient, the same nspawnmgr_exec/PASSWORD-tier mechanism
# nspawnmgr-clone-template.sh/nspawnmgr-create-*-template.sh already use for creation-time work -
# unlike those, this runs from INSIDE the nspawnmgr container (see
# nspawnmgr-bootstrap-app-machine.sh), reaching back out to the host over SSH_HOST=10.100.0.1.
#
# Creating the opinionated "nspawnmgr"/"guacamole" databases and users is NOT done offline in a
# chroot here - both MySQL-family and PostgreSQL genuinely need to run (even briefly) to execute
# CREATE DATABASE/CREATE USER, and their own first-run bootstrap sequences differ too much to
# reimplement safely offline. Instead: install the engine via the same host-apt-pointed-at-a-
# foreign-root/chroot-fallback technique every other bake script here uses, bake in a first-boot
# systemd oneshot unit that creates the databases/users with fresh generated passwords once the
# container boots for real and has a genuinely running engine, then start the machine and wait for
# that oneshot unit to finish before returning - so the one privileged-script round trip the wizard
# makes covers create-machine + install-engine + create-databases + hand back credentials, all in
# one go. Credentials are printed to stdout as KEY=VALUE lines for the wizard to parse directly out
# of the SSH command's own captured output - never written anywhere the wizard would need a second
# round trip to read.
#
# $1 = engine choice: mysql | mariadb | postgresql (mysql and mariadb both install Debian's own
#      mariadb-server - Debian hasn't shipped Oracle MySQL server since Debian 9, "default-mysql-
#      server" is a MariaDB alias there - so the two choices are a machine-naming/labelling
#      distinction only; postgresql installs postgresql instead)
# $2 = machine name (the wizard defaults this per engine choice - mysqldb/mariadb/postgresdb - but
#      it's admin-editable, so always passed through explicitly rather than re-derived here)
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-bootstrap-db-machine.sh must be run as root." >&2
    exit 1
fi

ENGINE_CHOICE="$1"
MACHINE_NAME="$2"
if [ -z "$ENGINE_CHOICE" ] || [ -z "$MACHINE_NAME" ]; then
    echo "Usage: nspawnmgr-bootstrap-db-machine.sh <mysql|mariadb|postgresql> <machine-name>" >&2
    exit 1
fi
case "$ENGINE_CHOICE" in
    mysql|mariadb) ENGINE_PACKAGE="mariadb-server" ;;
    postgresql) ENGINE_PACKAGE="postgresql" ;;
    *) echo "Unknown engine choice: $ENGINE_CHOICE (expected mysql, mariadb, or postgresql)" >&2; exit 1 ;;
esac

PRIVILEGED_SCRIPTS_DIR="/usr/lib/nspawnmgr/privileged"
TEMPLATES_DIR="/var/lib/nspawnmgr/templates/nspawn"
DEBIAN_TEMPLATE="$TEMPLATES_DIR/debian-minimal.tar.gz"
ROOTFS="/var/lib/machines/$MACHINE_NAME"
CREDENTIALS_FILE="/root/nspawnmgr-db-credentials"
MARKER_FILE="/root/.nspawnmgr-db-init-done"

for cmd in chroot mount umount machinectl systemctl tar; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Required command '$cmd' not found." >&2
        exit 1
    fi
done

BAKE_MARKER="$ROOTFS/etc/.nspawnmgr-db-bake-complete"
if [ -e "$ROOTFS" ] && [ ! -e "$BAKE_MARKER" ]; then
    # Exists but the bake phase never finished (confirmed live for the sibling app-machine script:
    # a package's postinst can abort this script partway through, e.g. openjdk needing /proc
    # mounted - the same class of failure can hit mariadb-server/postgresql's own postinst here).
    # None of the steps below are safely re-runnable against a half-populated rootfs, so start
    # over cleanly rather than falling through to "wait for/read credentials" against a machine
    # that was never actually finished baking.
    echo "nspawnmgr-bootstrap-db-machine.sh: $ROOTFS exists but looks like an incomplete prior attempt - removing it and starting over."
    machinectl remove "$MACHINE_NAME" 2>/dev/null || true
    rm -rf "$ROOTFS"
    rm -f "/etc/systemd/nspawn/$MACHINE_NAME.nspawn"
fi

if [ -e "$ROOTFS" ]; then
    echo "nspawnmgr-bootstrap-db-machine.sh: $ROOTFS already exists — skipping creation, will just wait for/read credentials."
else
    mkdir -p "$TEMPLATES_DIR"
    # The bundled .deb variant ships a PostgreSQL-preinstalled image for engine=postgresql
    # specifically (not mysql/mariadb - see nspawnmgr-create-postgresql-template.sh's own comment
    # for why only this one engine gets it) - when present, clone straight from it and skip the
    # apt-get install below entirely, so a fully offline install has *something* usable for the
    # DB-provisioning step too, not just the app machine itself.
    BUNDLED_POSTGRESQL_MINIMAL="/usr/share/nspawnmgr/postgresql-minimal.tar.gz"
    if [ "$ENGINE_PACKAGE" = "postgresql" ] && [ -e "$BUNDLED_POSTGRESQL_MINIMAL" ]; then
        echo "nspawnmgr-bootstrap-db-machine.sh: using the bundled postgresql-preinstalled image (offline)..."
        echo "nspawnmgr-bootstrap-db-machine.sh: cloning into $ROOTFS..."
        "$PRIVILEGED_SCRIPTS_DIR/nspawnmgr-clone-template.sh" "$BUNDLED_POSTGRESQL_MINIMAL" "$ROOTFS"
        ENGINE_PREINSTALLED=true
    else
        # Same bundled-vs-online fallback as nspawnmgr-bootstrap-app-machine.sh - see its own comment.
        BUNDLED_DEBIAN_MINIMAL="/usr/share/nspawnmgr/debian-minimal.tar.gz"
        if [ ! -e "$DEBIAN_TEMPLATE" ]; then
            if [ -e "$BUNDLED_DEBIAN_MINIMAL" ]; then
                echo "nspawnmgr-bootstrap-db-machine.sh: using the bundled debian-minimal image..."
                cp "$BUNDLED_DEBIAN_MINIMAL" "$DEBIAN_TEMPLATE"
            else
                echo "nspawnmgr-bootstrap-db-machine.sh: baking debian-minimal..."
                "$PRIVILEGED_SCRIPTS_DIR/nspawnmgr-create-debian-template.sh" "$DEBIAN_TEMPLATE"
            fi
        fi

        echo "nspawnmgr-bootstrap-db-machine.sh: cloning debian-minimal into $ROOTFS..."
        "$PRIVILEGED_SCRIPTS_DIR/nspawnmgr-clone-template.sh" "$DEBIAN_TEMPLATE" "$ROOTFS"
        ENGINE_PREINSTALLED=false
    fi

    if [ "$ENGINE_PREINSTALLED" != "true" ]; then
        echo "nspawnmgr-bootstrap-db-machine.sh: installing $ENGINE_PACKAGE into $ROOTFS..."
        export DEBIAN_FRONTEND=noninteractive
        # /proc/dev/sys/run bind-mounted unconditionally, in BOTH branches - confirmed live
        # (nspawnmgr-bootstrap-app-machine.sh's own JRE install) that `apt-get -o Dir=` still chroots
        # into $ROOTFS for the dpkg *configure* step via `-o DPkg::Options::=--root=`, and a package's
        # postinst can need a real /proc there even in the host-apt branch (openjdk's own
        # certificate-store setup runs `java` itself, which refuses to start without one) - engine
        # postinst scripts (mysql_install_db/initdb-style first-run setup) are exactly the kind of
        # script likely to hit the same thing, so mount defensively rather than wait for a live failure.
        mkdir -p "$ROOTFS/run"
        mount --bind /run "$ROOTFS/run"
        mount --bind /dev "$ROOTFS/dev"
        mount -t proc proc "$ROOTFS/proc"
        mount -t sysfs sys "$ROOTFS/sys"
        engine_install_cleanup() {
            umount "$ROOTFS/sys" 2>/dev/null || true
            umount "$ROOTFS/proc" 2>/dev/null || true
            umount "$ROOTFS/dev" 2>/dev/null || true
            umount "$ROOTFS/run" 2>/dev/null || true
        }
        trap engine_install_cleanup EXIT
        if command -v apt-get >/dev/null 2>&1; then
            APT_OPTS="-o Dir=$ROOTFS -o Dir::State::status=$ROOTFS/var/lib/dpkg/status -o APT::Sandbox::User=root -o DPkg::Options::=--root=$ROOTFS"
            apt-get $APT_OPTS update
            apt-get $APT_OPTS install -y "$ENGINE_PACKAGE"
        else
            cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
            chroot "$ROOTFS" apt-get update
            chroot "$ROOTFS" apt-get install -y "$ENGINE_PACKAGE"
        fi
        engine_install_cleanup
        trap - EXIT
    fi

    if [ "$ENGINE_PACKAGE" = "mariadb-server" ]; then
        # Debian's mariadb-server binds 127.0.0.1 only by default - this machine only ever talks
        # to the nspawnmgr container over nspawnbr0, never the host's own loopback, so it needs to
        # listen on all interfaces instead (matching every other managed container's own posture:
        # reachable on its internal bridge address, access controlled by password, not by network
        # scoping - same reasoning RemoteRootDbChannel's own removed comment gave for CREATE USER
        # '%' instead of 'localhost').
        sed -i 's/^bind-address.*/bind-address = 0.0.0.0/' "$ROOTFS/etc/mysql/mariadb.conf.d/50-server.cnf" 2>/dev/null || true
        cat > "$ROOTFS/etc/systemd/system/nspawnmgr-db-init.service" <<'EOF'
[Unit]
Description=nspawnmgr: create the nspawnmgr/guacamole databases and users on first boot
After=mariadb.service
Requires=mariadb.service
ConditionPathExists=!/root/.nspawnmgr-db-init-done

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/nspawnmgr-db-init.sh
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF
        cat > "$ROOTFS/usr/local/sbin/nspawnmgr-db-init.sh" <<EOF
#!/bin/sh
set -e
[ -f "$MARKER_FILE" ] && exit 0
NSPAWNMGR_PW="\$(openssl rand -base64 24)"
GUACAMOLE_PW="\$(openssl rand -base64 24)"
mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS nspawnmgr CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'nspawnmgr'@'%' IDENTIFIED BY '\$NSPAWNMGR_PW';
GRANT ALL PRIVILEGES ON nspawnmgr.* TO 'nspawnmgr'@'%';
CREATE DATABASE IF NOT EXISTS guacamole CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS 'guacamole'@'%' IDENTIFIED BY '\$GUACAMOLE_PW';
GRANT ALL PRIVILEGES ON guacamole.* TO 'guacamole'@'%';
FLUSH PRIVILEGES;
SQL
cat > $CREDENTIALS_FILE <<CREDS
NSPAWNMGR_DB_PASSWORD=\$NSPAWNMGR_PW
GUACAMOLE_DB_PASSWORD=\$GUACAMOLE_PW
CREDS
chmod 600 $CREDENTIALS_FILE
touch $MARKER_FILE
EOF
    else
        # PostgreSQL: same reasoning as the MariaDB branch above for listen_addresses/pg_hba.conf -
        # this machine is only ever reached over nspawnbr0, so open it to that subnet with
        # password auth. PG_VERSION_DIR resolved at bake time (chroot) since Debian's postgresql
        # package installs its config under a version-numbered path
        # (/etc/postgresql/<version>/main/) that varies by Debian release.
        PG_CONF_DIR="$(chroot "$ROOTFS" sh -c 'ls -d /etc/postgresql/*/main' | head -1)"
        sed -i "s/^#listen_addresses.*/listen_addresses = '*'/" "$ROOTFS$PG_CONF_DIR/postgresql.conf"
        # Force the port to the fixed 5432 DatabaseSetupWizardServlet/GuacamoleSchemaRunner always
        # dial - confirmed live (yoga): a plain chroot doesn't get its own network namespace, it
        # shares the BUILD host's, so if that host already had something bound to 5432 at bake time
        # (its own real postgres, say), pg_createcluster's own port-conflict avoidance silently picks
        # 5433 (or higher) for this cluster instead - "Connection refused" on 5432 even though
        # postgres itself came up fine and local (unix-socket) access worked. Applies equally to a
        # freshly-installed cluster and one cloned from the bundled postgresql-minimal.tar.gz, since
        # this runs before the very first real boot either way.
        sed -i "s/^#\?port[[:space:]]*=.*/port = 5432/" "$ROOTFS$PG_CONF_DIR/postgresql.conf"
        echo "host all all 10.100.0.0/24 md5" >> "$ROOTFS$PG_CONF_DIR/pg_hba.conf"
        cat > "$ROOTFS/etc/systemd/system/nspawnmgr-db-init.service" <<'EOF'
[Unit]
Description=nspawnmgr: create the nspawnmgr/guacamole databases and users on first boot
After=postgresql.service
Requires=postgresql.service
ConditionPathExists=!/root/.nspawnmgr-db-init-done

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/nspawnmgr-db-init.sh
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF
        cat > "$ROOTFS/usr/local/sbin/nspawnmgr-db-init.sh" <<EOF
#!/bin/sh
set -e
[ -f "$MARKER_FILE" ] && exit 0
NSPAWNMGR_PW="\$(openssl rand -base64 24)"
GUACAMOLE_PW="\$(openssl rand -base64 24)"
su -c "psql -c \\"CREATE ROLE nspawnmgr LOGIN PASSWORD '\$NSPAWNMGR_PW'\\"" postgres
su -c "createdb -O nspawnmgr nspawnmgr" postgres
su -c "psql -c \\"CREATE ROLE guacamole LOGIN PASSWORD '\$GUACAMOLE_PW'\\"" postgres
su -c "createdb -O guacamole guacamole" postgres
cat > $CREDENTIALS_FILE <<CREDS
NSPAWNMGR_DB_PASSWORD=\$NSPAWNMGR_PW
GUACAMOLE_DB_PASSWORD=\$GUACAMOLE_PW
CREDS
chmod 600 $CREDENTIALS_FILE
touch $MARKER_FILE
EOF
    fi
    chmod 755 "$ROOTFS/usr/local/sbin/nspawnmgr-db-init.sh"
    chroot "$ROOTFS" systemctl enable nspawnmgr-db-init.service

    mkdir -p /etc/systemd/nspawn
    cat > "/etc/systemd/nspawn/$MACHINE_NAME.nspawn" <<EOF
[Exec]
PrivateUsers=identity
[Network]
Bridge=nspawnbr0
EOF

    touch "$BAKE_MARKER"
    echo "nspawnmgr-bootstrap-db-machine.sh: starting $MACHINE_NAME..."
    machinectl start "$MACHINE_NAME"
fi

# Wait for the oneshot unit to finish (poll the marker file inside the container the same
# systemd-run --machine=<name> --pipe --quiet --wait way every other in-container command in this
# codebase runs one, rather than machinectl shell's own interactive-oriented PTY session) - this
# may be a genuinely fresh boot, so engine startup + first-run initialization can take a real
# amount of time, especially for PostgreSQL's own initdb-on-first-package-install step.
echo "nspawnmgr-bootstrap-db-machine.sh: waiting for database initialization to finish..."
i=1
while [ "$i" -le 24 ]; do
    if systemd-run --machine="$MACHINE_NAME" --pipe --quiet --wait /bin/sh -c "test -f $MARKER_FILE" >/dev/null 2>&1; then
        break
    fi
    # Heartbeat so this doesn't read as hung on the wizard's own live log during PostgreSQL's
    # first-run initdb, which can genuinely take a while - see the comment above this loop.
    echo "nspawnmgr-bootstrap-db-machine.sh: waiting $i ..."
    sleep 5
    i=$((i + 1))
done
if [ "$i" -gt 24 ]; then
    echo "nspawnmgr-bootstrap-db-machine.sh: database initialization didn't finish within 120s — check 'systemd-run --machine=$MACHINE_NAME --pipe --quiet --wait journalctl -u nspawnmgr-db-init.service'." >&2
    exit 1
fi

systemd-run --machine="$MACHINE_NAME" --pipe --quiet --wait /bin/cat "$CREDENTIALS_FILE"
