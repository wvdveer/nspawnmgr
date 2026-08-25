#!/bin/sh
# Prints VM $1's own internal IPv4 address on nspawnbr0, or nothing (exit 0, empty stdout) if it
# hasn't been leased one yet - same "empty = not ready" contract as
# nspawnmgr-get-internal-address.sh's nspawn equivalent.
#
# A QEMU guest has neither nspawn's nsenter-able network namespace nor podman's own IPAM to ask -
# it's genuine DHCP served by systemd-networkd's own [DHCPServer] on nspawnbr0 (see
# 70-nspawnmgr-bridge.network), so the only way to learn the address is to find the lease
# systemd-networkd itself handed out to this VM's deterministic MAC (see
# nspawnmgr-qemu-write-unit.sh - the two scripts MUST derive the same MAC from the name, since
# neither persists it anywhere).
#
# NOT YET VERIFIED LIVE against a real systemd-networkd lease store - the exact path/format has
# changed across systemd versions (newer: a single JSON array at
# /var/lib/systemd/network/leases/<ifindex>; older: one file per lease under
# /run/systemd/netif/leases/<ifindex>/). This tries both, best-effort, and should be corrected
# against whatever the actual target host's systemd version does before relying on it.
# $1 = VM name.
set -e
name="$1"

mac_suffix=$(printf '%s' "$name" | md5sum | cut -c1-6 | sed 's/\(..\)\(..\)\(..\)/\1:\2:\3/')
mac="52:54:00:$mac_suffix"

ifindex=$(ip -o link show nspawnbr0 2>/dev/null | awk -F': ' '{print $1}')
if [ -z "$ifindex" ]; then
    exit 0
fi

# Newer systemd-networkd: one JSON file per interface, an array of lease objects.
json_leases="/var/lib/systemd/network/leases/$ifindex"
if [ -f "$json_leases" ] && command -v grep >/dev/null 2>&1; then
    addr=$(grep -i "$mac" "$json_leases" 2>/dev/null | grep -o '"Address":"[^"]*"' | head -n1 | cut -d'"' -f4)
    if [ -n "$addr" ]; then
        echo "$addr"
        exit 0
    fi
fi

# Older systemd-networkd: one plain key=value file per lease under a per-interface directory.
legacy_dir="/run/systemd/netif/leases/$ifindex"
if [ -d "$legacy_dir" ]; then
    for lease_file in "$legacy_dir"/*; do
        [ -f "$lease_file" ] || continue
        if grep -qi "$mac" "$lease_file" 2>/dev/null; then
            addr=$(grep '^ADDRESS=' "$lease_file" | cut -d= -f2)
            if [ -n "$addr" ]; then
                echo "$addr"
                exit 0
            fi
        fi
    done
fi

exit 0
