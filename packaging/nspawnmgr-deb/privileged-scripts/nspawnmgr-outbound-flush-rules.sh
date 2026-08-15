#!/bin/sh
# Deletes every existing NSPAWNMGR-OUTBOUND rule whose input interface is veth $1, in one pass.
set -e
CHAIN="NSPAWNMGR-OUTBOUND"
veth="$1"
iptables -S "$CHAIN" | grep -- "-i $veth " | sed 's/^-A/-D/' | while IFS= read -r rule; do
    iptables $rule
done
