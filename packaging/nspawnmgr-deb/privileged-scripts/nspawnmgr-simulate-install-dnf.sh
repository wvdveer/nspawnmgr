#!/bin/sh
# DNF analogue of nspawnmgr-simulate-install.sh: determines which of an uploaded local .rpm's
# dependencies aren't already satisfied on the target container, without installing anything. Run
# via a HOST-side chroot into the container's rootfs - NOT `systemd-run --machine=` (in-container
# execution) - same conversion nspawnmgr-download-packages-dnf.sh's own sibling already got: a real
# provisioning run confirmed downloads/syncs issued from inside a container's own network namespace
# are unreliable even with working host-level network/DNS, and this script's own `dnf makecache`
# call hits the network exactly the same way.
#
# The uploaded .rpm itself lives in the host-side admin package cache, not inside the container's
# own rootfs, so it's copied in to a scratch location first and referenced by its in-rootfs path
# (chroot makes "in-container path" and "path relative to $ROOTFS" the same thing) from there on.
#
# Offline-first pass added after a real, live failure on acer's fedora-runner: this script always
# used to jump straight to `dnf makecache` (network required) regardless of what an admin had
# already pre-seeded into the shared auto/uploaded cache dirs (see PackageCacheService's own javadoc
# on that pre-seeding workflow, and nspawnmgr-download-packages-dnf.sh's own header on why a
# container's own dnf download can't be relied on either) - so a container with genuinely no
# outbound internet could never benefit from that pre-seeding at all, even when every single needed
# .rpm was already sitting right there on the host. Before touching the network, this now tries a
# purely local resolution (rpm db + explicitly given files, `--disablerepo='*'`, no network) - see
# the offline-resolution loop below for why it's iterative/need-driven rather than just throwing
# every cached .rpm at dnf as an install target (that naive version was tried and failed live,
# twice, for two different reasons - both documented at the loop itself). If dnf can fully resolve
# the transaction this way, nothing needs downloading at all: this copies the resolved dependency
# files straight into the container's own /var/cache/dnf/ (the exact spot PackageCacheService's own
# final local-install command already globs - see its LOCAL_INSTALL_COMMAND javadoc) and reports
# zero missing dependencies, so the network-dependent path below never runs. If offline resolution
# can't converge (some dependency has no matching local file anywhere in the cache), this falls
# through to the original network-based simulate unchanged - exactly today's existing behavior,
# preserved as a fallback.
#
# UNVERIFIED beyond the fixes above: this script's own dependency-simulation logic was already
# speculative before any of them and stays exactly as speculative after them.
#
# $1 = target container's rootfs dir (host-visible path, e.g. /var/lib/machines/<name>) - no longer
#      needs the container's own machinectl/systemd-nspawn name at all.
# $2 = host-visible path to the uploaded .rpm to simulate installing (NOT inside $1 - it lives in
#      the admin package cache)
# $3 = shared, persistent "auto" package-manager cache dir (e.g.
#      /var/cache/nspawnmgr/packages/dnf/auto) - same convention nspawnmgr-download-packages-dnf.sh
#      already uses for its own $2.
# $4 = admin package cache's "uploaded" dir for dnf (e.g. /var/cache/nspawnmgr/packages/dnf/uploaded)
#      - PackageCacheService's own convention, same as nspawnmgr-download-packages-dnf.sh's own $3.
set -e
ROOTFS="$1"
RPM_PATH="$2"
CACHE_DIR="$3"
UPLOAD_DIR="$4"
mkdir -p "$CACHE_DIR" "$UPLOAD_DIR"

IN_CONTAINER_SCRATCH=/var/cache/nspawnmgr-simulate
HOST_SCRATCH="$ROOTFS$IN_CONTAINER_SCRATCH"
rm -rf "$HOST_SCRATCH"
mkdir -p "$HOST_SCRATCH"
cp "$RPM_PATH" "$HOST_SCRATCH/"
IN_CONTAINER_RPM="$IN_CONTAINER_SCRATCH/$(basename "$RPM_PATH")"

# Same convention nspawnmgr-download-packages-dnf.sh uses for its own single cache bind mount - two
# separate mount points here since this script (unlike the download script) needs both the auto and
# uploaded dirs visible at once for the offline candidate pool.
IN_CONTAINER_CACHEDIR=/var/cache/nspawnmgr-shared-cache
IN_CONTAINER_UPLOADDIR=/var/cache/nspawnmgr-shared-uploaded
HOST_CACHE_TARGET="$ROOTFS$IN_CONTAINER_CACHEDIR"
HOST_UPLOAD_TARGET="$ROOTFS$IN_CONTAINER_UPLOADDIR"
mkdir -p "$HOST_CACHE_TARGET" "$HOST_UPLOAD_TARGET"

