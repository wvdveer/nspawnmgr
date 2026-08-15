#!/bin/sh
# Sets (or clears) machine $1's own "requires another machine already started" dependency, via a
# systemd unit drop-in on its own systemd-nspawn@$1.service. $2 = the OTHER machine's name to
# require, or omitted/empty to clear any existing requirement.
#
# Fixed-shape and safe for NOPASSWD: this only ever touches nspawnmgr's own single, fixed-name
# drop-in file (nspawnmgr-requires.conf) inside $1's own .service.d directory, never anything else
# an admin (or another tool) might have placed there.
#
# Requires=+After= together (not Wants=+After=): the user asked for one machine to genuinely
# require another already running, not just a soft/best-effort ordering hint - if the required
# machine fails to start or is stopped, systemd will stop this one too, matching that request
# literally.
set -e
NAME="$1"
REQUIRES="$2"
DROPIN_DIR="/etc/systemd/system/systemd-nspawn@${NAME}.service.d"
DROPIN_FILE="$DROPIN_DIR/nspawnmgr-requires.conf"

if [ -z "$REQUIRES" ]; then
    rm -f "$DROPIN_FILE"
else
    mkdir -p "$DROPIN_DIR"
    cat > "$DROPIN_FILE" <<EOF
[Unit]
Requires=systemd-nspawn@${REQUIRES}.service
After=systemd-nspawn@${REQUIRES}.service
EOF
fi

systemctl daemon-reload
