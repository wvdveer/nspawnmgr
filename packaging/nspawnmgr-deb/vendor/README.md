# guacd-bundle.tar.gz

A self-contained build of `guacd` (Apache Guacamole's native proxy daemon) plus every native
library it needs, bundled here because **guacd is no longer packaged for any current Debian,
Ubuntu, or Linux Mint release** — confirmed by searching Debian's and Ubuntu's package archives
directly: `guacd`/`guacamole-tomcat` return zero results on bookworm, trixie, jammy, and noble, and
even Debian unstable only builds `guacd` for `ia64`/`riscv64`, not `amd64`. See
`docs/administrator-guide.md` §7 for the full picture.

## What's inside

Extracted to `/opt/guacd-bundle` at install time (by `postinst`), containing:

- `sbin/guacd` — the daemon itself
- `lib/libguac*.so*` — guacamole-server's own core + protocol-plugin libraries (RDP, SSH, Telnet,
  VNC — **not** Kubernetes, deliberately dropped, see below)
- `lib/freerdp2/*.so` — FreeRDP's guacamole-specific addon channel plugins
- Every other `.so` guacd/its plugins need, **except** baseline glibc
  (`libc`/`libm`/`libpthread`/`libdl`/`librt`/`ld-linux`), which always comes from the host

Verified fully self-contained: `ldd` across `guacd` and every plugin resolves to either
`/opt/guacd-bundle/lib*` or baseline glibc, nothing else. ~87MB uncompressed, ~32MB as this
tarball.

## Why bundle instead of just downloading/using system packages

Two real problems, not just "guacd isn't packaged":

1. **EOL crypto.** Debian bullseye's own `libssl.so.1.1` (OpenSSL 1.1.1) is upstream end-of-life —
   no more security patches, ever.
2. **GPL-licensed codecs pulled in transitively.** Debian's system `libavcodec.so.58` (FFmpeg) was
   built with every codec enabled, including GPL-licensed `libx264`/`libx265`. Bundling GPL code
   alongside this Apache-2.0 project isn't something to do without legal review, so it's avoided
   entirely instead.

So this isn't just "guacd copied from an old system" — several of its dependencies are themselves
custom, minimal rebuilds. See "How it was built" below to reproduce or update this.

## How it was built

