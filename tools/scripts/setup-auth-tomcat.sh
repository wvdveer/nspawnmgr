#!/usr/bin/env bash
# Builds auth.war and deploys it as auth.war (context /auth) into the SAME Tomcat 9 instance
# (tools/scripts/setup-tomcat.sh's site/tomcat) that nspawnmgr.war and guacamole.war run in — auth
# targets javax.servlet (Servlet 4.0), matching Tomcat 9, not a separate Jakarta EE/Tomcat 10+
# instance. This is for testing the real PAM/SMB backends against site/tomcat directly; run
# setup-tomcat.sh (and optionally setup-guacamole.sh) first if site/tomcat doesn't exist yet -
# that script also deploys root-wizard's ROOT.war at the root context ("/"), since none of the
# three real webapps can take it without giving up their own path.
#
# Defaults to the SMB backend, pointed at this Windows machine's own local SAM accounts over
# loopback SMB, gated on access to a share — override any of these via env vars for a different
# target. (Not a group-membership check: Windows restricts remote group queries to Administrators
# by default, confirmed against this exact machine — see SmbOsAuthenticator's own comments.)
#
# Usage: setup-auth-tomcat.sh [--skip-build]
set -euo pipefail

TOMCAT_HTTP_PORT="${TOMCAT_HTTP_PORT:-8080}"
AUTH_BACKEND="${AUTH_BACKEND:-smb}"
SMB_SERVER="${SMB_SERVER:-WARDSDELLLAPTOP}"
SMB_DOMAIN="${SMB_DOMAIN:-}"
SMB_REQUIRED_SHARE="${SMB_REQUIRED_SHARE:-tmp}"

skip_build=false
if [[ "${1:-}" == "--skip-build" ]]; then
    skip_build=true
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tomcat_dir="$root/site/tomcat"

if [[ ! -d "$tomcat_dir" ]]; then
    echo "site/tomcat doesn't exist yet — run tools/scripts/setup-tomcat.sh first." >&2
    exit 1
fi

if [[ "$skip_build" == false ]]; then
    echo "Building auth.war..."
    mvn -f "$root/auth/pom.xml" -q -DskipTests package
fi

# Stop the instance BEFORE touching webapps/auth.war: on Windows, files a running JVM has
# open/loaded are locked (unlike Linux, where deleting an in-use file is fine), so replacing it
# while Tomcat is up fails with "Device or resource busy" for anything mid-deploy.
if [[ -x "$tomcat_dir/bin/shutdown.sh" ]]; then
    echo "Stopping the running instance (if any)..."
    export CATALINA_HOME="$tomcat_dir"
    "$tomcat_dir/bin/shutdown.sh" >/dev/null 2>&1 || true
    sleep 2
fi

echo "Deploying auth.war (alongside nspawnmgr.war/guacamole.war)..."
rm -rf "$tomcat_dir/webapps/auth"
cp "$root/auth/target/auth.war" "$tomcat_dir/webapps/auth.war"

export CATALINA_HOME="$tomcat_dir"
export CATALINA_OPTS="-DAUTH_BACKEND=${AUTH_BACKEND} -DSMB_SERVER=${SMB_SERVER} -DSMB_DOMAIN=${SMB_DOMAIN} -DSMB_REQUIRED_SHARE=${SMB_REQUIRED_SHARE}"

echo "Starting Tomcat (auth.war at /auth, backend=${AUTH_BACKEND}) on :${TOMCAT_HTTP_PORT}..."
"$tomcat_dir/bin/startup.sh"

cat <<EOF

auth.war is up at http://localhost:${TOMCAT_HTTP_PORT}/auth, alongside nspawnmgr/guacamole in the
same Tomcat 9 instance.
  Backend:        ${AUTH_BACKEND}
  SMB server:     ${SMB_SERVER}
  Required share: ${SMB_REQUIRED_SHARE:-<none>}

Log in:  http://localhost:${TOMCAT_HTTP_PORT}/auth/login
Logs:    $tomcat_dir/logs/catalina.out
Stop:    $tomcat_dir/bin/shutdown.sh
EOF
