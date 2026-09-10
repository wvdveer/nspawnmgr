#!/bin/sh
# Determines which of an uploaded local .deb's dependencies aren't already satisfied on the target
# container's rootfs, without installing anything - used by PackageCacheService's manual "Install
# package" flow so missing dependencies can be pre-fetched onto the host (see
# nspawnmgr-download-packages.sh) before the actual in-container install runs, the same way
# nspawnmgr's own SSH/RDP/VNC/desktop-manager provisioning already pre-fetches its own fixed
# package lists. Same -o Dir=$ROOTFS host-side-apt-against-the-container's-own-state trick those
# scripts already use - no chroot needed at all (unlike the DNF/RPM sibling), since the host itself
# already has real apt tooling.
#
# Offline-first pass ported from the DNF analogue after that one was proven live on acer's
# fedora-runner: before ever touching the network, this tries `apt-get install -s` (simulate,
# confirmed live to exit 0 on full success and never actually touch the network for that case)
# against the uploaded .deb plus whatever's already in the shared auto/uploaded cache dirs, adding
# candidates iteratively as apt's own "Depends: X but it is not installable" lines say what's still
# missing (see the loop below) - the exact same need-driven approach as
# nspawnmgr-simulate-install-dnf.sh, and for the same reason: blindly throwing every cached .deb at
# apt-get as an install target risks the identical class of bug that script's own header discusses
# (a completely unrelated/stale cached file poisoning an otherwise-fine transaction) even though the
# specific NEVRA-duplicate-conflict failure mode was RPM-specific and never actually confirmed for
# apt.
#
# Genuinely simpler than the DNF port in one respect: a .deb's own Depends: lines already name real
# package names (dpkg-shlibdeps resolves SONAMEs to package names at BUILD time), unlike an .rpm's
# raw "libfoo.so.6()(64bit)" capability strings that only the INSTALLING system's own rpm resolves -
# so there's no equivalent need for a slow "search every cached file's own Provides" fallback here;
# the fast name-based filename glob is expected to always be sufficient. Flag live if that turns out
# not to hold for some real package.
#
# $1 = target container's rootfs dir (host-visible path, e.g. /var/lib/machines/<name>)
# $2 = host-visible path to the uploaded .deb to simulate installing (NOT inside $1 - it lives in
#      the admin package cache; apt resolves this as a plain filesystem path, independent of -o Dir=)
# $3 = shared, persistent "auto" package-manager cache dir (e.g.
#      /var/cache/nspawnmgr/packages/apt/auto) - same convention nspawnmgr-download-packages.sh
#      already uses for its own $2.
# $4 = admin package cache's "uploaded" dir for apt (e.g. /var/cache/nspawnmgr/packages/apt/uploaded)
#      - PackageCacheService's own convention, same as nspawnmgr-download-packages.sh's own $3.
set -e
ROOTFS="$1"
DEB_PATH="$2"
CACHE_DIR="$3"
UPLOAD_DIR="$4"
mkdir -p "$CACHE_DIR" "$UPLOAD_DIR"

OWN_NAME="$(dpkg-deb -f "$DEB_PATH" Package)"
APT_OPTS="-o Dir=$ROOTFS -o Dir::State::status=$ROOTFS/var/lib/dpkg/status -o APT::Sandbox::User=root -o APT::Install-Recommends=false"