All builds done inside a `systemd-nspawn` container running a **Debian bullseye** rootfs (glibc
2.31) — an old-but-still-supported baseline chosen deliberately for forward compatibility (a
binary built against an old glibc runs on newer systems; the reverse isn't true). The rootfs itself
came from `https://images.linuxcontainers.org/images/debian/bullseye/amd64/default/`, matching the
same source `tools/scripts/setup-container-template.sh` already uses for its own Debian template.

In dependency order:

1. **OpenSSL 3.5.7** (LTS, supported until 2030) — `https://github.com/openssl/openssl/releases`.
   Plain `./Configure --prefix=/opt/guacd-bundle shared linux-x86_64 && make && make install_sw`.
2. **FFmpeg 8.1.2**, minimal decode-only build — `https://ffmpeg.org/releases/`. This is the piece
   that actually removes the GPL codec dependency: FFmpeg's own *native* H.264/HEVC decoders are
   LGPL, not GPL — the GPL encumbrance specifically comes from optionally linking the separate
   third-party `libx264`/`libx265` *encoder* libraries, which this build never touches at all.
   ```
   ./configure --prefix=/opt/guacd-bundle --disable-everything --disable-programs --disable-doc \
     --disable-static --enable-shared --enable-decoder=h264 --enable-decoder=hevc \
     --enable-parser=h264 --enable-parser=hevc --disable-demuxers --disable-muxers \
     --disable-protocols --disable-devices --disable-filters --disable-bsfs --disable-encoders \
     --disable-network
   ```
3. **FreeRDP 2.11.7** — upstream release tarball from
   `https://github.com/FreeRDP/FreeRDP/archive/refs/tags/2.11.7.tar.gz`, **not** Debian's own
   source package (`apt-get source freerdp2`): Debian's is `+dfsg`-repackaged, which strips
   `winpr/libwinpr/crt/utf.c` for license-cleanliness reasons and never patches in a replacement,
   so it fails to configure at all. Upstream FreeRDP is Apache-2.0 and doesn't have this gap.
   ```
   cmake .. -DCMAKE_INSTALL_PREFIX=/opt/guacd-bundle -DCMAKE_PREFIX_PATH=/opt/guacd-bundle \
     -DOPENSSL_ROOT_DIR=/opt/guacd-bundle \
     -DOPENSSL_INCLUDE_DIR=/opt/guacd-bundle/include \
     -DOPENSSL_SSL_LIBRARY=/opt/guacd-bundle/lib64/libssl.so \
     -DOPENSSL_CRYPTO_LIBRARY=/opt/guacd-bundle/lib64/libcrypto.so \
     -DWITH_FFMPEG=ON -DWITH_SWSCALE=ON -DWITH_OPENSSL=ON -DBUILD_TESTING=OFF \
     -DWITH_SERVER=OFF -DWITH_WINPR_TOOLS=OFF -DWITH_CLIENT=OFF -DWITH_SAMPLE=OFF
   ```
   Set `PKG_CONFIG_PATH=/opt/guacd-bundle/lib/pkgconfig:/opt/guacd-bundle/lib64/pkgconfig` first.
   **Important**: always run this `cmake` configure against a *freshly emptied* build directory.
   CMake caches `find_package(OpenSSL)` results; reusing a build dir from an earlier attempt (even
   with new `-D` flags) silently keeps the stale, wrong OpenSSL path instead of re-detecting it —
   this bit us once already (produced a binary silently linked against system OpenSSL 1.1.1 despite
   `-DOPENSSL_ROOT_DIR` being set correctly).
   `-DWITH_WINPR_TOOLS=OFF -DWITH_CLIENT=OFF -DWITH_SAMPLE=OFF`: these build optional CLI tools
   (`winpr-makecert`, `xfreerdp`) guacd never uses; one of them (`winpr-makecert`) fails to link
   against custom OpenSSL for unrelated reasons, so disabling the lot sidesteps it rather than
   debugging a tool we don't need.
4. **libssh2 1.11.1** — `https://github.com/libssh2/libssh2/releases`. Needed as its own rebuild
   because Debian's system `libssh2.so.1` links system OpenSSL 1.1.1 directly, which would
   otherwise reintroduce the EOL-crypto problem transitively through the RDP plugin (which uses
   libssh2 for its optional SFTP-based drive redirection) even after FreeRDP itself was fixed.
   ```
   cmake .. -DCMAKE_INSTALL_PREFIX=/opt/guacd-bundle -DCMAKE_PREFIX_PATH=/opt/guacd-bundle \
     -DOPENSSL_ROOT_DIR=/opt/guacd-bundle -DCRYPTO_BACKEND=OpenSSL -DBUILD_SHARED_LIBS=ON \
     -DBUILD_TESTING=OFF -DBUILD_EXAMPLES=OFF
   ```
5. **guacamole-server 1.5.5** (matching the version pinned everywhere else in this project —
   `install-guacamole-auth-jdbc.sh`, the administrator's guide's Debian example) —
   `https://archive.apache.org/dist/guacamole/1.5.5/source/guacamole-server-1.5.5.tar.gz`.
   ```
   ./configure --prefix=/usr --disable-guacenc --disable-kubernetes \
     LDFLAGS="-L/opt/guacd-bundle/lib -L/opt/guacd-bundle/lib64 \
              -Wl,-rpath,/opt/guacd-bundle/lib -Wl,-rpath,/opt/guacd-bundle/lib64" \
     CPPFLAGS="-I/opt/guacd-bundle/include"
   make && make install DESTDIR=<staging-dir>
   ```
   **Important**: `-L` is required, not just `-Wl,-rpath` — `-rpath` only affects where the
   *runtime* linker looks; without a matching `-L`, the *build-time* linker still resolves plain
   `-lssl`/`-lcrypto` against the system's default search path and silently links the wrong,
   system OpenSSL. This bit us too.
   `--disable-guacenc`: guacamole-server's separate session-recording-to-video tool uses an
   `avcodec_close()` call FFmpeg removed in newer releases (guacenc wasn't updated for it); it's an
   optional tool unrelated to guacd's live-session operation, so this just skips building it rather
   than patching 1.5.5-era code against an 8.x-era FFmpeg API.
   `--disable-kubernetes`: the Kubernetes connection plugin's only unique dependency is
   `libwebsockets`, which (as packaged) links system OpenSSL 1.1.1 too. Not worth a sixth rebuild
   for a protocol nspawnmgr never uses — containers are only ever reached via SSH/RDP.
6. Copy `guacd` + `libguac*.so*` from guacamole-server's install output, and
   `lib/freerdp2/*.so` (FreeRDP addon channel plugins guacamole-server builds itself — these land
   under `<DESTDIR>/opt/guacd-bundle/lib/freerdp2/`, *not* under guacamole-server's own `--prefix`,
   since the path comes from where FreeRDP itself was found, not from guacamole-server's install
   prefix), into `/opt/guacd-bundle` alongside the OpenSSL/FFmpeg/FreeRDP/libssh2 libraries from
   steps 1-4.
7. Walk `ldd` across `guacd` and every `libguac-client-*.so`/`freerdp2/*.so`, and copy every
   remaining non-baseline-glibc dependency (cairo, pango, the X11 stack, libjpeg, libwebp, Kerberos,
   PulseAudio, ~90 libraries total) into `/opt/guacd-bundle/lib` too — bundled rather than left to
   apt `Depends:`, since several of them (`libffi7`→`8`, `libwebp6`→`7`, `libpcre3` deprecated,
   `libnettle`/`libhogweed` version bumps) have known SONAME churn across Debian/Ubuntu releases,
   which is exactly the class of problem this whole bundle exists to avoid.

## Deploying it

`guacd.service` sets `Environment=LD_LIBRARY_PATH=/opt/guacd-bundle/lib:/opt/guacd-bundle/lib64:/opt/guacd-bundle/lib/freerdp2`
explicitly, even though `guacd`/`libguac*.so` already carry a matching `RUNPATH` — verified live
that a raw protocol handshake against the bundled binary loads the ssh/rdp plugins fine when this
path is resolvable, but `RUNPATH` isn't a substitute for this in every case: it applies to a
binary's own direct link-time dependencies, but the protocol plugins (`libguac-client-*.so`) are
loaded by filename at *runtime* via `dlopen()`, a different resolution path worth not leaving to
chance. Keep this in sync with `/opt/guacd-bundle` if that install path ever changes.

## To rebuild/update this bundle

Repeat the above (a systemd-nspawn Debian bullseye container is a convenient, already-available
build environment on any host this project manages — see
`tools/scripts/setup-container-template.sh` for the same rootfs-fetch pattern) and replace this
tarball. There's no automated build script for this yet — every step above was run by hand and
verified at each stage; a future improvement would be to script steps 1-7 as one reproducible shell
script, similar to `nspawnmgr-create-debian-template.sh`.

# apache-tomcat-9.0.120.tar.gz and guacamole-auth-jdbc-1.5.5.tar.gz

The other two files in this directory are vendored for the same underlying reason as
`guacd-bundle.tar.gz` — installability shouldn't depend on what a distro's apt archive happens to
carry, or on `archive.apache.org` being reachable from the target host at install time — but
neither needed a custom build. Both are the plain, unmodified upstream binary distribution,
downloaded once and committed here:

```bash
# Tomcat
curl -fsSL -o apache-tomcat-9.0.120.tar.gz \
  https://archive.apache.org/dist/tomcat/tomcat-9/v9.0.120/bin/apache-tomcat-9.0.120.tar.gz
# verify against the matching .sha512 on the same page before committing

# guacamole-auth-jdbc
curl -fsSL -o guacamole-auth-jdbc-1.5.5.tar.gz \
  https://archive.apache.org/dist/guacamole/1.5.5/binary/guacamole-auth-jdbc-1.5.5.tar.gz
curl -fsSL -o guacamole-auth-jdbc-1.5.5.tar.gz.sha256 \
  https://archive.apache.org/dist/guacamole/1.5.5/binary/guacamole-auth-jdbc-1.5.5.tar.gz.sha256
sha256sum -c guacamole-auth-jdbc-1.5.5.tar.gz.sha256
```

To bump either version: download+verify the new tarball as above, replace the file in this
directory, update the filename/version string in `packaging/nspawnmgr-deb/pom.xml`'s `dataSet`
(Tomcat) or `install-guacamole-auth-jdbc.sh`'s `GUACAMOLE_VERSION` (auth-jdbc) and
`packaging/nspawnmgr-deb/debian/postinst`'s `TOMCAT_TARBALL` path (Tomcat) to match, and delete the
old tarball.

# debian-minimal.tar.gz (optional — only used by the "bundled" .deb variant)

**Not present here by default.** Unlike every other file in this directory, this one isn't
committed automatically — it has to be baked once, by hand, on a real Linux host with root, the
same way `guacd-bundle.tar.gz` was. `packaging/nspawnmgr-deb` (the "online" variant, the default)
never looks for this file at all and works exactly as it always has; only
`packaging/nspawnmgr-deb-bundled` (see that module's own `pom.xml`) stages it, so its `postinst`
copies a pre-baked image into place at install time instead of baking one fresh — no network access
needed for that one step, at the cost of a larger `.deb`.

It's the exact same tarball `nspawnmgr-create-debian-template.sh` itself produces — nothing
special about the *build*, only about *when* it happens (once, ahead of time, by whoever maintains
this vendor directory, rather than on every fresh install):

```bash
sudo packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-debian-template.sh \
    packaging/nspawnmgr-deb/vendor/debian-minimal.tar.gz
```

Run this on a Debian/Ubuntu-family host (so it takes the confirmed-live, fast `apt-get -o Dir=`
path that script's own header comment describes, rather than the unverified chroot fallback) with
real internet access, since this step is exactly the network fetch the bundled variant exists to
avoid needing *at install time* — it still has to happen *somewhere*, just ahead of time here
instead. Commit the resulting tarball to this directory once produced.

To bump the Debian release this produces (the script pins `bookworm` — see its own `DEBIAN_RELEASE`
variable): re-run the same command after updating that pin, and replace the committed file.

# postgresql-minimal.tar.gz (optional — only used by the "bundled" .deb variant)

**Not present here by default**, same status as `debian-minimal.tar.gz` above — baked once, by
hand, on a real Linux host with root, and committed. `debian-minimal.tar.gz` with PostgreSQL
already `apt-get install`ed on top, so the DB setup wizard's PostgreSQL path is offline-capable too,
not just the app machine itself. **Only PostgreSQL gets this treatment, not MySQL/MariaDB** — a
deliberate choice, not an oversight: bundling every engine's full dependency closure would add
~150-250MB more to the `.deb` for a step that already needs real time regardless (cloning the DB
machine, running the engine's own first-boot init); PostgreSQL alone gives an admin who must do a
fully offline install *something* usable, without chasing full parity across every engine choice.

Requires `debian-minimal.tar.gz` (above) to already exist — this layers on top of it, rather than
baking a rootfs from scratch itself:

```bash
sudo packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-postgresql-template.sh \
    packaging/nspawnmgr-deb/vendor/debian-minimal.tar.gz \
    packaging/nspawnmgr-deb/vendor/postgresql-minimal.tar.gz
```

Same Debian/Ubuntu-family-host-with-real-internet-access requirement as `debian-minimal.tar.gz`.
Commit the resulting tarball to this directory once produced. To rebuild after a PostgreSQL point
release: re-run the same command against a fresh `debian-minimal.tar.gz` bake and replace the
committed file — there's no in-place update, since `nspawnmgr-create-postgresql-template.sh`
refuses to overwrite an existing target.