# Same chroot setup nspawnmgr-download-packages-dnf.sh uses - see that script's own comments for the
# full rationale of each mount/copy.
cleanup() {
    umount "$HOST_CACHE_TARGET" 2>/dev/null || true
    umount "$HOST_UPLOAD_TARGET" 2>/dev/null || true
    umount "$ROOTFS/sys" 2>/dev/null || true
    umount "$ROOTFS/proc" 2>/dev/null || true
    umount "$ROOTFS/dev" 2>/dev/null || true
    umount "$ROOTFS/run" 2>/dev/null || true
    rm -rf "$HOST_SCRATCH"
    # PROVIDES_MAP is declared further down and only ever set to a real mktemp path once
    # ensure_provides_map() actually runs - referencing it here (a function body, evaluated at call
    # time via the EXIT trap, not at this definition point) still correctly picks up whatever value
    # it holds by the time cleanup actually fires, empty or not.
    #
    # `|| true` is not cosmetic here - confirmed live (acer, fedora-runner, 2026-09-08) via a direct
    # empirical test against this host's own /bin/sh: under `set -e`, a FALSE last command inside an
    # EXIT trap silently overrides an explicit `exit 0` from earlier in the script. Without this,
    # every offline-resolution success where PROVIDES_MAP was never needed (the common case - see
    # add_candidates_for_name's fast path) turned `exit 0` into a reported exit 1 with completely
    # empty stdout/stderr, since `[ -n "$PROVIDES_MAP" ]` alone is false whenever it's still "".
    [ -n "$PROVIDES_MAP" ] && rm -f "$PROVIDES_MAP"
    true
}
trap cleanup EXIT
cp --remove-destination /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
mkdir -p "$ROOTFS/run"
mount --bind /run "$ROOTFS/run"
mount --bind /dev "$ROOTFS/dev"
mount -t proc proc "$ROOTFS/proc"
mount -t sysfs sys "$ROOTFS/sys"
mount --bind "$CACHE_DIR" "$HOST_CACHE_TARGET"
mount --bind "$UPLOAD_DIR" "$HOST_UPLOAD_TARGET"

