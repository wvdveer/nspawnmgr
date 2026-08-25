#!/bin/sh
# Reports whether a podman network is already attached to nspawnbr0 - see
# nspawnmgr-configure-podman-network.sh for the matching fix (host-local IPAM based, needs
# netavark 1.14+ for the bridge driver's own "mode=unmanaged" option, attach to an existing bridge
# instead of creating/managing one - confirmed against netavark's own RELEASE_NOTES.md, PR #1090).
# Not about DHCP: an earlier revision of both this check and its matching fix used netavark's DHCP
# IPAM driver instead (which also needs 1.14+, for a *different* reason - DHCP-in-unmanaged-mode
# support, PR #868, landing in that same 1.14 release) - abandoned after confirming live on yoga
# 2026-08-16 that it can never work at all, DHCP or not, due to a kernel TX/RX bridge-isolation
# limitation (containers/netavark#1416/#1008): a DHCP server bound to the bridge device itself is
# unreachable from netavark's own host-netns DHCP proxy, no matter how new netavark is. The 1.14
# threshold below is entirely about mode=unmanaged now, not DHCP.
#
# Earlier revisions of this check didn't know the exact threshold and just said "try Fix and see" -
# now that it's confirmed precisely, a too-old netavark is detected here instead, before the user
# ever clicks Fix (confirmed live, yoga, 2026-08-15: Linux Mint 22.1's stock podman 4.9.3 ships
# netavark 1.4.0 - ten minor versions short - Fix would otherwise just fail with a raw, confusing
# "Error: unsupported bridge network option mode").
# Deliberately greps the raw `podman network inspect` JSON for the interface name rather than
# relying on an exact Go-template field name (e.g. .NetworkInterface) - this is detection-only, a
# false negative here just means the check under-reports, not a wrong destructive action, so
# robustness against an uncertain exact field name matters more than precision. Prints exactly one
# of:
#   ok       - some podman network's own host-side interface is nspawnbr0.
#   missing  - podman isn't installed, or no network is attached to nspawnbr0 yet (and netavark is
#              new enough that Fix has a real chance of working).
#   too-old  - podman is installed, but its netavark is older than 1.14 - Fix cannot work here.
set -e
if ! command -v podman >/dev/null 2>&1; then
    echo "missing"
    exit 0
fi
FOUND="missing"
for name in $(podman network ls --format '{{.Name}}' 2>/dev/null); do
    if podman network inspect "$name" 2>/dev/null | grep -q '"nspawnbr0"'; then
        FOUND="ok"
        break
    fi
done
if [ "$FOUND" = "missing" ]; then
    # e.g. "netavark 1.4.0" - strip the leading backend name, keep just the version.
    NETAVARK_VERSION="$(podman info --format '{{.Host.NetworkBackendInfo.Version}}' 2>/dev/null | awk '{print $NF}')"
    NETAVARK_MAJOR="$(echo "$NETAVARK_VERSION" | cut -d. -f1)"
    NETAVARK_MINOR="$(echo "$NETAVARK_VERSION" | cut -d. -f2)"
    case "$NETAVARK_MAJOR" in ''|*[!0-9]*) NETAVARK_MAJOR=0 ;; esac
    case "$NETAVARK_MINOR" in ''|*[!0-9]*) NETAVARK_MINOR=0 ;; esac
    if [ "$NETAVARK_MAJOR" -eq 0 ] && [ "$NETAVARK_MINOR" -eq 0 ]; then
        : # Couldn't determine a version at all - fall through as "missing"/fixable rather than
          # guessing "too-old", same "false negative over wrong destructive action" posture above.
    elif [ "$NETAVARK_MAJOR" -lt 1 ] || { [ "$NETAVARK_MAJOR" -eq 1 ] && [ "$NETAVARK_MINOR" -lt 14 ]; }; then
        FOUND="too-old"
    fi
fi
echo "$FOUND"
