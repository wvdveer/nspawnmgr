#!/bin/bash
# Rebuilds the guacd-bundle.tar.gz vendor artifact from source, scripting the recipe documented in
# this directory's own README.md ("How it was built") - the README calls scripting this "a future
# improvement"; this is that improvement. Written specifically to fix a real, confirmed-live bug:
# the bundle's FreeRDP was built with -DWITH_FFMPEG=ON against a modern FFmpeg that no longer
# exports avcodec_close(), so guacd crashes outright ("symbol lookup error: ...
# undefined symbol: avcodec_close") the moment an RDP server offers the H.264/AVC444 codec
# capability during negotiation (confirmed against Fedora's xrdp 0.10.6.1; Debian's older xrdp
# never offers that capability, so the same bug is invisible there). Every step below matches the
# README exactly, with exactly one change: FreeRDP's own -DWITH_FFMPEG is OFF here, not ON - the
# same "disable the broken FFmpeg-dependent piece entirely" precedent the README's own step 5
# already used for guacamole-server's guacenc tool.
#
# MUST run as root inside a Debian bullseye (glibc 2.31) environment with real internet access -
# NOT on the bare host, whatever its own distro/glibc happens to be (see README's own
# "old-but-still-supported baseline, for forward compatibility" reasoning). A systemd-nspawn
# container booted from https://images.linuxcontainers.org/images/debian/bullseye/amd64/default/
# (the same rootfs source tools/scripts/setup-container-template.sh already uses) is the intended
# environment - see this directory's own README for how to fetch one.
#
# Not fully unattended on a from-scratch host: the exact -dev package list below is a best-effort
# reconstruction (the original hand-built bundle's own package list was never recorded) - if a step
# fails on a missing header/library, install the missing -dev package and re-run; every step here
# is safe to re-run (each stage's own source directory is freshly re-fetched/re-extracted).
#
# Usage: ./build-guacd-bundle.sh
# Produces: /root/guacd-bundle.tar.gz (copy out and replace
#           packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz with it once built and verified).
set -euo pipefail

PREFIX=/opt/guacd-bundle
BUILD=/root/guacd-build
STAGING=/root/guacd-staging

OPENSSL_VERSION=3.5.7
FFMPEG_VERSION=8.1.2
FREERDP_VERSION=2.11.7
LIBSSH2_VERSION=1.11.1
GUACAMOLE_SERVER_VERSION=1.5.5

mkdir -p "$BUILD" "$PREFIX" "$STAGING"
cd "$BUILD"

echo "=== Installing build toolchain ==="
export DEBIAN_FRONTEND=noninteractive
apt-get update
# Best-effort dev-package list (see header comment) - covers guacamole-server's own optional
# protocol/codec backends (cairo/pango for text rendering, X11 for VNC screendumps, PulseAudio for
# RDP/VNC audio, libjpeg/libwebp for image encoding, libssl - system OpenSSL 1.1.1 only needed as a
# *build-time* tool here (e.g. `openssl` CLI), never linked - libvorbis/libgcrypt/libpng for
# guacamole-server's own dependencies, uuid-dev for FreeRDP) plus nasm/yasm for FFmpeg's own
# assembly-optimized codec paths. libvncserver-dev (provides libvncclient, guacamole-server's own
# VNC protocol backend) was missing from an earlier version of this list - confirmed live, its
# absence doesn't fail the build at all, guacamole-server's ./configure just silently detects "no
# VNC support" and skips building libguac-client-vnc.so, so a from-scratch rebuild without this
# package produces a guacd that plainly refuses every VNC connection ("Support for protocol \"vnc\"
# is not installed") with no build-time warning to catch it. libusb-1.0-0-dev/libcups2-dev/
# libpcsclite-dev/libxml2-dev were also missing - confirmed live, FreeRDP's cmake configure step
# fails outright without libusb-1.0-0-dev specifically (LIBUSB_INCLUDE_DIR-NOTFOUND, a fatal "CMake
# Generate step failed"); the other three were added proactively alongside it at the same time.
apt-get install -y --no-install-recommends \
    build-essential cmake pkg-config git curl ca-certificates autoconf automake libtool \
    nasm yasm \
    libx11-dev libxext-dev libxrandr-dev libxi-dev libxcursor-dev libxrender-dev \
    libxfixes-dev libxdamage-dev libxinerama-dev libxkbfile-dev \
    libcairo2-dev libpango1.0-dev libjpeg62-turbo-dev libwebp-dev libpng-dev \
    libpulse-dev libasound2-dev libvorbis-dev libgcrypt20-dev \
    libavahi-client-dev libkrb5-dev uuid-dev zlib1g-dev libvncserver-dev \
    libusb-1.0-0-dev libcups2-dev libpcsclite-dev libxml2-dev

