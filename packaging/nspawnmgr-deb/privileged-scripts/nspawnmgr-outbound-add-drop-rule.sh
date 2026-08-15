#!/bin/sh
# Adds a catch-all DROP rule to NSPAWNMGR-OUTBOUND for veth $1.
set -e
CHAIN="NSPAWNMGR-OUTBOUND"
iptables -A "$CHAIN" -i "$1" -j DROP
