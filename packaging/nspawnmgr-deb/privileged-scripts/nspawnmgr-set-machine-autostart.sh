#!/bin/sh
# Enables or disables machine $1 auto-starting when the HOST itself boots. $2 = "true" or "false".
# Unit-file symlink manipulation only (systemctl enable/disable) - safe to run with no running
# instance of the unit itself, same "no running init needed" precedent every bake script's own
# "systemctl enable ssh"-style call already relies on.
set -e
NAME="$1"
ENABLE="$2"

if [ "$ENABLE" = "true" ]; then
    systemctl enable "systemd-nspawn@${NAME}.service"
else
    systemctl disable "systemd-nspawn@${NAME}.service"
fi
