#!/bin/sh
# Downloads a Fedora minirootfs from images.linuxcontainers.org, extracts it, and bakes
# openssh-server into it.
#
# If the HOST itself is Fedora/RHEL-family (has dnf), installs it as a normal host process pointed
# at --installroot= - dnf's own official, documented mechanism for exactly this. Otherwise
# (nspawnmgr is deployed on some other distro entirely - Debian, Arch, ...), falls back to
# chrooting into the rootfs and using ITS OWN bundled dnf instead - the image already carries its
# own copy, so there's nothing to install on the host at all. Confirmed live: the host running
# nspawnmgr is typically Debian/Ubuntu-based, where the host-side branch alone 404'd immediately
# with "Required command 'dnf' not found." - the chroot fallback exists specifically for that case.
# UNVERIFIED either way: no Fedora/RHEL host exists anywhere in this project's test environment
# (unlike nspawnmgr-create-debian-template.sh's own host-side branch, confirmed live multiple
# times), so neither branch here has actually been exercised against a real container.
#
# $1 = target .tar.gz path (must not already exist) - a machinectl import-tar-compatible gzipped
# tar, not a live directory.
#
# Requires a sudo password (see ContainerFilesystemProvisioner.createMinimalTemplate) —
# creation-time-only, invoked from the Templates admin page's "Set up fedora-minimal" button.
set -e

TARGET="$1"
ARCH="$(uname -m)"
# images.linuxcontainers.org names architectures its own way (amd64/arm64), not uname -m's
# (x86_64/aarch64).
case "$ARCH" in
    x86_64) ARCH="amd64" ;;
    aarch64) ARCH="arm64" ;;
esac
# Fedora 43, not the newer 44 - not because 44 itself is broken (confirmed live: 43 hits the exact
# same SSH/PAM issue, see the pam_permit.so fix further down; the release number turned out not to
# be the actual factor). Kept at 43 simply because that's the combination actually confirmed
# working end-to-end live, without also re-verifying 44 unnecessarily. There's no "current"-style
# rolling alias for Fedora the way Arch has (see nspawnmgr-create-arch-template.sh), so this will
# need bumping again once 43 ages out of images.linuxcontainers.org's retention.
FEDORA_RELEASE="43"
INDEX_URL="https://images.linuxcontainers.org/images/fedora/${FEDORA_RELEASE}/${ARCH}/default/"

if [ -e "$TARGET" ]; then
    echo "Target file already exists" >&2
    exit 1
fi

for cmd in curl tar sha256sum chroot mount umount; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Required command '$cmd' not found." >&2
        exit 1
    fi
done

# Same "resolve the newest build actually published" reasoning as
# nspawnmgr-create-debian-template.sh: images.linuxcontainers.org prunes older timestamped builds
# over time, so pinning one would need periodic bumping to keep working.
LATEST_BUILD="$(curl -fsSL "$INDEX_URL" \
    | grep -oE 'href="[0-9]{8}_[0-9]{2}%3A[0-9]{2}/"' \
    | sed -E 's/href="(.*)\/"/\1/' \
    | sort | tail -1)"
if [ -z "$LATEST_BUILD" ]; then
    echo "Couldn't find a build under $INDEX_URL - the image server's layout may have changed." >&2
    exit 1
fi
BUILD_URL="${INDEX_URL}${LATEST_BUILD}/"

