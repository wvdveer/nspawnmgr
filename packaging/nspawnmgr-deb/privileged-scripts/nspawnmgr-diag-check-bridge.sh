#!/bin/sh
# Reports whether the shared container bridge (nspawnbr0 - see
# packaging/nspawnmgr-deb/70-nspawnmgr-bridge.netdev/.network, installed unconditionally by
# postinst) is actually up with its expected address. Prints exactly one of:
#   ok       - nspawnbr0 exists with address 10.100.0.1/24.
#   missing  - nspawnbr0 doesn't exist yet, or doesn't have the expected address (e.g.
#              systemd-networkd hasn't picked up the config, or isn't running at all - check the
#              "systemd-networkd active" check first).
set -e
if ip -4 -o addr show nspawnbr0 2>/dev/null | grep -q '10\.100\.0\.1/24'; then
    echo "ok"
else
    echo "missing"
fi