# Clear whatever's already in the container's own dnf cache BEFORE either resolution path below
# gets a chance to run - confirmed live (acer, fedora-runner, 2026-09-08) this needs to happen here,
# unconditionally, not only in this script's own offline-success branch (which fixed the case where
# offline resolution converges, but NOT the case where it falls through to the network-based
# fallback below, which then calls nspawnmgr-download-packages-dnf.sh - that script's own
# copy-into-cache step is deliberately left purely additive, since it's shared with
# ContainerFilesystemProvisioner's own SSH/RDP/VNC/desktop-manager auto-installs, which may
# legitimately need multiple downloads to accumulate across separate calls before one combined
# install). This script, unlike that one, is ONLY ever invoked once per "Install package" attempt
# (PackageCacheService.installOnContainer calls this first, always, before conditionally calling the
# download script) - the one safe, shared place to guarantee a clean slate for this specific install
# regardless of which path it ends up taking, without touching the download script's own behavior
# that provisioning still relies on.
rm -f "$ROOTFS/var/cache/dnf"/*.rpm

OWN_NAME="$(chroot "$ROOTFS" rpm -qp --queryformat '%{NAME}' "$IN_CONTAINER_RPM")"

# Iterative, need-driven offline resolution - NOT "throw every cached .rpm at dnf as an explicit
# install target". That naive approach was tried and confirmed broken twice, live, on acer's
# fedora-runner: (1) `dnf install file1 file2 ...` treats every single file as something to
# INSTALL, not merely as an optional dependency source, so two files with identical NEVRA (which
# happens routinely - see below) collide as "cannot install both X from @commandline and X from
# @commandline - conflicting requests"; (2) any unrelated, stale, or plain wrong .rpm sitting
# anywhere in the cache (e.g. a leftover mis-targeted upload from an earlier admin mistake) gets
# force-included too, and if *its own* dependencies can't be satisfied, that poisons the entire
# transaction even though nothing actually wanted that package installed at all.
#
# Instead: start with ONLY the uploaded target. If dnf can't resolve it, its own "nothing provides
# X" lines say exactly which capability names are still missing - look those up by package name in
# the cache dirs, add only the matching files, and retry. This converges quickly for a real
# dependency chain (each round adds only what the previous round's own resolver said it needed) and
# a completely unrelated cache file (like that stale mis-targeted upload) is never pulled in at all,
# since nothing ever asks for it by name. `createrepo_c` (which would let a real local file:// repo
# do this same job through dnf's own normal resolver instead of this manual loop) isn't installed
# in this minimal image and would itself need network to fetch - not an option here.
NEEDED_NAMES_SEEN=" $OWN_NAME "
CACHE_RPMS=""
# Several distinct missing symbol requirements (e.g. "libc.so.6", "libc.so.6(GLIBC_2.0)",
# "libc.so.6(GLIBC_2.1.3)" - all real, separate "nothing provides" lines for one single dependency)
# routinely resolve to the SAME provider file via the slow provides-lookup path below - without this
# guard, that file would get added to CACHE_RPMS once per distinct symbol name, reintroducing the
# exact "cannot install both X from @commandline and X from @commandline" duplicate-NEVRA conflict
# this whole rework exists to avoid. Every append to CACHE_RPMS goes through this.
add_rpm_path() {
    case " $CACHE_RPMS " in
        *" $1 "*) return 0 ;;
    esac
    CACHE_RPMS="$CACHE_RPMS $1"
}
# Lazily-built, script-lifetime cache of every cached .rpm's own `rpm -qp --provides` output, one
# "<capability>\t<in-container-path>" line per provide - built on first need (see
# add_candidates_for_name's fallback below), not unconditionally, since most rounds resolve via the
# cheap filename-glob path alone and never need it. Deliberately a temp FILE, not a shell variable:
# POSIX sh has no associative arrays, and this can run to a few thousand lines for a large cache.
PROVIDES_MAP=""
ensure_provides_map() {
    # `if`, not `[ cond ] && return 0` - same defensive fix as every other bare `&&` in this pass
    # (see add_candidates_for_name's own copy of this exact comment below for the fuller rationale).
    if [ -n "$PROVIDES_MAP" ]; then
        return 0
    fi
    PROVIDES_MAP="$(mktemp)"
    for f in "$HOST_CACHE_TARGET"/*.rpm "$HOST_UPLOAD_TARGET"/*.rpm; do
        [ -e "$f" ] || continue
        case "$f" in
            "$HOST_CACHE_TARGET"/*) inpath="$IN_CONTAINER_CACHEDIR/$(basename "$f")" ;;
            *) inpath="$IN_CONTAINER_UPLOADDIR/$(basename "$f")" ;;
        esac
        chroot "$ROOTFS" rpm -qp --provides "$f" 2>/dev/null | awk -v p="$inpath" -F'[[:space:]]+' '{print $1"\t"p}' >> "$PROVIDES_MAP"
    done
}
add_candidates_for_name() {
    # $1 = a name dnf's own "nothing provides X" reported as missing - usually a plain package name
    # ("less", "libgit2"), but just as often a raw SONAME/capability string like "libc.so.6" or
    # "libc.so.6(GLIBC_2.17)(64bit)" for a compiled dependency (confirmed live, acer, 2026-09-08:
    # mpdecimal.i686 needing libc.so.6 - provided by the completely differently-named "glibc"
    # package, so no filename glob on "libc.so.6" could ever match it).
    #
    # Fast path first: "$DIR/$name"-*.rpm is the same fuzzy-but-accepted convention
    # nspawnmgr-download-packages-dnf.sh's own end-of-run copy loop already uses; a name that's a
    # prefix of another package's own name (e.g. "ncurses" vs "ncurses-libs") can over-match, but a
    # spurious extra candidate here is harmless - it only gets used if something separately needs it.
    found=0
    for f in "$HOST_CACHE_TARGET/$1"-*.rpm "$HOST_UPLOAD_TARGET/$1"-*.rpm; do
        [ -e "$f" ] || continue
        found=1
        case "$f" in
            "$HOST_CACHE_TARGET"/*) inpath="$IN_CONTAINER_CACHEDIR/$(basename "$f")" ;;
            *) inpath="$IN_CONTAINER_UPLOADDIR/$(basename "$f")" ;;
        esac
        add_rpm_path "$inpath"
    done
    # `if`, not `[ cond ] && return 0` - confirmed live (acer, 2026-09-08) that a bare `[ cond ] &&
    # cmd` used as a loop-body/function-body statement can silently kill this whole script under
    # `set -e` (the APT port's own structurally similar nested-loop-calling-a-function case actually
    # reproduced it) - fixed everywhere in this pass, including cases like this one where isolated
    # reproduction attempts didn't clearly show the failure, since the risk isn't worth it either way.
    if [ "$found" -eq 1 ]; then
        return 0
    fi
    # Slow path: no filename matched at all - look up which cached package actually PROVIDES this
    # exact capability string instead.
    ensure_provides_map
    matches="$(awk -F'\t' -v want="$1" '$1 == want { print $2 }' "$PROVIDES_MAP" | sort -u)"
    for inpath in $matches; do
        add_rpm_path "$inpath"
    done
}
OFFLINE_OUTPUT=""
i=0
while [ "$i" -lt 15 ]; do
    i=$((i + 1))
    # Deliberately NOT gated on this command's own exit code - confirmed live (acer, 2026-09-07):
    # `dnf install --assumeno` exits non-zero even when it fully, successfully resolves the
    # transaction, because --assumeno means "decline the transaction" - dnf treats that decline
    # itself as a failure exit status, indistinguishable by exit code alone from a genuine
    # resolution failure. The network-based fallback below has always relied on this same
    # `--assumeno` flag and never checked its exit code for exactly this reason. Checking for a real
    # "Transaction Summary" section in the output is what actually distinguishes "fully resolved,
    # just declined" from "could not resolve".
    OFFLINE_OUTPUT="$(chroot "$ROOTFS" dnf --assumeyes --disablerepo='*' --setopt=install_weak_deps=False \
            install --assumeno "$IN_CONTAINER_RPM" $CACHE_RPMS 2>/dev/null || true)"
    if echo "$OFFLINE_OUTPUT" | grep -q '^Transaction Summary'; then
        break
    fi
    MISSING_NAMES="$(echo "$OFFLINE_OUTPUT" | sed -n 's/^[[:space:]]*- nothing provides \([^ ]*\).*/\1/p' | sort -u)"
    ADDED=0
    for name in $MISSING_NAMES; do
        case " $NEEDED_NAMES_SEEN " in
            *" $name "*) continue ;;
        esac
        NEEDED_NAMES_SEEN="$NEEDED_NAMES_SEEN $name"
        BEFORE="$CACHE_RPMS"
        add_candidates_for_name "$name"
        [ "$CACHE_RPMS" = "$BEFORE" ] || ADDED=1
    done
    # Nothing new found locally this round (either dnf reported no missing names at all - some
    # other, non-availability failure - or every missing name has no matching file anywhere in the
    # cache) - further looping can't help, stop now rather than spinning to the iteration cap.
    [ "$ADDED" -eq 1 ] || { OFFLINE_OUTPUT=""; break; }