WORK_DIR="$(mktemp -d)"
ROOTFS="$WORK_DIR/rootfs"
# Unmount before the work dir gets removed - rm -rf on a directory with active bind mounts inside
# it would either fail or (worse) delete the mount point out from under an active mount. `|| true`
# on each: if the script fails before a given mount was even set up (or the host-side branch below
# never mounted anything at all), that umount is a harmless no-op, not a real error.
cleanup() {
    umount "$ROOTFS/sys" 2>/dev/null || true
    umount "$ROOTFS/proc" 2>/dev/null || true
    umount "$ROOTFS/dev" 2>/dev/null || true
    umount "$ROOTFS/run" 2>/dev/null || true
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

echo "Downloading rootfs from $BUILD_URL..."
curl -fsSL -o "$WORK_DIR/rootfs.tar.xz" "${BUILD_URL}rootfs.tar.xz"
EXPECTED="$(curl -fsSL "${BUILD_URL}SHA256SUMS" | awk '$2 == "rootfs.tar.xz" || $2 == "*rootfs.tar.xz" {print $1}')"
if [ -z "$EXPECTED" ]; then
    echo "Couldn't find rootfs.tar.xz's checksum in ${BUILD_URL}SHA256SUMS" >&2
    exit 1
fi
ACTUAL="$(sha256sum "$WORK_DIR/rootfs.tar.xz" | awk '{print $1}')"
if [ "$EXPECTED" != "$ACTUAL" ]; then
    echo "Checksum mismatch for rootfs.tar.xz — expected $EXPECTED, got $ACTUAL. Aborting." >&2
    exit 1
fi

mkdir -p "$ROOTFS"
tar -xpf "$WORK_DIR/rootfs.tar.xz" -C "$ROOTFS"

# Belt-and-braces baseline device nodes for the FINAL packed template (systemd-nspawn sets up its
# own private /dev at container boot regardless of what's baked in here, but keep this for
# consistency with nspawnmgr-create-debian-template.sh, and in case anything other than
# systemd-nspawn ever imports this rootfs). Not load-bearing for dnf itself either way - the
# host-side branch below uses --installroot= (full access to the host's own real /dev), and the
# chroot branch gets a real, fully populated /dev via its own bind mount.
mkdir -p "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys"
mknod -m 666 "$ROOTFS/dev/null" c 1 3
mknod -m 666 "$ROOTFS/dev/zero" c 1 5
mknod -m 666 "$ROOTFS/dev/random" c 1 8
mknod -m 666 "$ROOTFS/dev/urandom" c 1 9
mknod -m 666 "$ROOTFS/dev/tty" c 5 0

echo "Installing openssh-server into the template..."
if command -v dnf >/dev/null 2>&1; then
    # Host is Fedora/RHEL-family - run dnf as a normal HOST process against --installroot=, same
    # rationale as the Debian script's own host-side apt branch: uses the host's own working
    # network/DNS instead of relying on in-container network setup. --installroot reads
    # $ROOTFS/etc/yum.repos.d/*.repo (already present in the downloaded image, pointing at real
    # Fedora mirrors) to know what repos to use - no manual sources-list wrangling needed, unlike
    # apt's own -o Dir= trick.
    DNF_OPTS="--installroot=$ROOTFS --releasever=$FEDORA_RELEASE --assumeyes"
    dnf $DNF_OPTS makecache
    dnf $DNF_OPTS install openssh-server
else
    # Host has no dnf at all (nspawnmgr deployed on a Debian or Arch host, say) - chroot into the
    # rootfs and use ITS OWN bundled dnf instead. chroot doesn't share the host's network config
    # automatically - dnf needs $ROOTFS's own resolv.conf to resolve Fedora's mirrors. /proc and
    # /sys get their own fresh mounts (not bind mounts of the host's) - dnf's own disk-space/
    # hardware checks and some packages' post-install scriptlets expect them to exist, the same
    # reason pacstrap/arch-chroot set these up before running a package manager. These three
    # unmount again right after the install, deliberately NOT left mounted until the trap-based
    # cleanup at script exit - packing the rootfs into $TARGET further down must never capture a
    # live snapshot of the host's own /proc or /dev. --installroot is gone here: we're not pointing
    # an external dnf at $ROOTFS anymore, we ARE chrooted into it, so plain dnf commands apply to
    # $ROOTFS as if it were /.
    # --remove-destination: confirmed live, the Fedora image's own /etc/resolv.conf ships as a
    # symlink to the absolute path "/etc/resolv.conf" (a placeholder meant to be replaced by
    # whatever container runtime boots it) - outside a chroot that resolves right back to the
    # HOST's own real /etc/resolv.conf, so a plain `cp` sees source and destination as the same
    # inode and refuses with "are the same file". --remove-destination unlinks the destination
    # path first, so cp never has to open-and-follow that symlink at all.
    cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
    # /run bind mount: confirmed live for the Arch script's own chroot branch hitting this exact
    # issue ("Could not resolve host"), applied here defensively too. Fedora's default
    # /etc/nsswitch.conf also lists "resolve" ahead of "dns" - nss-resolve doesn't read
    # /etc/resolv.conf at all, it talks to systemd-resolved over the socket it publishes under
    # /run. Since the chroot's own /run was untouched (freshly extracted, essentially empty), that
    # socket was never reachable. Real `dnf --installroot=`-adjacent bootstrap tooling (and
    # arch-chroot on the Arch side) bind-mount /run for exactly this reason.
    mkdir -p "$ROOTFS/run"
    mount --bind /run "$ROOTFS/run"
    mount --bind /dev "$ROOTFS/dev"
    mount -t proc proc "$ROOTFS/proc"
    mount -t sysfs sys "$ROOTFS/sys"
    chroot "$ROOTFS" dnf --releasever="$FEDORA_RELEASE" --assumeyes makecache
    chroot "$ROOTFS" dnf --releasever="$FEDORA_RELEASE" --assumeyes install openssh-server
    umount "$ROOTFS/sys"
    umount "$ROOTFS/proc"
    umount "$ROOTFS/dev"
    umount "$ROOTFS/run"
    # The resolv.conf copy above is build-time-only glue for dnf to reach Fedora's mirrors from
    # inside this chroot - it must not survive into the packed template (see
    # nspawnmgr-create-debian-template.sh's own comment on this exact fix for the full reasoning:
    # left in place, it freezes every future container's /etc/resolv.conf to whatever this BUILD
    # host's resolver was at bake time, permanently locking out systemd-resolved's live per-link
    # management). Restore the standard systemd-resolved-managed symlink before packing.
    ln -sf ../run/systemd/resolve/stub-resolv.conf "$ROOTFS/etc/resolv.conf"
fi

# Same two systemd-nspawn-level workarounds nspawnmgr-create-debian-template.sh needed, confirmed
# live for Debian - these are about systemd-networkd/systemd-nspawn's own generated network config,
# not anything Debian-specific, so the same fix should apply to any systemd-based rootfs. UNVERIFIED
# for Fedora specifically though - flag if a live Fedora container still can't ping or resolve
# other containers by name despite this.
mkdir -p "$ROOTFS/etc/sysctl.d"
echo "net.ipv4.ping_group_range = 0 65535" > "$ROOTFS/etc/sysctl.d/50-ping-group-range.conf"
mkdir -p "$ROOTFS/etc/systemd/network/80-container-host0.network.d"
printf '[Network]\nDomains=internal\n' > "$ROOTFS/etc/systemd/network/80-container-host0.network.d/nspawnmgr.conf"

# Fedora's OpenSSH service unit is sshd.service, not ssh.service (Debian's own naming convention
# doesn't apply here). systemctl enable doesn't need a running systemd (or a bus connection) to
# manipulate unit-file symlinks, so this is safe to run chrooted with no init process behind it -
# confirmed live for the same call in the Debian script.
chroot "$ROOTFS" systemctl enable sshd

# Confirmed live: every SSH pubkey login into a real, booted Fedora container (43 and 44 both -
# not a single-release quirk) was rejected with "Access denied for user <account> by PAM account
# configuration [preauth]" - pam_unix's account phase (pam_acct_mgmt) returning
# PAM_AUTHINFO_UNAVAIL. The account, its password, and its authorized_keys were all genuinely
# correct; unix_chkpwd itself (the setuid helper pam_unix's account phase shells out to, to safely
# read /etc/shadow) refuses to run with "This binary is not designed for running in this way" -
# some caller-legitimacy hardening in Fedora's current shadow-utils build that doesn't tolerate
# running inside a systemd-nspawn container. Setting `UsePAM no` in sshd_config does NOT work
# around this - confirmed live, sshd's own privileged monitor process still calls do_pam_account
# unconditionally on this build (sshd itself warns "'UsePAM no' is not supported in this build").
# The actual fix: point sshd's own account phase at pam_permit.so (always succeeds) instead of
# password-auth's pam_unix.so, bypassing the broken shadow lookup entirely - scoped to sshd only,
# not a system-wide pam.d change. This only removes PAM's *account*-phase checks (expiration,
# nologin, etc.) for SSH specifically; the real identity check (pubkey verification) is untouched
# and already succeeds on its own before this phase ever runs, so this is a narrow, deliberate
# trade-off for these throwaway provisioned admin accounts, not a blanket PAM bypass.
sed -i 's/^account.*include.*password-auth/account required pam_permit.so/' "$ROOTFS/etc/pam.d/sshd"

# Confirmed live (fed1, 2026-08-14): RDP via xrdp's Xvnc session type (see the pam_nspawnmgr
# feature) got as far as actually starting the X server, then the window manager exited in under a
# second every time - "Failed to connect to user scope bus via local transport: No such file or
# directory" in .xsession-errors, and journalctl showed user@<uid>.service itself failing with
# status=224/PAM: "unix_chkpwd: could not obtain user info" / "PAM failed: Authentication service
# cannot retrieve authentication info" - the exact same broken-shadow-read symptom as the sshd fix
# above, just hitting a different PAM service name. Root cause: /etc/pam.d/systemd-user (which
# user@.service authenticates against via PAMName=systemd-user, and whose session stack is what
# actually sets up XDG_RUNTIME_DIR/the D-Bus user session bus - loginctl enable-linger alone only
# keeps the *directory* around, it doesn't make this succeed) doesn't exist on this rootfs at all -
# confirmed live that Fedora normally generates it via `authselect`, which this minimal
# `dnf --installroot` build never runs, so PAM falls through to whatever it does for a genuinely
# missing service config rather than /etc/pam.d/other (which is plain pam_deny and would have
# failed differently). Fixed the same way as sshd above: write a minimal systemd-user config with
# pam_permit.so standing in for the broken pam_unix.so account check, keeping the real
# pam_systemd.so session line intact - this is standard authselect-generated content for this file
# on a working Fedora install, not a stripped-down substitute.
cat > "$ROOTFS/etc/pam.d/systemd-user" <<'EOF'
#%PAM-1.0
account  required pam_permit.so
session  optional pam_keyinit.so revoke
session  required pam_limits.so
session  required pam_systemd.so
session  required pam_umask.so
EOF

# Confirmed live: every SSH session into a booted Fedora container showed a garbled prompt full of
# raw escape-sequence text ("start=<uuid>;machineid=<uuid>;user=...;type=shell;cwd=...") instead of
# a plain "[user@host ~]$" one. Root cause: systemd 257+ ships /usr/lib/systemd/profile.d/
# 80-systemd-osc-context.sh (symlinked into /etc/profile.d/ by systemd-tmpfiles), which emits an
# OSC 3008 "Hierarchical Context Signalling" escape sequence on every prompt - Guacamole's own
# terminal emulator doesn't recognize/strip it, so it prints as literal text. The script only skips
# itself when $TERM is unset or "dumb" (see its own header comment), and Guacamole's SSH client
# reports a real $TERM (e.g. "linux"), so it always fires. Disabled the documented way (the script's
# own header comment gives this exact procedure): remove the profile.d symlink and mask the
# tmpfiles.d snippet that recreates it, so a plain re-run of systemd-tmpfiles doesn't undo this.
if [ -h "$ROOTFS/etc/profile.d/80-systemd-osc-context.sh" ]; then
    rm -f "$ROOTFS/etc/profile.d/80-systemd-osc-context.sh"
    ln -sf /dev/null "$ROOTFS/etc/tmpfiles.d/20-systemd-osc-context.conf"
fi

mkdir -p "$(dirname "$TARGET")"
tar -czf "$TARGET" --numeric-owner -C "$ROOTFS" .

echo "Fedora $FEDORA_RELEASE minirootfs ready at $TARGET"
