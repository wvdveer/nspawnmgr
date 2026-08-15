#!/usr/bin/env bash
# Starts the REAL stack for tools/scripts/real-lifecycle-test.sh: nspawnmgr.war (at /nspawnmgr),
# guacamole.war (the tools/fake-guacamole-server stand-in, at /guacamole), and auth.war (at /auth)
# all deployed side by side into the same site/tomcat instance, under SPRING_PROFILES_ACTIVE=prod
# so the real
# SshRemoteExecutor-backed CLI/filesystem provisioners are active (@Profile("!dev")) instead of the
# fakes tools/scripts/start-dev-stack.sh uses. All three target javax.servlet (Servlet 4.0), which
# is why they can share one Tomcat instance — see auth/pom.xml's own comment. Modeled on
# tools/scripts/test-tomcat-with-guacamole.sh's CATALINA_OPTS recipe, extended with real
# nspawnmgr.ssh.* credentials (from dev_env/ssh-account.env, via tools/scripts/lib/ssh-sudo.sh) and
# pointed at the co-located fake-guacamole-server instead of a real Guacamole install, so
# ProvisioningService.provisionSsh's Guacamole connection registration works without needing
# guacd/an auth backend configured.
#
# NSPAWN_MACHINES_DIR/NSPAWN_SETTINGS_DIR are deliberately NOT overridden here (unlike
# test-tomcat-with-guacamole.sh, which sandboxes them under $tomcat_dir/temp/ since it never
# actually exercises the executor): machinectl/systemd-nspawn only ever look at the real, fixed
# system paths (/var/lib/machines, /etc/systemd/nspawn — application.yml's own defaults), not
# whatever nspawnmgr's own config says, so a sandboxed path here would make machinectl unable to
# find what nspawnmgr just wrote. This means this script has real, lasting effects on those system
# directories on whatever box it runs on — tools/scripts/real-lifecycle-test.sh cleans up after
# itself via the app's own DELETE /api/containers/{id} flow.
#
# nspawnmgr ends up at http://localhost:${TOMCAT_HTTP_PORT:-8080}/nspawnmgr (WAR deployment into a
# real Tomcat — server.port is irrelevant here).
set -euo pipefail

skip_build=false
if [[ "${1:-}" == "--skip-build" ]]; then
    skip_build=true
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib/ssh-sudo.sh
. "$root/tools/scripts/lib/ssh-sudo.sh"

TOMCAT_HTTP_PORT="${TOMCAT_HTTP_PORT:-8080}"
tomcat_dir="$root/site/tomcat"
log_dir="${TMPDIR:-/tmp}/nspawnmgr-realtest/logs"
mkdir -p "$log_dir"

# HOST_PUBLIC_ADDRESS: what guacd/ContainerReadinessPollingService dial to reach *into* a
# container's forwarded port. A literal 127.0.0.1 breaks on any host whose NAT won't hairpin
# loopback-destined traffic back to a container (seen live: systemd-networkd's own io.systemd.nat
# table excludes 127.0.0.0/8 from its `output` hairpin chain by design, so a self-connection to
# 127.0.0.1:<port> never reaches the container even though the forward itself is configured
# correctly) - auto-detect this host's real address instead, still overridable via
# dev_env/ssh-account.env for anyone who needs something else.
#
# Deliberately NOT `ip route get 1.1.1.1` - on a host whose default route for general internet
# traffic goes over a VPN tunnel, that picks the *tunnel's* address, which isn't a normal local
# address for hairpin purposes and isn't what any firewall rule for this host's LAN is scoped to
# (confirmed live: exactly this happened on a host with a tun-pure VPN interface). Instead, list
# this host's own global-scope addresses directly and skip virtual/tunnel-style interfaces.
if [[ -z "${HOST_PUBLIC_ADDRESS:-}" ]]; then
    HOST_PUBLIC_ADDRESS="$(ip -4 -o addr show scope global 2>/dev/null | awk '
        { iface=$2; addr="";
          for (i=1;i<=NF;i++) if ($i=="inet") { addr=$(i+1); sub(/\/.*/,"",addr) }
          if (addr != "" && iface !~ /^(tun|tap|wg|ppp|docker|veth|ve-|br-)/) { print addr; exit } }')"
    if [[ -z "$HOST_PUBLIC_ADDRESS" ]]; then
        HOST_PUBLIC_ADDRESS="$(hostname -I 2>/dev/null | awk '{print $1}')"
    fi
    HOST_PUBLIC_ADDRESS="${HOST_PUBLIC_ADDRESS:-127.0.0.1}"
