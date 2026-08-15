#!/bin/sh
# Prints machine $1's own boot-time settings, queried live from the host rather than trusted from
# nspawnmgr's own database - an admin may toggle these directly via systemctl, bypassing the app
# entirely, so the app must never assume its own last-known value is still correct. Two KEY=VALUE
# lines:
#   ENABLED=true|false - whether systemd-nspawn@$1.service is enabled to auto-start when the HOST
#                        itself boots (systemctl is-enabled).
#   REQUIRES=<name>    - the OTHER machine this one currently requires already started, read back
#                        from nspawnmgr's own systemd-nspawn@$1.service.d/nspawnmgr-requires.conf
#                        drop-in (see nspawnmgr-set-machine-requires.sh) - blank if none set.
#
# Deliberately no top-level `set -e`: `systemctl is-enabled` legitimately exits non-zero for the
# ordinary "disabled" case (not a script error), so its exit code is handled explicitly via the
# if/else below instead of being allowed to abort the script.
NAME="$1"

if systemctl is-enabled --quiet "systemd-nspawn@${NAME}.service" 2>/dev/null; then
    ENABLED=true
else
    ENABLED=false
fi

DROPIN_FILE="/etc/systemd/system/systemd-nspawn@${NAME}.service.d/nspawnmgr-requires.conf"
REQUIRES=""
if [ -f "$DROPIN_FILE" ]; then
    REQUIRES="$(sed -n 's/^Requires=systemd-nspawn@\(.*\)\.service$/\1/p' "$DROPIN_FILE" | head -n1)"
fi

echo "ENABLED=$ENABLED"
echo "REQUIRES=$REQUIRES"
