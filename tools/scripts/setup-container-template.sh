#!/usr/bin/env bash
# One-time (idempotent) preparation of a REAL, bootable systemd-nspawn template tarball for the
# real-container-lifecycle CI job (tools/scripts/real-lifecycle-test.sh) — the stub trees under
# site/templates/nspawn/{debian-minimal,alpine-minimal} (see site/templates/README.md) are fine for
# the fake/dev provisioner but can't actually boot.
#
# Downloads a Debian bookworm/amd64 "default" rootfs tarball from images.linuxcontainers.org (the
# standard, systemd-based rootfs source for systemd-nspawn/LXC — unlike Docker's images, which
# have no init system) into tools/downloads (cached, same convention as
# tools/scripts/setup-tomcat.sh), extracts it to a scratch directory, bakes openssh-server into it
# using the host's own apt (pointed at the tree via -o Dir=) rather than booting the tree itself —
# see the remote_sudo block below for why — then packs the result into
# site/templates/nspawn/debian-real.tar.gz, a machinectl import-tar-compatible gzipped tar
# (production/RealContainerFilesystemProvisioner clones templates the same way, via
# nspawnmgr-clone-template.sh). This lets the real create-container flow's SSH-into-container step
# (ProvisioningService.provisionSsh) succeed with openssh-server already installed+enabled, without
# needing any new host-side container networking (NAT/masquerade) to exist; the real container still
# gets its own isolated veth later via machinectl start's .nspawn VirtualEthernet=yes.
#
# Extraction/baking needs root (preserving device nodes/ownership, installing packages), so it
# runs over SSH as the sudo-capable account from dev_env/ssh-account.env — same account the app's
# own SshRemoteExecutor uses. That account must be able to read/write into this checkout's
# site/templates directory. Safe to re-run: skips the download+bake if the tarball already exists.
#
# Unlike the old live-directory-tree version of this script, the final artifact is a single file —
# nothing needs to traverse into it, so there's none of the permission-wrangling
# (chmod -R a+rwX / SSH-host-key re-tightening / StrictModes fights) the old approach needed just so
# the unprivileged CI job user could later move/delete it during its own checkout's wipe-and-restore.
#
# NOTE: images.linuxcontainers.org prunes older timestamped builds over time, so this script
# resolves the latest build dynamically rather than pinning one — if the image server's index page
# layout ever changes format, the `grep`/`sed` below will need updating.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib/ssh-sudo.sh
. "$root/tools/scripts/lib/ssh-sudo.sh"

DEBIAN_RELEASE="${DEBIAN_RELEASE:-bookworm}"
ARCH="${ARCH:-amd64}"
downloads_dir="$root/tools/downloads"
tarball_path="$root/site/templates/nspawn/debian-real.tar.gz"
# Baked as root (below), so the tarball comes out root-owned unless explicitly chowned back - and
# confirmed live, that breaks the checkout's own cache-preserve dance in .gitea/workflows/build.yml:
# moving a root-owned file through /tmp (sticky, mode 1777) as this unprivileged CI job user fails,
# since the sticky bit blocks removing/replacing directory entries you don't own even with full
# directory write permission. Chown the result back to whoever's actually running this script (the
# CI job user) so that round-trip keeps working.
owner_uid="$(id -u)"
owner_gid="$(id -g)"

if remote_test "-f '$tarball_path'"; then
    echo "Template already baked at $tarball_path, skipping."
    exit 0
fi

index_url="https://images.linuxcontainers.org/images/debian/${DEBIAN_RELEASE}/${ARCH}/default/"
echo "Resolving latest ${DEBIAN_RELEASE}/${ARCH} build from $index_url..."
# `|| true`: under `set -e` + `pipefail`, a failing pipeline inside a plain assignment aborts the
# script immediately, before the -z check below ever runs, silently exiting with no message at
# all — this makes a future format-drift (grep finding nothing) hit that check instead.
latest_build="$(curl -fsSL "$index_url" \
    | grep -oE 'href="[0-9]{8}_[0-9]{2}%3A[0-9]{2}/"' \
    | sed -E 's/href="(.*)\/"/\1/' \
    | sort | tail -1 || true)"
if [[ -z "$latest_build" ]]; then
    echo "Couldn't find a build under $index_url - the image server's layout may have changed; this script needs an update." >&2
    exit 1
fi
build_url="${index_url}${latest_build}/"
echo "Using build $latest_build"

mkdir -p "$downloads_dir"
archive_name="debian-${DEBIAN_RELEASE}-${ARCH}-${latest_build//%3A/}.tar.xz"
archive_path="$downloads_dir/$archive_name"