fi
echo "Using HOST_PUBLIC_ADDRESS=$HOST_PUBLIC_ADDRESS (override via dev_env/ssh-account.env if wrong)"

if [[ "$skip_build" == false ]]; then
    "$root/tools/scripts/build-all.sh"
fi

echo "Setting up Tomcat 9 + deploying nspawnmgr.war..."
TOMCAT_HTTP_PORT="$TOMCAT_HTTP_PORT" "$root/tools/scripts/setup-tomcat.sh"

echo "Deploying guacamole.war (fake stand-in) alongside it..."
cp "$root/tools/fake-guacamole-server/target/guacamole.war" "$tomcat_dir/webapps/guacamole.war"

echo "Deploying auth.war alongside it..."
rm -rf "$tomcat_dir/webapps/auth"
cp "$root/auth/target/auth.war" "$tomcat_dir/webapps/auth.war"

echo "Starting nspawnmgr.war (prod profile, real SSH+sudo executors) + auth.war (at /auth) in Tomcat on :${TOMCAT_HTTP_PORT}..."
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
export CATALINA_HOME="$tomcat_dir"
# Every value quoted: catalina.sh eval's $CATALINA_OPTS as a fresh shell command line, so a raw
# unescaped ';' (like H2's own AUTO_SERVER=TRUE separator) gets treated as a shell command
# separator, not a literal character — silently truncating the java invocation before ever
# reaching Tomcat's main class (confirmed: this exact failure mode, javac's own usage/help text
# dumped to catalina.out, JVM never actually starts). Single-quoting each value here protects it
# through that second eval pass.
export CATALINA_OPTS="-DSPRING_PROFILES_ACTIVE=prod \
  -DDB_URL='jdbc:h2:file:$tomcat_dir/temp/realtest-db;AUTO_SERVER=TRUE' -DDB_USERNAME=sa -DDB_PASSWORD= -DDB_VENDOR=h2 \
  -DAPP_SECRET_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY= \
  -DTEMPLATES_DIR='$root/site/templates' \
  -DSSH_HOST='$SSH_HOST' -DSSH_PORT='$SSH_PORT' -DSSH_USERNAME='$SSH_USERNAME' -DSSH_PASSWORD='$SSH_PASSWORD' \
  -DGUACAMOLE_BASE_URL='http://localhost:${TOMCAT_HTTP_PORT}/guacamole' -DGUACAMOLE_DATA_SOURCE=fake \
  -DUSER_ID_URL='http://localhost:${TOMCAT_HTTP_PORT}/auth/userinfo' \
  -DHOST_PUBLIC_ADDRESS='$HOST_PUBLIC_ADDRESS'"
"$tomcat_dir/bin/startup.sh"
echo "$tomcat_dir" >"$log_dir/tomcat_dir"

cat <<EOF

Real stack starting. Logs under $log_dir, Tomcat's own log at $tomcat_dir/logs/catalina.out
  nspawnmgr:             http://localhost:${TOMCAT_HTTP_PORT}/nspawnmgr
  auth.war login page:   http://localhost:${TOMCAT_HTTP_PORT}/auth/login?returnTo=http://localhost:${TOMCAT_HTTP_PORT}/nspawnmgr/
  fake-guacamole-server: http://localhost:${TOMCAT_HTTP_PORT}/guacamole

Stop everything with tools/scripts/stop-real-stack.sh.
EOF
