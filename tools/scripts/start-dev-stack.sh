#!/usr/bin/env bash
# Builds (unless --skip-build) and launches the full dev stack: fake-guacamole-server
# (guacamole.war), nspawnmgr.war (dev profile), root-wizard's ROOT.war, and auth.war all deployed
# side by side into the same external Tomcat 9 instance under site/tomcat (nspawnmgr at
# /nspawnmgr, guacamole.war at /guacamole, auth.war at /auth, ROOT.war at "/" — redirecting to
# /nspawnmgr/ once the database is configured, via tools/scripts/setup-tomcat.sh) — same topology
# tools/scripts/start-real-stack.sh uses for the real path. All four target javax.servlet
# (Servlet 4.0), which is why they can share one Tomcat instance — see auth/pom.xml's own comment.
#
# nspawnmgr.war never bundles tools/fake-machinectl (to keep real deployments clean), so unlike a
# plain tools/scripts/setup-tomcat.sh deploy, this script explodes the WAR (into
# site/tomcat-apps/nspawnmgr, outside webapps/) and injects fake-machinectl's jar into its
# WEB-INF/lib — that's what makes @Profile("dev") beans (FakeContainerCliExecutor et al)
# resolvable at all once Tomcat starts it under SPRING_PROFILES_ACTIVE=dev. It then repoints
# conf/Catalina/localhost/nspawnmgr.xml's docBase at that exploded directory instead of the plain
# .war setup-tomcat.sh initially deployed — this context-XML-based (not webapps/ auto-deploy)
# convention is what lets ROOT.war's "Finish setup" reload mechanism (touching this same file's
# mtime) be exercised here too, matching production.
#
# Set NSPAWNMGR_FORCE_DB_WIZARD=true to make nspawnmgr's dev-profile boot take the "database not
# configured" branch anyway (dev's H2 is otherwise always instantly reachable, so there'd be no way
# to click through the ROOT.war wizard flow at all) — see NspawnmgrApplication.isWizardForced().
set -euo pipefail

skip_build=false
if [[ "${1:-}" == "--skip-build" ]]; then
    skip_build=true
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOMCAT_HTTP_PORT="${TOMCAT_HTTP_PORT:-8080}"
# auth.war is real (no fake), so its OS-account backend still needs configuring: PAM by default
# (matching AuthConfig's own default, and what the Linux CI runner's ciuser account uses), or set
# AUTH_BACKEND=smb (+ SMB_SERVER/SMB_DOMAIN/SMB_REQUIRED_SHARE) for local Windows dev, same as
# tools/scripts/setup-auth-tomcat.sh.
AUTH_BACKEND="${AUTH_BACKEND:-pam}"
SMB_SERVER="${SMB_SERVER:-}"
SMB_DOMAIN="${SMB_DOMAIN:-}"
SMB_REQUIRED_SHARE="${SMB_REQUIRED_SHARE:-}"
tomcat_dir="$root/site/tomcat"
log_dir="${TMPDIR:-/tmp}/nspawnmgr-dev/logs"
mkdir -p "$log_dir"

if [[ "$skip_build" == false ]]; then
    "$root/tools/scripts/build-all.sh"
fi

echo "Setting up Tomcat 9 + deploying nspawnmgr.war..."
TOMCAT_HTTP_PORT="$TOMCAT_HTTP_PORT" "$root/tools/scripts/setup-tomcat.sh"

echo "Exploding nspawnmgr.war and injecting tools/fake-machinectl for the dev profile..."
webapp_dir="$root/site/tomcat-apps/nspawnmgr"
rm -rf "$webapp_dir"
mkdir -p "$webapp_dir"
(cd "$webapp_dir" && jar -xf "$root/target/nspawnmgr.war")
# `|| true`: under set -e + pipefail, a nonexistent target/ dir makes `find`/`ls` fail, which -
# since this is a plain assignment - would otherwise abort the script right here with zero output,
# before the -z check below ever gets a chance to print a useful message.
#
# `ls -t` (newest first), not a plain `find | head -1`: confirmed live, a stale jar from a
# previous build (e.g. left behind by a version bump - target/ is never `mvn clean`ed between
# runs) sorts ahead of the current one in `find`'s own directory-order listing, silently injecting
# an out-of-date fake executor into WEB-INF/lib with no error - surfaced as an AbstractMethodError
# at runtime for whichever new interface method the stale jar doesn't have yet, nothing at
# build/deploy time to catch it.
fake_machinectl_jar="$(cd "$root/tools/fake-machinectl/target" 2>/dev/null && ls -t ./*.jar 2>/dev/null | head -1 || true)"
if [[ -n "$fake_machinectl_jar" ]]; then
    fake_machinectl_jar="$root/tools/fake-machinectl/target/${fake_machinectl_jar#./}"
