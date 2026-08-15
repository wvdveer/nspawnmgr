#!/usr/bin/env bash
# Builds nspawnmgr.war and auth.war (if needed), sets up a fresh Tomcat 9 with nspawnmgr.war,
# Guacamole's official WAR, and auth.war all deployed side by side, starts it, and checks that all
# three webapps actually respond over HTTP — proving they can coexist in one Tomcat instance,
# which was the whole point of downgrading nspawnmgr and auth to Boot 2.7/javax.servlet (see root
# pom.xml and auth/pom.xml).
#
# This is a *deployment coexistence* check, not a functional Guacamole/auth test: real remote
# Guacamole sessions additionally need guacd (the native proxy daemon) running and an
# authentication backend configured via GUACAMOLE_HOME/guacamole.properties, neither of which this
# script sets up. Guacamole responding with a "no authentication backend configured" page (or
# similar non-404) still counts as a pass here — it proves the webapp deployed and initialized
# correctly. Likewise, auth.war just needs to serve its login form, not actually authenticate
# anyone (that's tools/scripts/real-lifecycle-test.sh/site/scripts/smoke-test.sh's job).
set -euo pipefail

TOMCAT_HTTP_PORT="${TOMCAT_HTTP_PORT:-8080}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tomcat_dir="$root/site/tomcat"

echo "== 1/5: Building nspawnmgr.war =="
if [[ ! -f "$root/target/nspawnmgr.war" ]]; then
    mvn -f "$root/pom.xml" -q -DskipTests package
fi

echo "== 2/5: Building auth.war =="
if [[ ! -f "$root/auth/target/auth.war" ]]; then
    mvn -f "$root/auth/pom.xml" -q -DskipTests package
fi

echo "== 3/5: Setting up Tomcat 9 + deploying nspawnmgr.war =="
"$root/tools/scripts/setup-tomcat.sh"

echo "== 4/5: Deploying Guacamole's WAR and auth.war =="
"$root/tools/scripts/setup-guacamole.sh"
rm -rf "$tomcat_dir/webapps/auth"
cp "$root/auth/target/auth.war" "$tomcat_dir/webapps/auth.war"

echo "== 5/5: Starting Tomcat and checking all three webapps respond =="
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export CATALINA_HOME="$tomcat_dir"
# Every value quoted: catalina.sh eval's $CATALINA_OPTS as a fresh shell command line, so a raw
# unescaped ';' (like H2's own DB_CLOSE_DELAY separator) gets treated as a shell command
# separator, not a literal character — silently truncating the java invocation before it ever
# reaches Tomcat's main class. Single-quoting each value protects it through that second eval pass.
#
# SSH_PASSWORD is never actually used here (this test never triggers a container operation), but
# SPRING_PROFILES_ACTIVE=prod activates SshPropertiesValidator (@Profile("!dev")), which fails
# startup fast if neither a password nor a private key is configured at all — a dummy value
# satisfies it without this coexistence check needing real SSH/sudo access.
export CATALINA_OPTS="-DSPRING_PROFILES_ACTIVE=prod \
  -DDB_URL='jdbc:h2:mem:nspawnmgrtomcattest;DB_CLOSE_DELAY=-1' -DDB_USERNAME=sa -DDB_PASSWORD= -DDB_VENDOR=h2 \
  -DAPP_SECRET_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY= \
  -DSSH_PASSWORD=coexistence-test-unused \
  -DTEMPLATES_DIR='$root/site/templates' \
  -DNSPAWN_MACHINES_DIR='$tomcat_dir/temp/machines' \
  -DNSPAWN_SETTINGS_DIR='$tomcat_dir/temp/nspawn-settings'"

"$tomcat_dir/bin/startup.sh"

cleanup() {
    "$tomcat_dir/bin/shutdown.sh" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Waiting for Tomcat to finish deploying all three webapps..."
for _ in $(seq 1 30); do
    sleep 1
    if grep -q "Server startup" "$tomcat_dir/logs/catalina.out" 2>/dev/null; then
        break
    fi
done

nspawnmgr_status="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${TOMCAT_HTTP_PORT}/nspawnmgr/")"
guacamole_status="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${TOMCAT_HTTP_PORT}/guacamole/")"
auth_status="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${TOMCAT_HTTP_PORT}/auth/login")"

echo ""
echo "nspawnmgr /nspawnmgr/:  $nspawnmgr_status"
echo "Guacamole /guacamole/:  $guacamole_status"
echo "auth      /auth/login: $auth_status"

pass=true
if [[ "$nspawnmgr_status" == "404" || "$nspawnmgr_status" == "000" ]]; then
    echo "FAIL: nspawnmgr did not deploy correctly"
    pass=false
fi
if [[ "$guacamole_status" == "404" || "$guacamole_status" == "000" ]]; then
    echo "FAIL: Guacamole did not deploy correctly"
    pass=false
fi
if [[ "$auth_status" == "404" || "$auth_status" == "000" ]]; then
    echo "FAIL: auth did not deploy correctly"
    pass=false
fi

if [[ "$pass" == true ]]; then
    echo "PASS: all three webapps deployed and responded in the same Tomcat 9 instance."
    exit 0
else
    echo "See $tomcat_dir/logs/catalina.out for details."
    exit 1
fi