done
if echo "$OFFLINE_OUTPUT" | grep -q '^Transaction Summary'; then
    NEEDED_NAMES="$(echo "$OFFLINE_OUTPUT" | awk '
        /^Installing/ { active=1; next }
        /^Transaction Summary/ { active=0 }
        active && NF >= 4 && $1 !~ /^=+$/ { print $1 }
    ' | grep -vFx "$OWN_NAME" || true)"
    # Every one of these resolved purely from a local file already sitting in $CACHE_DIR/
    # $UPLOAD_DIR - copy it into the container's own dnf cache now, since the download script (which
    # normally does this exact copy - see nspawnmgr-download-packages-dnf.sh's own end) never runs
    # when this function reports zero missing dependencies below. Already cleared of any stale
    # leftovers up top, before either resolution path ran - nothing more to wipe here.
    mkdir -p "$ROOTFS/var/cache/dnf"
    for name in $NEEDED_NAMES; do
        for f in "$CACHE_DIR/$name"-*.rpm "$UPLOAD_DIR/$name"-*.rpm; do
            # `[ -e "$f" ] || continue`, not `&&` - confirmed live (acer, 2026-09-08): a bare
            # `[ -e "$f" ] && cmd` loop-body statement dies under `set -e` the instant the glob has
            # no match in one of the two directories (the common case - a given package is normally
            # cached in only one of auto/uploaded). Separately, `cp -n`'s own `|| true` matters too:
            # newer coreutils' `cp -n` returns non-zero when it skips an already-existing destination
            # (see its own "behavior of -n is non-portable" warning), which would otherwise kill the
            # run on a second/later attempt where some files were already copied in.
            [ -e "$f" ] || continue
            cp -n "$f" "$ROOTFS/var/cache/dnf/" 2>/dev/null || true
        done
    done
    exit 0
fi

# Offline resolution above either found nothing to work with (empty cache) or couldn't fully resolve
# the transaction from local files alone - fall back to the original network-based simulate,
# unchanged from before this offline-first pass was added.
chroot "$ROOTFS" dnf --assumeyes makecache >/dev/null
chroot "$ROOTFS" dnf --assumeyes install --assumeno "$IN_CONTAINER_RPM" 2>/dev/null | awk '
    /^Installing/ { active=1; next }
    /^Transaction Summary/ { active=0 }
    active && NF >= 4 && $1 !~ /^=+$/ { print $1 }
' | grep -vFx "$OWN_NAME" || true
