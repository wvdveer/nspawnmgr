#!/bin/sh
# Ensures the NSPAWNMGR-OUTBOUND iptables chain exists and is jumped to from the top of FORWARD.
set -e
CHAIN="NSPAWNMGR-OUTBOUND"
iptables -S "$CHAIN" >/dev/null 2>&1 || iptables -N "$CHAIN"
iptables -C FORWARD -j "$CHAIN" >/dev/null 2>&1 || iptables -I FORWARD 1 -j "$CHAIN"