echo "=== Step 1: OpenSSL $OPENSSL_VERSION ==="
cd "$BUILD"
rm -rf "openssl-openssl-$OPENSSL_VERSION" "openssl-$OPENSSL_VERSION.tar.gz"
curl -fsSL -o "openssl-$OPENSSL_VERSION.tar.gz" \
    "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
tar xzf "openssl-$OPENSSL_VERSION.tar.gz"
cd "openssl-$OPENSSL_VERSION"
./Configure --prefix="$PREFIX" shared linux-x86_64
make -j"$(nproc)"
make install_sw

echo "=== Step 2: FFmpeg $FFMPEG_VERSION (minimal decode-only) ==="
cd "$BUILD"
rm -rf "ffmpeg-$FFMPEG_VERSION" "ffmpeg-$FFMPEG_VERSION.tar.xz"
curl -fsSL -o "ffmpeg-$FFMPEG_VERSION.tar.xz" "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
tar xJf "ffmpeg-$FFMPEG_VERSION.tar.xz"
cd "ffmpeg-$FFMPEG_VERSION"
./configure --prefix="$PREFIX" --disable-everything --disable-programs --disable-doc \
    --disable-static --enable-shared --enable-decoder=h264 --enable-decoder=hevc \
    --enable-parser=h264 --enable-parser=hevc --disable-demuxers --disable-muxers \
    --disable-protocols --disable-devices --disable-filters --disable-bsfs --disable-encoders \
    --disable-network
make -j"$(nproc)"
make install

echo "=== Step 3: FreeRDP $FREERDP_VERSION (-DWITH_FFMPEG=OFF - the actual fix) ==="
cd "$BUILD"
rm -rf "FreeRDP-$FREERDP_VERSION" "FreeRDP-$FREERDP_VERSION.tar.gz" freerdp-build
curl -fsSL -o "FreeRDP-$FREERDP_VERSION.tar.gz" \
    "https://github.com/FreeRDP/FreeRDP/archive/refs/tags/$FREERDP_VERSION.tar.gz"
tar xzf "FreeRDP-$FREERDP_VERSION.tar.gz"
mkdir freerdp-build
cd freerdp-build
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:$PREFIX/lib64/pkgconfig"
# Freshly-emptied build dir every time, deliberately - see README's own warning: CMake caches
# find_package(OpenSSL) results, silently keeping a stale/wrong path across reused build dirs even
# with new -D flags passed.
cmake "../FreeRDP-$FREERDP_VERSION" -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_PREFIX_PATH="$PREFIX" \
    -DOPENSSL_ROOT_DIR="$PREFIX" \
    -DOPENSSL_INCLUDE_DIR="$PREFIX/include" \
    -DOPENSSL_SSL_LIBRARY="$PREFIX/lib64/libssl.so" \
    -DOPENSSL_CRYPTO_LIBRARY="$PREFIX/lib64/libcrypto.so" \
    -DWITH_FFMPEG=OFF -DWITH_SWSCALE=ON -DWITH_OPENSSL=ON -DBUILD_TESTING=OFF \
    -DWITH_SERVER=OFF -DWITH_WINPR_TOOLS=OFF -DWITH_CLIENT=OFF -DWITH_SAMPLE=OFF
make -j"$(nproc)"
make install