fi
if [[ -z "$fake_machinectl_jar" ]]; then
    echo "tools/fake-machinectl/target/*.jar not found - build-all.sh should have produced it (did you use --skip-build without building first?)" >&2
    exit 1
fi
cp "$fake_machinectl_jar" "$webapp_dir/WEB-INF/lib/"
# Repoints nspawnmgr's context at the exploded+injected directory instead of the plain .war
# setup-tomcat.sh initially deployed above - see this script's own header comment for why.
# cygpath -w: see setup-tomcat.sh's to_native_path() for why a POSIX-style path here breaks
# Tomcat's docBase resolution under Git Bash on Windows; a no-op on real Linux.
if command -v cygpath >/dev/null 2>&1; then
    webapp_dir_native="$(cygpath -w "$webapp_dir")"
else
    webapp_dir_native="$webapp_dir"
fi
cat >"$tomcat_dir/conf/Catalina/localhost/nspawnmgr.xml" <<EOF
<Context docBase="$webapp_dir_native" />
EOF

echo "Deploying guacamole.war (fake stand-in)..."
cp "$root/tools/fake-guacamole-server/target/guacamole.war" "$tomcat_dir/webapps/guacamole.war"

echo "Deploying auth.war..."
rm -rf "$tomcat_dir/webapps/auth"
cp "$root/auth/target/auth.war" "$tomcat_dir/webapps/auth.war"

echo "Deploying the web test harness (dev-only, not part of any packaged artifact)..."
rm -rf "$tomcat_dir/webapps/test-harness"
mkdir -p "$tomcat_dir/webapps/test-harness"
cp "$root/tools/web-test-harness/"*.html "$root/tools/web-test-harness/"*.js "$tomcat_dir/webapps/test-harness/"

echo "Starting Tomcat (dev profile, fakes; auth.war at /auth) on :${TOMCAT_HTTP_PORT}..."
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export CATALINA_HOME="$tomcat_dir"
# Same path nspawnmgr's dev profile writes to (application-dev.yml's nspawnmgr.auth.settings-file,
# ${java.io.tmpdir} resolving to the same default TMPDIR on a Linux dev/CI box) — one JVM, so this
# system property is visible to both webapps, exactly like AUTH_BACKEND/SMB_SERVER below.
AUTH_SETTINGS_FILE="${AUTH_SETTINGS_FILE:-${TMPDIR:-/tmp}/nspawnmgr-dev/auth-settings.properties}"
NSPAWNMGR_FORCE_DB_WIZARD="${NSPAWNMGR_FORCE_DB_WIZARD:-false}"
export CATALINA_OPTS="-DSPRING_PROFILES_ACTIVE=dev \
  -DGUACAMOLE_BASE_URL=http://localhost:${TOMCAT_HTTP_PORT}/guacamole \
  -DUSER_ID_URL=http://localhost:${TOMCAT_HTTP_PORT}/auth/userinfo \
  -DAUTH_LOGIN_URL=http://localhost:${TOMCAT_HTTP_PORT}/auth/login \
  -DAUTH_BACKEND=${AUTH_BACKEND} -DSMB_SERVER=${SMB_SERVER} -DSMB_DOMAIN=${SMB_DOMAIN} -DSMB_REQUIRED_SHARE=${SMB_REQUIRED_SHARE} \
  -DAUTH_SETTINGS_FILE=${AUTH_SETTINGS_FILE} \
  -DNSPAWNMGR_FORCE_DB_WIZARD=${NSPAWNMGR_FORCE_DB_WIZARD}"
"$tomcat_dir/bin/startup.sh"
echo "$tomcat_dir" >"$log_dir/tomcat_dir"

cat <<EOF

Dev stack starting. Logs under $log_dir, Tomcat's own log at $tomcat_dir/logs/catalina.out
  nspawnmgr:             http://localhost:${TOMCAT_HTTP_PORT}/nspawnmgr
  root wizard / redirect: http://localhost:${TOMCAT_HTTP_PORT}/ (NSPAWNMGR_FORCE_DB_WIZARD=${NSPAWNMGR_FORCE_DB_WIZARD})
  auth.war login page:   http://localhost:${TOMCAT_HTTP_PORT}/auth/login?returnTo=http://localhost:${TOMCAT_HTTP_PORT}/nspawnmgr/ (backend=${AUTH_BACKEND})
  fake-guacamole-server: http://localhost:${TOMCAT_HTTP_PORT}/guacamole
  web test harness:      http://localhost:${TOMCAT_HTTP_PORT}/test-harness/

Log in at the auth.war URL above first (any local OS account works) to get the session cookie,
then browse to nspawnmgr. Or run site/scripts/smoke-test.sh for a scripted end-to-end check.
Stop everything with tools/scripts/stop-dev-stack.sh.
EOF