if [[ ! -f "$archive_path" ]]; then
    echo "Downloading rootfs..."
    curl -fsSL -o "$archive_path" "${build_url}rootfs.tar.xz"
    echo "Verifying checksum..."
    expected_sha="$(curl -fsSL "${build_url}SHA256SUMS" | awk '$2 == "rootfs.tar.xz" || $2 == "*rootfs.tar.xz" {print $1}')"
    if [[ -z "$expected_sha" ]]; then
        echo "Couldn't find rootfs.tar.xz's checksum in ${build_url}SHA256SUMS" >&2
        rm -f "$archive_path"
        exit 1
    fi
    actual_sha="$(sha256sum "$archive_path" | awk '{print $1}')"
    if [[ "$expected_sha" != "$actual_sha" ]]; then
        echo "Checksum mismatch for $archive_path (expected $expected_sha, got $actual_sha)" >&2
        rm -f "$archive_path"
        exit 1
    fi
else
    echo "Using cached $archive_path"
fi
# Only the remote_sudo block below actually needs root — this download itself doesn't, but if this
# script IS run as root (or any other user besides whoever runs CI jobs), tools/downloads/this
# archive would otherwise come out owned by that user, hitting the exact same
# CI-job-user-can't-move-it-during-checkout problem the old template bake used to hit too.
chmod -R a+rwX "$downloads_dir"

echo "Extracting and baking openssh-server, then packing into $tarball_path (as the sudo-capable account)..."
remote_sudo "
# chmod (not just mkdir -p): if this directory doesn't already exist as a git-tracked path (it
# normally does, alongside the dev stub templates - see site/templates/README.md), creating it here
# as root would otherwise leave it non-writable by the unprivileged CI job user, which would break
# that user's own ability to move/delete the tarball this block writes below during its next
# checkout's cache-preserve step - the exact class of problem this tarball-based approach exists to
# avoid in the first place.
mkdir -p '$root/site/templates/nspawn'
chmod a+rwx '$root/site/templates/nspawn'
rootfs=\"\$(mktemp -d)\"
trap 'rm -rf \"\$rootfs\"' EXIT
tar -xpf '$archive_path' -C \"\$rootfs\"
# Deliberately NOT 'systemd-nspawn -D ... apt-get ...': that runs apt's network traffic (including
# DNS resolution) from inside the container's mount/network setup, which is confirmed live to be
# unreliable on at least one real host (apt-get update failed with \"Temporary failure resolving
# 'deb.debian.org'\" even though the host's own DNS and the rootfs curl download above both work
# fine). Pointing apt's Dir at the scratch rootfs directly runs apt as an ordinary host process
# instead - using the host's own working network/DNS - and only chroots (via dpkg) for the final
# unpack/configure step, once the packages are already downloaded. Downloading happens outside the
# container; only the already-fetched files get transferred in.
export DEBIAN_FRONTEND=noninteractive
# apt's official --root= flag turned out to be unusable here: confirmed live (CI run #80), this
# host's apt (2.8.3, Linux Mint 22.1) rejects it outright with \"Command line option --root=... is
# not understood in combination with the other options\" - reproduced even as genuine root, with
# --root as the ONLY option, and with plain 'apt' instead of 'apt-get', so it's not a
# flag-combination or privilege issue, apt on this host just doesn't accept it. Falling back to a
# manual -o Dir=/-o Dir::State::status= combo (apt's own package resolution) PLUS
# -o DPkg::Options::=--root= (forces the dpkg *subprocess* apt spawns to also chroot there for
# unpack/configure - the piece a plain -o Dir= combo is missing on its own). Without that
# DPkg::Options push-through (confirmed live, CI run #79), dpkg validated downloaded Debian bookworm
# packages against the HOST's own (Ubuntu) dpkg database instead of the scratch rootfs's - \"dpkg:
# dependency problems ... ncurses-base (6.4+20240113-1ubuntu2) breaks ncurses-term\" was the host's
# own installed Ubuntu package colliding with the bookworm one being unpacked.
# -o APT::Sandbox::User=root: confirmed live (CI run #77), without this apt drops privileges to the
# unprivileged '_apt' user for the actual download step (its usual security sandboxing) - which
# fails with \"couldn't be accessed by user '_apt' ... Permission denied\" because the scratch
# rootfs's var/lib/apt/lists and var/cache/apt/archives are only writable by root, having just been
# extracted from the rootfs tarball as-is. That sandboxing exists to protect the host's real root
# filesystem from a malicious/compromised download; not needed here since this whole block already
# runs as root building a throwaway CI fixture, not installing onto the live system.
APT_OPTS=\"-o Dir=\$rootfs -o Dir::State::status=\$rootfs/var/lib/dpkg/status -o APT::Sandbox::User=root -o DPkg::Options::=--root=\$rootfs\"

