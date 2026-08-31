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
# CONFIRMED LIVE 2026-08-31 (systemd 257, Debian 13/trixie): systemd-networkd's own DHCP *server*
# role persists leases at /var/lib/systemd/network/dhcp-server-lease/<interface-name> (named by
# the bridge's own interface name, not its ifindex) - a single JSON object (not an array) with a
# top-level "Leases" array; each entry's "Address"/"HardwareAddress" are arrays of decimal byte
# values (e.g. HardwareAddress":[82,84,0,70,71,153,0,...], not a quoted MAC/IP string). This is a
# genuinely different path AND format from either of the two this script used to check (which
# turned out to be for systemd-networkd's DHCP *client* lease tracking - a different subsystem
# entirely - and never actually existed on this host: confirmed live, neither
# /var/lib/systemd/network/leases/ nor /run/systemd/netif/leases/<ifindex>/ existed at all, so
# every QEMU VM's SSH/RDP/Files access silently stayed unavailable forever, not just briefly
# during boot). The two old paths are kept below as a fallback for older systemd-networkd
# versions that predate the dhcp-server-lease directory, best-effort, same posture as before.
# $1 = VM name.
set -e
name="$1"

mac_suffix=$(printf '%s' "$name" | md5sum | cut -c1-6 | sed 's/\(..\)\(..\)\(..\)/\1:\2:\3/')
mac="52:54:00:$mac_suffix"

ifname=nspawnbr0

# Current systemd-networkd (confirmed live, systemd 257): DHCP-server-issued leases, one JSON
# object per interface, keyed by interface name. HardwareAddress/Address are decimal-byte arrays,
# not strings - match on the fixed 6-byte prefix (the trailing bytes in a 16-byte
# HardwareAddress field are always zero-padding) and pull out the matching lease's own Address.
server_leases="/var/lib/systemd/network/dhcp-server-lease/$ifname"
if [ -f "$server_leases" ]; then
    old_ifs=$IFS
    IFS=:
    set -- $mac
    IFS=$old_ifs
    # `$((16#XX))`-style arithmetic base notation is a ksh/bash extension, NOT POSIX - confirmed
    # live, dash (Debian's real /bin/sh, and this whole packaging targets sh, not bash) rejects it
    # outright ("arithmetic expression: expecting EOF"), even for a bare literal like $((16#52)).
    # printf's own %d conversion accepting a 0x-prefixed hex literal IS POSIX-specified and
    # confirmed working under dash - use that instead for the hex-to-decimal conversion.
    d1=$(printf '%d' "0x$1"); d2=$(printf '%d' "0x$2"); d3=$(printf '%d' "0x$3")
    d4=$(printf '%d' "0x$4"); d5=$(printf '%d' "0x$5"); d6=$(printf '%d' "0x$6")
    pattern="\"HardwareAddress\":[$d1,$d2,$d3,$d4,$d5,$d6,"
    addr=$(sed 's/.*"Leases":\[//' "$server_leases" | awk -v pat="$pattern" '
        BEGIN { RS="},{" }
        index($0, pat) > 0 {
            if (match($0, /"Address":\[[0-9]+,[0-9]+,[0-9]+,[0-9]+\]/)) {
                s = substr($0, RSTART, RLENGTH)
                gsub(/"Address":\[|\]/, "", s)
                gsub(/,/, ".", s)
                print s
                exit
            }
        }
    ')
    if [ -n "$addr" ]; then
        echo "$addr"
        exit 0
    fi
fi

ifindex=$(ip -o link show "$ifname" 2>/dev/null | awk -F': ' '{print $1}')
if [ -z "$ifindex" ]; then
    exit 0
fi

# Fallback for older systemd-networkd versions that predate the dhcp-server-lease directory above
# - unverified against a real host, kept best-effort. Newer: one JSON file per interface, an array
# of lease objects (client-lease-tracking format, distinct from the server-lease format above).
json_leases="/var/lib/systemd/network/leases/$ifindex"
if [ -f "$json_leases" ] && command -v grep >/dev/null 2>&1; then
    addr=$(grep -i "$mac" "$json_leases" 2>/dev/null | grep -o '"Address":"[^"]*"' | head -n1 | cut -d'"' -f4)
    if [ -n "$addr" ]; then
        echo "$addr"
        exit 0
    fi
fi

# Older still: one plain key=value file per lease under a per-interface directory.
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