echo "=== Step 4: libssh2 $LIBSSH2_VERSION ==="
cd "$BUILD"
rm -rf "libssh2-$LIBSSH2_VERSION" "libssh2-$LIBSSH2_VERSION.tar.gz" libssh2-build
curl -fsSL -o "libssh2-$LIBSSH2_VERSION.tar.gz" \
    "https://github.com/libssh2/libssh2/releases/download/libssh2-$LIBSSH2_VERSION/libssh2-$LIBSSH2_VERSION.tar.gz"
tar xzf "libssh2-$LIBSSH2_VERSION.tar.gz"
mkdir libssh2-build
cd libssh2-build
cmake "../libssh2-$LIBSSH2_VERSION" -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_PREFIX_PATH="$PREFIX" \
    -DOPENSSL_ROOT_DIR="$PREFIX" -DCRYPTO_BACKEND=OpenSSL -DBUILD_SHARED_LIBS=ON \
    -DBUILD_TESTING=OFF -DBUILD_EXAMPLES=OFF
make -j"$(nproc)"
make install

echo "=== Step 5: guacamole-server $GUACAMOLE_SERVER_VERSION ==="
cd "$BUILD"
rm -rf "guacamole-server-$GUACAMOLE_SERVER_VERSION" "guacamole-server-$GUACAMOLE_SERVER_VERSION.tar.gz"
curl -fsSL -o "guacamole-server-$GUACAMOLE_SERVER_VERSION.tar.gz" \
    "https://archive.apache.org/dist/guacamole/$GUACAMOLE_SERVER_VERSION/source/guacamole-server-$GUACAMOLE_SERVER_VERSION.tar.gz"
tar xzf "guacamole-server-$GUACAMOLE_SERVER_VERSION.tar.gz"
cd "guacamole-server-$GUACAMOLE_SERVER_VERSION"

# guacamole-server's NLA case never sets TlsSecurity=TRUE, only NlaSecurity=TRUE. FreeRDP's own
# nego_recv() (libfreerdp/core/nego.c) rejects the server's SelectedProtocol if EnabledProtocols[]
# says it's not allowed - so when an RDP server declines HYBRID/CredSSP and falls back to plain TLS
# (valid, spec-compliant server behavior - confirmed live against Fedora's xrdp 0.10.6), FreeRDP
# aborts immediately instead of accepting the fallback, with no TLS handshake ever attempted.
# Confirmed via packet capture 2026-08-12: FreeRDP sends FIN right after the server's Negotiate
# Response, never a ClientHello. VMCONNECT mode in this same switch DOES set both TlsSecurity and
# NlaSecurity TRUE, so NLA's asymmetry looks like a gap, not a deliberate choice - not (yet) reported
# upstream.
perl -0777 -pi -e 's/(case GUAC_SECURITY_NLA:\s*\n\s*rdp_settings->RdpSecurity = FALSE;\s*\n\s*rdp_settings->TlsSecurity = )FALSE(;\s*\n\s*rdp_settings->NlaSecurity = TRUE;)/${1}TRUE${2}/' \
    src/protocols/rdp/settings.c
grep -A5 "case GUAC_SECURITY_NLA:" src/protocols/rdp/settings.c | grep -q "TlsSecurity = TRUE" \
    || { echo "FATAL: NLA TlsSecurity patch did not apply - upstream settings.c changed?" >&2; exit 1; }