# A freshly-extracted rootfs tarball has no /dev entries (device nodes aren't portable inside a
# plain tar archive). systemd-nspawn used to populate these automatically when this bake ran inside
# it; now that apt/dpkg run as a normal host process against the scratch rootfs, they need to exist
# upfront - confirmed live (CI run #80), openssh-server's postinst failed with \"Can't open
# /dev/null: No such file or directory\" (a shell redirect, needs the actual path to exist) without
# this. Same minimal set debootstrap itself creates.
mkdir -p \"\$rootfs/dev\"
mknod -m 666 \"\$rootfs/dev/null\" c 1 3
mknod -m 666 \"\$rootfs/dev/zero\" c 1 5
mknod -m 666 \"\$rootfs/dev/random\" c 1 8
mknod -m 666 \"\$rootfs/dev/urandom\" c 1 9
mknod -m 666 \"\$rootfs/dev/tty\" c 5 0

apt-get \$APT_OPTS update
apt-get \$APT_OPTS install -y openssh-server
# Without net.ipv4.ping_group_range set, ping from inside the container fails - confirmed live,
# container-to-container ping tests failing even once name resolution itself worked. The package
# that normally sets this (linux-sysctl-defaults) isn't available for bookworm outside
# backports/trixie+ though (confirmed live: apt-get install fails with \"Unable to locate
# package\"), so write the one line it would have installed directly instead of depending on a
# package that doesn't exist for this release.
# \"0 2147483647\" (the usual \"no limit\" idiom) fails with EINVAL when written from inside a
# systemd-nspawn container - confirmed live, the container's own user-namespace only maps a
# 65536-wide GID range, so the kernel rejects anything wider and silently keeps its own default
# (65534 65534, i.e. ping still broken) instead. \"0 65535\" fits within that mapped range.
mkdir -p \"\$rootfs/etc/sysctl.d\"
echo \"net.ipv4.ping_group_range = 0 65535\" > \"\$rootfs/etc/sysctl.d/50-ping-group-range.conf\"
# Without a routing/search domain, systemd-resolved inside the container only ever resolves bare
# container names (e.g. \"b2\") via LLMNR multicast, never via the real nspawnbr0 DNS server -
# confirmed live via 'resolvectl log-level debug' + journalctl: it fired LLMNR transactions only,
# never even attempting the \"dns\" scope, despite the scope being on the link's default route. This
# is systemd-resolved's own documented behaviour for single-label (undotted) names: they're only
# routed to a configured DNS server if the link has a routing/search domain to qualify them with.
# DHCP could supply this (option 15), but that needs the client-side .network file to opt in with
# UseDomains=yes too, and /usr/lib/systemd/network/80-container-host0.network is systemd-nspawn's own
# generated file, not something this template controls. A static drop-in (merged by filename, like a
# systemd unit drop-in) sidesteps DHCP entirely: Domains=internal marks nspawnbr0's own dnsmasq
# (10.100.0.1, this domain's DNS server per resolvectl status) as authoritative for .internal,
# including for bare names.
mkdir -p \"\$rootfs/etc/systemd/network/80-container-host0.network.d\"
printf '[Network]\nDomains=internal\n' > \"\$rootfs/etc/systemd/network/80-container-host0.network.d/nspawnmgr.conf\"
# Belt and braces: the postinst above already enables the service via deb-systemd-helper, but do it
# explicitly too - systemctl enable only manipulates unit-file symlinks, so it's safe chrooted with
# no init process behind it.
chroot \"\$rootfs\" systemctl enable ssh
# Without this, host0 never picks up the stock 80-container-host0.network's DHCP config
# (debootstrap-derived rootfs images don't enable networkd by default), so the container never gets
# an address on its own veth at all - not just slow, genuinely never. That's no longer just an
# outbound-internet nicety: nspawnmgr's own readiness check and guacd both dial this address
# directly now (see docs/administrator-guide.md's Container networking section), so without it a
# container never leaves BOOTING.
chroot \"\$rootfs\" systemctl enable systemd-networkd

# Pack into a machinectl import-tar-compatible gzipped tar - --numeric-owner keeps uid/gid numeric
# in the archive so machinectl doesn't need matching /etc/passwd entries on the importing host to
# make sense of ownership. tar to a temp file first and rename into place, so a checkout's cache-
# preserve step (.gitea/workflows/build.yml) never observes a partially-written tarball.
tar -czf '$tarball_path.tmp' --numeric-owner -C \"\$rootfs\" .
mv '$tarball_path.tmp' '$tarball_path'
chown $owner_uid:$owner_gid '$tarball_path'
chmod a+r '$tarball_path'
"

echo "Template ready at $tarball_path"
