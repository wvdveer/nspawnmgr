#!/bin/sh
# Prints the host-side veth interface for machine $1, discovered via its peer ifindex since
# systemd-nspawn doesn't name the host-side interface predictably.
set -e
machine="$1"
pid=$(machinectl show "$machine" --property=Leader --value)
peer=$(nsenter --net="/proc/$pid/ns/net" -- ip -o link show host0 | sed -n 's/.*@if\([0-9]*\):.*/\1/p')
ip -o link show | sed -n "s/^${peer}: \([^:@]*\).*/\1/p"
