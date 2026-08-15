#!/bin/sh
# Adds an ACCEPT rule to NSPAWNMGR-OUTBOUND. $1=veth $2=destHost $3=protocol $4=destPort
set -e
CHAIN="NSPAWNMGR-OUTBOUND"
iptables -A "$CHAIN" -i "$1" -d "$2" -p "$3" --dport "$4" -j ACCEPT