# xrdp's CVE-2026-32105 fix (0.10.6+) added MAC verification for Classic RDP Security that was
# previously entirely missing - but its xrdp_sec_sign()/xrdp_sec_check_sig() only ever computes
# the PLAIN, unsalted MAC (SHA1(key+pad+len+data), then MD5 - no sequence number involved).
# FreeRDP defaults settings->SaltedChecksum=TRUE, and rdp_client_establish_keys() (connection.c)
# sets rdp->do_secure_checksum straight from that setting with NO server-capability check at all -
# so by default FreeRDP always calls security_salted_mac_signature() (which mixes a 4-byte
# encrypt/decrypt use-counter into the SHA1 input) instead of the plain security_mac_signature().
# Against xrdp's plain-only verification this mismatch is deterministic and total: every Classic
# RDP Security connection fails with "MAC checksum error for non-FIPS PDU", regardless of Guacamole's
# own "security" parameter (any/rdp both negotiate classic security against an xrdp server and hit
# this). Confirmed via source-level trace across both FreeRDP 2.11.7 and xrdp v0.10.6, 2026-08-12 -
# not yet verified live (needs a full rebuild+redeploy to test). Forcing SaltedChecksum=FALSE here
# makes FreeRDP's classic-security MAC generation match what xrdp actually verifies.
perl -0777 -pi -e 's{(/\* Authentication \*/\s*\n\s*rdp_settings->Authentication = !guac_settings->disable_authentication;)}{/* SaltedChecksum disabled: xrdp CVE-2026-32105 MAC verification (0.10.6+) only implements the\n     * plain, unsalted MAC (SHA1(key+pad+len+data)) - it never implements the salted variant\n     * FreeRDP defaults to (SaltedChecksum=TRUE, enabled unconditionally with no server-capability\n     * check - see rdp_client_establish_keys() in connection.c). Mismatch is deterministic and\n     * total: every Classic RDP Security connection to an unpatched-for-this xrdp gets rejected\n     * with "MAC checksum error for non-FIPS PDU". Force the plain variant to match. */\n    rdp_settings->SaltedChecksum = FALSE;\n\n    $1}' \
    src/protocols/rdp/settings.c
grep -q "rdp_settings->SaltedChecksum = FALSE;" src/protocols/rdp/settings.c \
    || { echo "FATAL: SaltedChecksum patch did not apply - upstream settings.c changed?" >&2; exit 1; }

rm -rf "$STAGING"
mkdir -p "$STAGING"
./configure --prefix=/usr --disable-guacenc --disable-kubernetes \
    LDFLAGS="-L$PREFIX/lib -L$PREFIX/lib64 -Wl,-rpath,$PREFIX/lib -Wl,-rpath,$PREFIX/lib64" \
    CPPFLAGS="-I$PREFIX/include"
make -j"$(nproc)"
make install DESTDIR="$STAGING"

echo "=== Step 6: assembling $PREFIX ==="
cp -v "$STAGING"/usr/sbin/guacd "$PREFIX/sbin/" 2>/dev/null || { mkdir -p "$PREFIX/sbin"; cp -v "$STAGING"/usr/sbin/guacd "$PREFIX/sbin/"; }
mkdir -p "$PREFIX/lib"
cp -v "$STAGING"/usr/lib/libguac*.so* "$PREFIX/lib/"
mkdir -p "$PREFIX/lib/freerdp2"
# FreeRDP addon channel plugins land under guacamole-server's own build tree, keyed by wherever it
# found FreeRDP (this build's $PREFIX/lib/freerdp2), not under guacamole-server's own --prefix - see
# README step 6's own note.
if [ -d "$PREFIX/lib/freerdp2" ] && [ "$(find "$PREFIX/lib/freerdp2" -maxdepth 1 -name '*.so' | wc -l)" -eq 0 ]; then
    find "$STAGING" -path '*/freerdp2/*.so' -exec cp -v {} "$PREFIX/lib/freerdp2/" \;
fi