# Clear whatever's already in the container's own apt archive cache BEFORE either resolution path
# below runs - same reasoning and same unconditional placement as the DNF sibling's own fix: this
# script is only ever invoked once per "Install package" attempt (PackageCacheService always calls
# it first), so this is the one safe, shared place to guarantee a clean slate regardless of which
# path this specific install ends up taking, without touching nspawnmgr-download-packages.sh's own
# accumulate-across-calls behavior that ContainerFilesystemProvisioner's SSH/RDP/VNC/desktop-manager
# auto-installs may still rely on.
rm -f "$ROOTFS/var/cache/apt/archives"/*.deb

# Iterative, need-driven offline resolution - see nspawnmgr-simulate-install-dnf.sh's own header for
# the full rationale (naive "throw every cached file at the install command" was tried and failed
# live for that package manager). Start with ONLY the uploaded target; apt's own "Depends: X but it
# is not installable" lines say exactly which package names are still missing, so only ever look
# those up by name and add matching cached .debs, retrying until it converges or nothing new is
# found (capped at 15 rounds).
NEEDED_NAMES_SEEN=" $OWN_NAME "
CACHE_DEBS=""
add_deb_path() {
    case " $CACHE_DEBS " in
        *" $1 "*) return 0 ;;
    esac
    CACHE_DEBS="$CACHE_DEBS $1"
}
add_candidates_for_name() {
    # $1 = package name apt's own "Depends: X but it is not installable" reported as missing - a
    # real package name, not a raw capability string (see this script's own header) - so a plain
    # "<name>_*.deb" filename glob is expected to always be sufficient, matching the exact naming
    # convention nspawnmgr-download-packages.sh's own copy loop already uses.
    for f in "$CACHE_DIR/$1"_*.deb "$UPLOAD_DIR/$1"_*.deb; do
        # `[ -e "$f" ] || continue` (not `&&`) - confirmed live (acer, 2026-09-08): under this
        # script's `set -e`, a bare `[ -e "$f" ] && cmd` as a loop-body statement dies the instant
        # the glob genuinely has no match in one of the two directories (a literal, unexpanded
        # pattern that trivially fails -e) - the overwhelmingly common case, since a given package is
        # normally cached in only ONE of auto/uploaded, not both. `|| continue` is exempt from
        # errexit by construction: a true left side skips the right entirely, and a false left side
        # runs `continue`, which moves on before the compound's own status is ever consulted.
        [ -e "$f" ] || continue
        add_deb_path "$f"
    done
}

OFFLINE_OUTPUT=""
OFFLINE_EXIT=1
i=0
while [ "$i" -lt 15 ]; do
    i=$((i + 1))
    # Deliberately no `apt-get update` anywhere in this offline pass - confirmed live (acer,
    # 2026-09-08): against a container rootfs whose own apt package lists were never (re)fetched
    # here, `apt-get install -s` resolves purely from the explicitly-given local .deb files plus
    # whatever's already installed, reports a genuine "not installable" for anything else, and does
    # so without any observable network access - the same offline guarantee `--disablerepo='*'`
    # gives the DNF sibling, achieved here simply by never populating real repo indices to consult.
    if OFFLINE_OUTPUT="$(apt-get $APT_OPTS install -s -y "$DEB_PATH" $CACHE_DEBS 2>&1)"; then
        OFFLINE_EXIT=0
        break
    fi
    MISSING_NAMES="$(echo "$OFFLINE_OUTPUT" | sed -n 's/.*Depends: \([^ (]*\).*but it is not installable.*/\1/p' | sort -u)"
    ADDED=0
    for name in $MISSING_NAMES; do
        case " $NEEDED_NAMES_SEEN " in
            *" $name "*) continue ;;
        esac
        NEEDED_NAMES_SEEN="$NEEDED_NAMES_SEEN $name"
        BEFORE="$CACHE_DEBS"
        add_candidates_for_name "$name"
        [ "$CACHE_DEBS" = "$BEFORE" ] || ADDED=1
    done
    # Nothing new found locally this round - further looping can't help, stop now rather than
    # spinning to the iteration cap.
    [ "$ADDED" -eq 1 ] || break
done

if [ "$OFFLINE_EXIT" -eq 0 ]; then
    NEEDED_NAMES="$(echo "$OFFLINE_OUTPUT" | awk '/^Inst / {print $2}' | grep -vFx "$OWN_NAME" || true)"
    # Every one of these resolved purely from a local file already sitting in $CACHE_DIR/
    # $UPLOAD_DIR - copy it into the container's own apt archive cache now, since the download
    # script (which normally does this exact copy - see nspawnmgr-download-packages.sh's own end)
    # never runs when this function reports zero missing dependencies below. Already cleared of any
    # stale leftovers up top, before either resolution path ran.
    mkdir -p "$ROOTFS/var/cache/apt/archives"
    for name in $NEEDED_NAMES; do
        for f in "$CACHE_DIR/$name"_*.deb "$UPLOAD_DIR/$name"_*.deb; do
            # See add_candidates_for_name's own comment on why this must be `|| continue`, not `&&`.
            [ -e "$f" ] || continue
            cp -n "$f" "$ROOTFS/var/cache/apt/archives/" 2>/dev/null || true
        done
    done
    exit 0
fi

# Offline resolution above either found nothing to work with (empty cache) or couldn't fully resolve
# the transaction from local files alone - fall back to the original network-based simulate,
# unchanged from before this offline-first pass was added.
apt-get $APT_OPTS update >/dev/null
apt-get $APT_OPTS install -s -y "$DEB_PATH" 2>/dev/null | awk '/^Inst / {print $2}' | grep -vFx "$OWN_NAME" || true
