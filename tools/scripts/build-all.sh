#!/usr/bin/env bash
# Builds every module. The root pom.xml is the main app (packaging=war) and is NOT a reactor
# aggregator (Maven requires aggregators to be packaging=pom), so each standalone module —
# tools/* plus auth/ plus packaging/nspawnmgr-deb — is built with its own -f invocation, in
# dependency order:
#   1. root (installed, so its classes are available to dependent modules)
#   2. fake-machinectl (just packaged — its jar only needs to exist in target/ for
#      tools/scripts/start-dev-stack.sh to inject into a deployed WAR's WEB-INF/lib; nothing
#      depends on it as a Maven artifact)
#   3. fake-guacamole-server, auth, root-wizard (independent of each other)
#   4. packaging/nspawnmgr-deb (production .deb — needs target/nspawnmgr.war and
#      auth/target/auth.war from steps 1 and 3) — opt-in only, via BUILD_DEB=1: it fetches the
#      jdeb Maven plugin from the network on first use, isn't needed for dev/CI, and shouldn't
#      block a plain dev build (e.g. tools/scripts/start-dev-stack.sh, which calls this script)
#      on network access it doesn't otherwise need.
#   5. packaging/nspawnmgr-deb-bundled (the "bundled debian-minimal" variant of step 4 — see that
#      module's own pom.xml comment) — separately opt-in via BUILD_DEB_BUNDLED=1, since it also
#      needs packaging/nspawnmgr-deb/vendor/{debian-minimal,postgresql-minimal}.tar.gz to exist,
#      which a plain BUILD_DEB=1 build doesn't require at all. Same for BUILD_ARCH_PKG/
#      BUILD_STEAMOS_PKG/BUILD_RPM below. These two files are too large to commit to git - see
#      tools/scripts/ensure-vendor-templates.sh (called once below, before any of the four gated
#      blocks that need it) for how they're obtained: downloaded from Gitea's package registry, or
#      baked+published fresh if the registry doesn't have them yet.
#   6. packaging/nspawnmgr-arch (Arch package — needs the same target/nspawnmgr.war,
#      auth/target/auth.war, root-wizard/target/ROOT.war as step 4, plus
#      target/guacamole-jdbc-drivers/) — opt-in via BUILD_ARCH_PKG=1. Not a Maven module (no
#      Maven-native Arch packaging plugin exists) — this just runs `makepkg` directly if it's on
#      PATH, since this project's own dev/CI environment has neither `pacman` nor `makepkg`
#      available. If it's missing, this prints a clear message and moves on rather than failing
#      the whole build — see packaging/nspawnmgr-arch/PKGBUILD's own header comment for why a
#      real build/install of this package is still unverified until a real Arch host exists.
#   7. packaging/nspawnmgr-steamos (SteamOS variant of step 6 — same WARs, different `pkgname`
#      (`nspawnmgr-steamos`, not `nspawnmgr`) and its own `.install` hook that relocates storage
#      under /home for SteamOS's small root partition — see that PKGBUILD's own header comment).
#      Separately opt-in via BUILD_STEAMOS_PKG=1, same "print and move on if no makepkg" fallback
#      as step 6.
#   8. packaging/nspawnmgr-rpm (RPM package for Fedora/RHEL — needs the same three WARs as steps
#      4/6, plus target/guacamole-jdbc-drivers/) — opt-in via BUILD_RPM=1, same "fetches a plugin
#      from the network on first use, shouldn't block a plain dev build" reasoning as BUILD_DEB.
#      Unlike Arch, this one IS a real Maven module (org.codehaus.mojo:rpm-maven-plugin is
#      Redline-based, pure Java — no `rpmbuild` binary needed on the build host either).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

echo "1/3 Installing root (main app)..."
mvn -f "$root/pom.xml" -q -DskipTests install

echo "2/3 Packaging tools/fake-machinectl..."
mvn -f "$root/tools/fake-machinectl/pom.xml" -q -DskipTests package

echo "3/3 Building tools/fake-guacamole-server, auth, root-wizard..."
mvn -f "$root/tools/fake-guacamole-server/pom.xml" -q -DskipTests package
mvn -f "$root/auth/pom.xml" -q -DskipTests package
mvn -f "$root/root-wizard/pom.xml" -q -DskipTests package

if [ "${BUILD_DEB:-}" = "1" ]; then
    echo "Building packaging/nspawnmgr-deb (.deb, BUILD_DEB=1)..."
    mvn -f "$root/packaging/nspawnmgr-deb/pom.xml" -q package
fi

# All four of BUILD_DEB_BUNDLED/BUILD_ARCH_PKG/BUILD_STEAMOS_PKG/BUILD_RPM need
# packaging/nspawnmgr-deb/vendor/{debian-minimal,postgresql-minimal}.tar.gz to exist - no longer
# committed to git (too large, see .gitignore), so ensure-vendor-templates.sh downloads them from
# Gitea's package registry (or bakes+publishes them itself if the registry doesn't have them yet)
# before any of the four blocks below run.
if [ "${BUILD_DEB_BUNDLED:-}" = "1" ] || [ "${BUILD_ARCH_PKG:-}" = "1" ] || [ "${BUILD_STEAMOS_PKG:-}" = "1" ] || [ "${BUILD_RPM:-}" = "1" ]; then
    bash "$root/tools/scripts/ensure-vendor-templates.sh"
fi

if [ "${BUILD_DEB_BUNDLED:-}" = "1" ]; then
    echo "Building packaging/nspawnmgr-deb-bundled (.deb, BUILD_DEB_BUNDLED=1)..."
    mvn -f "$root/packaging/nspawnmgr-deb-bundled/pom.xml" -q package
fi

if [ "${BUILD_RPM:-}" = "1" ]; then
    echo "Building packaging/nspawnmgr-rpm (.rpm, BUILD_RPM=1)..."
    mvn -f "$root/packaging/nspawnmgr-rpm/pom.xml" -q package
fi

if [ "${BUILD_ARCH_PKG:-}" = "1" ]; then
    if command -v makepkg >/dev/null 2>&1; then
        echo "Building packaging/nspawnmgr-arch (Arch package, BUILD_ARCH_PKG=1)..."
        (cd "$root/packaging/nspawnmgr-arch" && makepkg -f)
    else
        echo "BUILD_ARCH_PKG=1 set, but no 'makepkg' on PATH — packaging/nspawnmgr-arch/PKGBUILD" >&2
        echo "is staged but not built. A real Arch host (or the archlinux/devtools container" >&2
        echo "image) is needed to actually produce a .pkg.tar.zst." >&2
    fi
fi

if [ "${BUILD_STEAMOS_PKG:-}" = "1" ]; then
    if command -v makepkg >/dev/null 2>&1; then
        echo "Building packaging/nspawnmgr-steamos (SteamOS package, BUILD_STEAMOS_PKG=1)..."
        (cd "$root/packaging/nspawnmgr-steamos" && makepkg -f)
    else
        echo "BUILD_STEAMOS_PKG=1 set, but no 'makepkg' on PATH — packaging/nspawnmgr-steamos/PKGBUILD" >&2
        echo "is staged but not built. A real Arch/SteamOS host (or the archlinux/devtools container" >&2
        echo "image) is needed to actually produce a .pkg.tar.zst." >&2
    fi
fi

echo "All modules built."
