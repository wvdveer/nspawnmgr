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
#      needs packaging/nspawnmgr-deb/vendor/debian-minimal.tar.gz to exist (see that vendor
#      directory's own README.md for how to produce it), which a plain BUILD_DEB=1 build doesn't
#      require at all.
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

if [ "${BUILD_DEB_BUNDLED:-}" = "1" ]; then
    echo "Building packaging/nspawnmgr-deb-bundled (.deb, BUILD_DEB_BUNDLED=1)..."
    mvn -f "$root/packaging/nspawnmgr-deb-bundled/pom.xml" -q package
fi

echo "All modules built."
