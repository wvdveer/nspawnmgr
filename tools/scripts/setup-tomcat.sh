#!/usr/bin/env bash
# Downloads Apache Tomcat 9 (the version matching Guacamole's and auth's javax.servlet-based
# webapps — see root pom.xml/auth/pom.xml for why nspawnmgr and auth both target Boot 2.7/
# javax.servlet rather than Tomcat 10+/jakarta.servlet), caching the archive under tools/downloads
# (gitignored), then extracts a fresh working copy into site/tomcat and deploys nspawnmgr.war and
# root-wizard's ROOT.war into it — both via conf/Catalina/localhost/*.xml context descriptors
# (docBase pointing straight at the built .war files), the same convention the real .deb packaging
# uses, rather than dropping WARs into webapps/ directly. This is what lets ROOT.war's "Finish
# setup" reload mechanism (touch nspawnmgr.xml's mtime, Tomcat redeploys just that context) be
# exercised in dev-stack too, not just in production. ROOT.war (deployed at "/") redirects to
# /nspawnmgr/ once the database is configured; before that it's the first-boot setup wizard itself
# — neither guacamole.war nor auth.war can take the ROOT context without giving up their own path.
# Drop a real guacamole.war into site/tomcat/webapps/ yourself afterward (or see
# tools/scripts/setup-guacamole.sh) and auth.war via tools/scripts/setup-auth-tomcat.sh for full
# side-by-side integration testing — neither is part of this project's own build output by default.
set -euo pipefail

TOMCAT_VERSION="${TOMCAT_VERSION:-9.0.83}"
TOMCAT_HTTP_PORT="${TOMCAT_HTTP_PORT:-8080}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
downloads_dir="$root/tools/downloads"
tomcat_dir="$root/site/tomcat"
archive_name="apache-tomcat-${TOMCAT_VERSION}.tar.gz"
archive_path="$downloads_dir/$archive_name"
download_url="https://archive.apache.org/dist/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin/${archive_name}"

mkdir -p "$downloads_dir"

if [[ ! -f "$archive_path" ]]; then
    echo "Downloading Tomcat ${TOMCAT_VERSION}..."
    curl -fsSL -o "$archive_path" "$download_url"
else
    echo "Using cached $archive_path"
fi

echo "Extracting a fresh copy into $tomcat_dir..."
rm -rf "$tomcat_dir"
mkdir -p "$tomcat_dir"
tar -xzf "$archive_path" -C "$tomcat_dir" --strip-components=1
chmod +x "$tomcat_dir"/bin/*.sh

if [[ "$TOMCAT_HTTP_PORT" != "8080" ]]; then
    echo "Setting HTTP connector port to $TOMCAT_HTTP_PORT..."
    sed -i "s/port=\"8080\"/port=\"${TOMCAT_HTTP_PORT}\"/" "$tomcat_dir/conf/server.xml"
fi

# Same stripping postinst does in production: the stock webapps/ROOT would otherwise auto-deploy
# and shadow ROOT.war's own context-XML deployment below (webapps/ auto-deploy and a
# conf/Catalina/localhost/*.xml descriptor for the same context name conflict) - manager/
# host-manager/examples/docs are stripped too, matching production's own reduced attack surface.
echo "Stripping the stock manager/host-manager/examples/docs/ROOT webapps..."
rm -rf "$tomcat_dir/webapps/manager" "$tomcat_dir/webapps/host-manager" \
       "$tomcat_dir/webapps/examples" "$tomcat_dir/webapps/docs" "$tomcat_dir/webapps/ROOT"

mkdir -p "$tomcat_dir/conf/Catalina/localhost"

# Tomcat here runs as a native Windows java.exe (not under Git Bash), so a docBase must be a real
# Windows path (C:\...) - the POSIX-style /c/... paths $root is built from get silently
# misinterpreted as *relative* (no recognized drive), which HostConfig then resolves inside
# webapps/ instead, producing bizarre nested paths and a 404. cygpath -w is Git Bash's own
# converter for exactly this; a no-op passthrough on real Linux, where cygpath doesn't exist and
# $root is already a proper absolute path Tomcat understands natively.
to_native_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        printf '%s' "$1"
    fi
}

war_path="$root/target/nspawnmgr.war"
if [[ -f "$war_path" ]]; then
    echo "Deploying nspawnmgr.war..."
    cat >"$tomcat_dir/conf/Catalina/localhost/nspawnmgr.xml" <<EOF
<Context docBase="$(to_native_path "$war_path")" />
EOF
else
    echo "Note: $war_path not built yet — run 'mvn -DskipTests package' at repo root first, then re-run this script."
fi

root_war_path="$root/root-wizard/target/ROOT.war"
if [[ -f "$root_war_path" ]]; then
    echo "Deploying ROOT.war (redirects to /nspawnmgr/ once the database is configured)..."
    cat >"$tomcat_dir/conf/Catalina/localhost/ROOT.xml" <<EOF
<Context docBase="$(to_native_path "$root_war_path")" />
EOF
else
    echo "Note: $root_war_path not built yet — run 'mvn -f root-wizard/pom.xml -DskipTests package', then re-run this script."
fi

cat <<EOF

Tomcat ${TOMCAT_VERSION} set up at $tomcat_dir (HTTP port ${TOMCAT_HTTP_PORT})
  - nspawnmgr:  http://localhost:${TOMCAT_HTTP_PORT}/nspawnmgr  (once deployed)
  - Guacamole:  drop guacamole.war into $tomcat_dir/webapps/ yourself, then it's at
                http://localhost:${TOMCAT_HTTP_PORT}/guacamole
  - auth:       tools/scripts/setup-auth-tomcat.sh deploys auth.war here at
                http://localhost:${TOMCAT_HTTP_PORT}/auth
  - /           redirects to /nspawnmgr/

If port ${TOMCAT_HTTP_PORT} is already taken by something else on your machine, re-run with
TOMCAT_HTTP_PORT=8180 (or any free port) set in the environment.

Also set SPRING_PROFILES_ACTIVE=prod (plus the other env vars in site/env/.env.example) before
starting Tomcat — with no profile active it defaults to "dev", which needs
tools/fake-machinectl's classes injected into WEB-INF/lib (see
tools/scripts/start-dev-stack.sh, which does that for you), not the plain deployed WAR as-is.

Start:  $tomcat_dir/bin/startup.sh
Stop:   $tomcat_dir/bin/shutdown.sh
Logs:   $tomcat_dir/logs/catalina.out
EOF
