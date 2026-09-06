#!/bin/sh
# Loads a podman template's saved image and creates (does not start) a new container from it,
# attached to the shared nspawnbr0 network so it gets its own address the same way nspawn
# containers already do (see nspawnmgr-configure-podman-network.sh for exactly how) - the action
# behind ProvisioningService's own pod-creation flow (see
# TemplateService/ContainerFilesystemProvisioner.createPodmanContainer). $1 = source podman-template
# .tar path (as produced by nspawnmgr-podman-pull-template.sh/
# nspawnmgr-podman-convert-nspawn-to-podman.sh), $2 = the new container's name, $3 = optional
# command override (like a Dockerfile CMD, run through a shell as the container's own PID 1) - see
# Container#getPodCommand's own javadoc for why this exists: trusting the loaded image's own CMD
# unconditionally is a real footgun (confirmed live, yoga 2026-08-16 - a bare interactive shell with
# no TTY attached exits within milliseconds of a non-interactive `podman start`, landing on
# podman's own "Exited" state nspawnmgr never used to notice). Blank/empty $3 keeps the old
# behavior (trust the image's own CMD) for a template whose image already does the right thing on
# its own. Starting it is a separate step (plain `podman start`, NOPASSWD - see nspawnmgr.sudoers),
# not this script's job.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-podman-create-container.sh must be run as root." >&2
    exit 1
fi

if [ ! -f "$1" ]; then
    echo "Source template file not found: $1" >&2
    exit 1
fi
if ! command -v podman >/dev/null 2>&1; then
    echo "podman is not installed - see the Diagnostics page." >&2
    exit 1
fi

# podman load prints "Loaded image: <ref>" (or, on older versions, just the image ID) - the last
# whitespace-separated token on its final non-empty line is the reference either way - same
# parsing already used by nspawnmgr-podman-convert-podman-to-nspawn.sh.
LOADED_REF="$(podman load -i "$1" | tail -1 | awk '{print $NF}')"
if [ -z "$LOADED_REF" ]; then
    echo "Could not determine the loaded image reference from 'podman load' output." >&2
    exit 1
fi

# --dns/--dns-search: podman's own per-container /etc/resolv.conf overrides, independent of the
# nspawnbr0 network's own "dns_enabled": false (see nspawnmgr-configure-podman-network.sh's own
# comment for why that's off - aardvark-dns would conflict with nspawnmgr's own dnsmasq, already
# bound to this exact address). Nspawn containers get this same "10.100.0.1 nameserver, internal
# search domain" pair for free (DNS server via DHCP option 6, search domain via a baked-in template
# drop-in), but pods get neither - they've used host-local IPAM instead of DHCP ever since
# containers/netavark#1416 (see nspawnmgr-configure-podman-network.sh's own history), and there's no
# template-side drop-in for a podman image to bake in. Confirmed live (yoga, 2026-08-17): without
# this, a pod can ping its own bridge-assigned IP fine but "ping fed2.internal"/"ping
# nspawnmgr.internal" fail outright with "Name or service not known" - /etc/resolv.conf was simply
# never written at all.
# podman's own systemd auto-detection (--systemd=auto, the default) looks at the LITERAL
# top-level command being run - /sbin/init, /usr/sbin/init, /usr/local/sbin/init, or anything
# ending in "systemd" - and if it matches, transparently sets up what a systemd PID 1 needs
# (tmpfs at /run and /tmp, the right stop-signal, cgroup access) without needing --privileged.
# Wrapping every command in `sh -c "$3"` below defeats that detection outright, since the
# command podman actually sees is "sh", not "/sbin/init" - confirmed live (yoga, 2026-09-03): a
# pod created from an nspawn-converted template with Command=/sbin/init ran systemd with none
# of that support, and it repeatedly failed "Failed to set RLIMIT_CORE: Operation not
# permitted" until giving up (Exited 255). $4 = "1" is an explicit opt-in (a pod configuration
# checkbox, see Container#isPodSystemdEnabled's own javadoc) that forces the same
# --systemd=always handling regardless of the sh -c wrapping - deliberately NOT inferred from
# $3's own text (an earlier version of this script tried pattern-matching known init paths, but
# guessing "this command looks like init" is worse UX than just asking).
if [ "$4" = "1" ]; then
    SYSTEMD_FLAG="--systemd=always"
else
    SYSTEMD_FLAG=""
fi

if [ -n "$3" ]; then
    podman create --name "$2" --network nspawnbr0 --dns 10.100.0.1 --dns-search internal $SYSTEMD_FLAG "$LOADED_REF" sh -c "$3"
else
    podman create --name "$2" --network nspawnbr0 --dns 10.100.0.1 --dns-search internal $SYSTEMD_FLAG "$LOADED_REF"
fi
