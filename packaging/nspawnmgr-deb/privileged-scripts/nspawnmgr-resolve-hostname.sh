#!/bin/sh
# Resolves $1 (a hostname) to its address via the HOST's own name resolution (DNS, /etc/hosts,
# NSS modules such as mDNS/WINS if configured - whatever getent's configured chain provides) - used
# by ContainerSessionService so an EXTERNAL host can be connected to by a hostname Guacamole's own
# SSH/RDP/VNC client (now running inside the self-hosted nspawnmgr container) has no way to resolve
# on its own: that container's only DNS path is nspawnmgr's own dnsmasq (container names + public
# 1.1.1.1/9.9.9.9 upstream only - see dnsmasq-nspawnmgr.conf), with zero visibility into the host's
# own LAN-local name resolution (NetBIOS, mDNS, a LAN router's own DNS, etc.). Resolving on the HOST
# instead, where all of that already works, sidesteps the container's DNS limitation entirely rather
# than trying to replicate the host's own resolution setup inside dnsmasq.
#
# $1 = hostname to resolve. An already-dotted IPv4/IPv6 address round-trips through getent
# unresolved (its own numeric address), so this is always safe to call unconditionally, whether or
# not the admin actually typed a real hostname into the host's own hostname field.
#
# Genuine resolution failure (unknown host, DNS unreachable, etc.) is a real error here (exit
# non-zero, getent's own stderr passed straight through) - unlike
# nspawnmgr-get-internal-address.sh's "empty output is the normal not-ready-yet case", a hostname
# that doesn't resolve at connect time is something the caller needs to see and fail on, not
# silently retry forever.
#
# getent's own output captured into a variable BEFORE piping through awk/head, not piped directly -
# same reasoning nspawnmgr-get-internal-address.sh's own comment gives: plain `sh` (dash) has no
# pipefail, so a getent failure partway through a pipeline would otherwise be silently swallowed by
# awk/head still exiting 0 on empty input. A bare command substitution's own exit status, unlike a
# mid-pipeline failure, does trip `set -e`.
set -e
RAW="$(getent hosts "$1")"
ADDRESS="$(echo "$RAW" | awk '{print $1}' | head -n1)"

# Observed live (a stalled connection attempt, traced back to this): when $1 is literally THIS
# host's own hostname (an External host entry pointing at the same machine the self-hosted install
# runs on - a real, common case, not an edge case), getent most likely resolves it via
# nss-myhostname/the host's own self-mapping /etc/hosts entry (classically "127.0.1.1 <hostname>"
# on Debian) rather than a real LAN-facing address - useless as a connection target either way:
# Guacamole, dialing from inside the self-hosted nspawnmgr container's own network namespace, would
# just connect to itself. Detected generically (any loopback result, not by comparing $1 against
# `hostname` specifically) since a *genuine* remote host has no legitimate reason to ever resolve
# to 127.0.0.0/8 or ::1.
#
# First choice for the fallback: 10.100.0.1, nspawnbr0's own fixed host-side address (see
# 70-nspawnmgr-bridge.network's Address=10.100.0.1/24) - the exact same address SSH_HOST/
# HOST_PUBLIC_ADDRESS already rewrite to for every other "reach the host from inside a container"
# need in this app (see nspawnmgr-bootstrap-app-machine.sh), guaranteed reachable from any
# container on this bridge by construction. Checked against this host's own interfaces before being
# trusted, not assumed - it's only guaranteed to exist on a genuinely self-hosted install with a
# healthy bridge (see nspawnmgr-diag-check-bridge.sh); a plain (non-self-hosted) install, or one
# where the bridge hasn't come up, has no such guarantee.
#
# Falls back further to this host's other real, externally-routable address (10.100.0.0/24
# excluded - that's nspawnbr0's own subnet, not a genuine external one) if 10.100.0.1 isn't there -
# still a real, working address for a plain (non-self-hosted) install where nspawnbr0 either
# doesn't exist yet or isn't relevant, just not the preferred/most-reliably-routable one. Only a
# genuinely address-less host (no global-scope address at all) falls through to a real failure.
#
# Both checks reuse the exact `ip -4 -o addr show scope global` invocation already NOPASSWD-granted
# elsewhere (see nspawnmgr.sudoers) - run once, not twice, since sudo re-authorizing the same
# command a second time would be wasteful, not because a second call would be denied.
case "$ADDRESS" in
    127.*|::1)
        GLOBAL_ADDRESSES="$(ip -4 -o addr show scope global | awk '{print $4}' | cut -d/ -f1)"
        if echo "$GLOBAL_ADDRESSES" | grep -q '^10\.100\.0\.1$'; then
            ADDRESS="10.100.0.1"
        else
            ADDRESS="$(echo "$GLOBAL_ADDRESSES" | grep -v '^10\.100\.0\.' | head -n1)"
            if [ -z "$ADDRESS" ]; then
                echo "'$1' resolved to a loopback address (this host's own hostname), and no usable fallback address (neither nspawnbr0's 10.100.0.1 nor any other address) could be found on this host" >&2
                exit 1
            fi
        fi
        ;;
esac
echo "$ADDRESS"