echo "=== Step 7: bundling every remaining non-baseline-glibc dependency ==="
# Baseline glibc pieces always come from the host, never bundled - see README's own "What's
# inside" list. MUST be anchored with a literal ".so" (or "-" for the ld-linux family) right after
# each short name, not just a bare prefix - confirmed live 2026-08-12 (three separate sessions'
# worth of a recurring "missing libcairo.so.2 at runtime" bug, always live-patched by hand and
# never actually fixed until now): an unanchored "libc" prefix also matches "libcairo.so.2" (since
# "libcairo" literally starts with "libc"), silently treating it as baseline glibc and skipping it
# in both the bundling loop below and the verification pass later in this script - the build's own
# "everything resolves" check passed cleanly despite the bundle being genuinely incomplete, because
# it used this exact same regex to decide what counted as "baseline" and didn't need to be present.
# Also silently affected (confirmed via direct regex test, not yet hit as a live symptom):
# libcrypt.so.1 and libmagic.so.1, both likewise misidentified as baseline glibc by the old pattern.
BASELINE_REGEX='^(linux-vdso\.so|libc\.so|libm\.so|libpthread\.so|libdl\.so|librt\.so|ld-linux)'
collect_deps() {
    local target="$1"
    ldd "$target" 2>/dev/null | awk '{print $3}' | grep -E '^/' | while read -r dep; do
        basename_dep="$(basename "$dep")"
        if echo "$basename_dep" | grep -qE "$BASELINE_REGEX"; then
            continue
        fi
        # Already-bundled (from steps 1-4, or a previous pass of this same loop) - skip re-copying.
        if [ -e "$PREFIX/lib/$basename_dep" ] || [ -e "$PREFIX/lib64/$basename_dep" ]; then
            continue
        fi
        echo "  bundling $dep"
        cp -v "$dep" "$PREFIX/lib/"
    done
}
# Repeat until stable: a freshly-copied library can itself pull in more not-yet-bundled
# dependencies (e.g. cairo -> pango -> pixman -> ...), so this needs to fully transitively close,
# not just one pass. Critically, each pass must also re-scan $PREFIX/lib itself (not just the
# original guacd/libguac-client/freerdp2-plugin set) - otherwise a dependency-of-a-dependency
# that only shows up once its parent has already been copied in (e.g. libcairo, needed only by
# the freshly-bundled libpangocairo, not by guacd directly) is silently never collected.
prev_count=-1
for _ in $(seq 1 10); do
    for f in "$PREFIX/sbin/guacd" "$PREFIX/lib"/libguac-client-*.so "$PREFIX/lib/freerdp2"/*.so "$PREFIX/lib"/*.so*; do
        [ -e "$f" ] || continue
        collect_deps "$f"
    done
    count="$(find "$PREFIX/lib" -maxdepth 1 -name '*.so*' | wc -l)"
    if [ "$count" -eq "$prev_count" ]; then
        break
    fi
    prev_count="$count"
done

echo "=== Verifying: everything resolves to $PREFIX or baseline glibc ==="
# Uses readelf against a manifest of what's actually IN the bundle, not ldd's live resolution -
# ldd also searches the build container's own system library paths, so a dependency that's only
# satisfied by a system package (and would be missing on a real target host) can silently pass an
# ldd-based check while still being absent from the bundle itself.
bundled_names="$(find "$PREFIX/lib" "$PREFIX/lib64" "$PREFIX/lib/freerdp2" -maxdepth 1 -name '*.so*' 2>/dev/null -exec basename {} \; | sort -u)"
unresolved=0
for f in "$PREFIX/sbin/guacd" "$PREFIX/lib"/libguac-client-*.so "$PREFIX/lib/freerdp2"/*.so "$PREFIX/lib"/*.so*; do
    [ -e "$f" ] || continue
    for needed in $(readelf -d "$f" 2>/dev/null | grep NEEDED | sed -E 's/.*\[(.*)\]/\1/'); do
        if echo "$needed" | grep -qE "$BASELINE_REGEX"; then
            continue
        fi
        if ! echo "$bundled_names" | grep -qxF "$needed"; then
            echo "UNRESOLVED in $f: $needed not present anywhere in $PREFIX"
            unresolved=1
        fi
    done
done
if [ "$unresolved" -ne 0 ]; then
    echo "Some dependencies are still unresolved - install the corresponding -dev package (for the" >&2
    echo "build-time symlink) or bundle the missing .so by hand, then re-run." >&2
    exit 1
fi

echo "=== Packing $PREFIX into /root/guacd-bundle.tar.gz ==="
tar czf /root/guacd-bundle.tar.gz -C /opt guacd-bundle
echo "Done: /root/guacd-bundle.tar.gz ($(du -h /root/guacd-bundle.tar.gz | cut -f1))"
