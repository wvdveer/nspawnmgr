#!/bin/sh
# Attaches a podman network to nspawnbr0, using netavark's own host-local IPAM driver rather than
# its DHCP IPAM driver.
#
# DHCP IPAM was tried first and abandoned: confirmed live on yoga 2026-08-16 (and matching a known,
# documented upstream limitation - containers/netavark#1416, closed as a duplicate of #1008) that
# netavark's DHCP proxy runs in the HOST network namespace and transmits its DHCPDISCOVER directly
# out the bridge's own TX path - the Linux kernel never loops packets transmitted out an interface
# back to that same interface's own RX queue, so a DHCP server bound to the bridge device itself
# (exactly how 70-nspawnmgr-bridge.network's own [DHCPServer] is configured, on Name=nspawnbr0)
# never sees the request at all, even though tcpdump does. systemd-nspawn containers never hit this
# because their own DHCP client's traffic genuinely arrives via a bridge PORT (their own veth),
# which the bridge correctly forwards to local delivery - the exact mechanism netavark's host-side
# proxy bypasses. No configuration on this host can fix that; it's a kernel/netavark architecture
# mismatch, not a misconfiguration.
#
# host-local IPAM sidesteps the whole DHCP path - netavark just allocates from a fixed local pool,
# no proxy, no DHCP protocol exchange at all. The subnet/gateway match nspawnbr0's own fixed
# 10.100.0.0/24 identity; the lease range is restricted to 10.100.0.192-10.100.0.254, the exact
# complement of the DHCP pool 70-nspawnmgr-bridge.network's own [DHCPServer] PoolOffset=/PoolSize=
# now reserves for it (10.100.0.2-10.100.0.191) - two independent, uncoordinated allocators sharing
# one subnet need a hard split like this or they can eventually hand the same address to both an
# nspawn container and a pod.
#
# Writes /etc/containers/networks/nspawnbr0.json directly instead of running
# `podman network create --subnet ... --ip-range ...` - confirmed live on yoga 2026-08-16 that the
# CLI path fails outright with "subnet 10.100.0.0/24 is already used on the host or by another
# config", a second, separate known upstream bug (containers/podman#25833/#25736/#27358, closed as
# duplicates of containers/common#2322): podman validates an unmanaged network's declared subnet
# against the host's own existing routes, even though attaching to an already-addressed pre-existing
# bridge is the entire point of unmanaged mode. The only documented workaround (bring the bridge
# down, create the network, bring it back up) is a non-starter here - nspawnbr0 is the shared bridge
# every currently-running systemd-nspawn container depends on. Writing the config file directly
# (confirmed live to work and produce an identical result to what `podman network create` would
# write, verified via `podman network inspect` and a real `podman run` getting a working
# 10.100.0.192-range address with the reserved-pool split honored) sidesteps that CLI-level check
# entirely - it isn't re-validated once a network is actually used to run a container.
#
# Still needs netavark 1.14+ for the bridge driver's own "mode=unmanaged" option (attach to an
# existing bridge instead of creating/managing one) - that requirement is unrelated to which IPAM
# driver sits on top of it (confirmed against netavark's own RELEASE_NOTES.md - PR #1090) and is
# still meaningfully newer than most Debian/Ubuntu stock podman packages (Debian bookworm ships
# podman 4.3.1/netavark well under 1.14; Linux Mint 22.1 ships podman 4.9.3/netavark 1.4.0,
# confirmed live 2026-08-15). nspawnmgr-diag-check-podman-network.sh checks the installed netavark
# version itself and only offers this Fix button at all when it's new enough, so reaching this
# script at all means it's genuinely expected to succeed - a failure here on a host the check
# already cleared would be a real bug to investigate, not expected behavior.
#
# "dns_enabled": false - nspawnmgr's own dnsmasq is already the DNS authority on nspawnbr0 (bound
# to its gateway address, 10.100.0.1) - podman's own embedded DNS (aardvark-dns) would be redundant
# at best and a port-53-bind conflict at worst if left enabled.
set -e

NETWORK_CONFIG_DIR="/etc/containers/networks"
NETWORK_CONFIG_FILE="$NETWORK_CONFIG_DIR/nspawnbr0.json"

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-configure-podman-network.sh must be run as root." >&2
    exit 1
fi

if ! command -v podman >/dev/null 2>&1; then
    echo "podman is not installed." >&2
    exit 1
fi

if podman network exists nspawnbr0 2>/dev/null; then
    echo "A podman network named 'nspawnbr0' already exists:"
    podman network inspect nspawnbr0
    exit 0
fi

mkdir -p "$NETWORK_CONFIG_DIR"
NETWORK_ID="$(head -c32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%S.%N+00:00)"
cat > "$NETWORK_CONFIG_FILE" <<EOF
{
    "name": "nspawnbr0",
    "id": "$NETWORK_ID",
    "driver": "bridge",
    "network_interface": "nspawnbr0",
    "created": "$CREATED_AT",
    "subnets": [
        {
            "subnet": "10.100.0.0/24",
            "gateway": "10.100.0.1",
            "lease_range": {
                "start_ip": "10.100.0.192",
                "end_ip": "10.100.0.254"
            }
        }
    ],
    "ipv6_enabled": false,
    "internal": false,
    "dns_enabled": false,
    "options": {
        "mode": "unmanaged"
    },
    "ipam_options": {
        "driver": "host-local"
    }
}
EOF
podman network inspect nspawnbr0
