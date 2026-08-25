#!/usr/bin/env bash
# Orchestrates the real container-lifecycle test: starts the real stack (start-real-stack.sh),
# seeds a Template row pointing at the real, pre-baked rootfs from setup-container-template.sh,
# logs into auth.war for a session, creates a container through the real POST /api/containers ->
# ProvisioningService.provision() flow, polls until RUNNING, independently verifies against the
# real host via SSH+machinectl (not just the app's own DB state), creates and runs a named
# container script (POST .../scripts/run) with a deliberate `sleep 1.5` between a stdout and a
# stderr line to prove ContainerScriptService/SshRemoteExecutor's timestamped-capture path
# produces genuine, distinctly-timed per-line output against a real container (not just the
# canned fixed-gap output FakeContainerCliExecutor returns in the dev stack), then force-stops and
# deletes the container and verifies that removal too. This is the actual proof that
# RealContainerCliExecutor/RealContainerFilesystemProvisioner/SshRemoteExecutor work end-to-end on
# a real Linux box, complementing tools/scripts/smoke-test.sh which only exercises the fakes.
#
# Does NOT cover the "shared user can act / non-owner+non-shared gets 403" authorization matrix
# for scripts (ContainerApiController.requireOwnedOrShared) - that needs a second real, logged-in
# OS account, and this runner only has one provisioned (dev_env/ci-test-user.env). That logic is
# covered by the dev-stack click-through and code review only; see docs/administrator-guide.md's
# script trust-boundary section for the reasoning.
#
# Usage: real-lifecycle-test.sh [nspawnmgr_url] [auth_url]
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib/ssh-sudo.sh
. "$root/tools/scripts/lib/ssh-sudo.sh"

ci_user_env="$root/dev_env/ci-test-user.env"
if [[ ! -f "$ci_user_env" ]]; then
    echo "Missing $ci_user_env - copy dev_env/ci-test-user.env.example there and fill in a real account." >&2
    exit 1
fi
set -a
# shellcheck source=/dev/null
. "$ci_user_env"
set +a
: "${CI_TEST_USERNAME:?CI_TEST_USERNAME not set in $ci_user_env}"
: "${CI_TEST_PASSWORD:?CI_TEST_PASSWORD not set in $ci_user_env}"

TOMCAT_HTTP_PORT="${TOMCAT_HTTP_PORT:-8080}"
nspawnmgr_url="${1:-http://localhost:${TOMCAT_HTTP_PORT}/nspawnmgr}"
auth_url="${2:-http://localhost:${TOMCAT_HTTP_PORT}/auth}"
tomcat_dir="$root/site/tomcat"
container_name="real-it-$(date +%s)"
cookie_jar="$(mktemp)"

# Don't assume ~/.m2/repository - ask Maven itself, since a global/user settings.xml can (and on
# this runner, does: /usr/share/maven/repository) redirect the local repo elsewhere.
maven_local_repo="$(mvn -q -f "$root/pom.xml" help:evaluate -Dexpression=settings.localRepository -DforceStdout 2>/dev/null || true)"
if [[ -z "$maven_local_repo" ]]; then
    maven_local_repo="$HOME/.m2/repository"
fi
# `|| true`: under `set -e` + `pipefail`, if `find`'s starting path doesn't exist it returns
# non-zero, which - since this whole pipeline is a plain assignment - would otherwise abort the
# script right here with zero output, before the -z check below ever gets a chance to print a
# useful message (this exact silent-death pattern already bit setup-container-template.sh once).
h2_jar="$(find "$maven_local_repo/com/h2database/h2" -name 'h2-*.jar' 2>/dev/null | sort -V | tail -1 || true)"
if [[ -z "$h2_jar" ]]; then
    echo "Couldn't find the h2 jar under $maven_local_repo - run 'mvn -DskipTests install' at repo root first." >&2
    exit 1
fi

cleanup() {
    local exit_code=$?
    if [[ $exit_code -ne 0 ]]; then
        echo "== FAILURE: dumping service logs =="
        for f in "${TMPDIR:-/tmp}"/nspawnmgr-realtest/logs/*.log "$tomcat_dir"/logs/catalina.out; do
            [[ -f "$f" ]] || continue
            echo "===== $f ====="
            cat "$f" || true
        done
        echo "Best-effort cleanup of test container '$container_name' on the real host..."
        remote_sudo "machinectl terminate '$container_name' 2>/dev/null || true; machinectl remove '$container_name' 2>/dev/null || true" || true
    fi
    rm -f "$cookie_jar"
    "$root/tools/scripts/stop-real-stack.sh" || true
    exit $exit_code
}
trap cleanup EXIT

echo "1. Ensuring the shared container bridge (nspawnbr0) exists..."
"$root/tools/scripts/setup-container-network.sh"

echo "2. Preparing the real container template..."
"$root/tools/scripts/setup-container-template.sh"

echo "3. Starting the real stack..."
"$root/tools/scripts/start-real-stack.sh"

echo "4. Waiting for all three services to respond..."
for url in "http://localhost:${TOMCAT_HTTP_PORT}/guacamole/" "$auth_url/login" "$nspawnmgr_url/"; do
    echo "   Waiting for $url..."
    ready=false
    for _ in $(seq 1 40); do
        if curl -sf -o /dev/null "$url"; then
            ready=true
            break
        fi
        sleep 3
    done
    if [[ "$ready" != true ]]; then
        echo "$url never became ready" >&2
        exit 1
    fi
done

echo "5. Seeding the real test template row..."
db_url="jdbc:h2:file:$tomcat_dir/temp/realtest-db;AUTO_SERVER=TRUE"
java -cp "$h2_jar" org.h2.tools.Shell -url "$db_url" -user sa -password "" -sql \
    "INSERT INTO templates (name, description, source_path, package_manager, install_ssh_command, rdp_state, active) VALUES ('debian-real-nspawn', 'Real systemd-nspawn test image (sshd pre-baked)', 'debian-real', 'APT', 'true', 'NOT_CAPABLE', TRUE)"

echo "6. Logging in as $CI_TEST_USERNAME via auth.war..."
curl -fsS -c "$cookie_jar" -X POST "$auth_url/login" \
    --data-urlencode "username=$CI_TEST_USERNAME" --data-urlencode "password=$CI_TEST_PASSWORD" >/dev/null

# No guacd-connectivity assertion here, deliberately: start-real-stack.sh deploys
# tools/fake-guacamole-server (see its own header comment), not real guacd/guacamole.war - this
# harness exercises real machinectl/SSH+sudo container lifecycle, not real Guacamole integration.
# NetworkDiagnosticsService's guacd-connectivity check is still real and useful (it runs against
# whatever's actually configured on a genuine install, e.g. via the admin UI) - it just has nothing
# meaningful to check against here, since guacamole.properties is never populated in this setup.

echo "7. Fetching the real test template's id..."
template_id="$(curl -fsS -b "$cookie_jar" "$nspawnmgr_url/api/templates" \
    | grep -oP '\{"id":\K[0-9]+(?=,"name":"debian-real-nspawn")' || true)"
if [[ -z "$template_id" ]]; then
    echo "debian-real-nspawn template not found via /api/templates" >&2
    exit 1
fi
echo "   template id=$template_id"

echo "8. Creating container '$container_name'..."
container_id="$(curl -fsS -b "$cookie_jar" -X POST "$nspawnmgr_url/api/containers" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$container_name\",\"templateId\":$template_id,\"rdpEnabled\":false}" \
    | grep -oP '"id":\K[0-9]+' || true)"
if [[ -z "$container_id" ]]; then
    echo "Failed to create container (no id in response)" >&2
    exit 1
fi
echo "   container id=$container_id, polling for RUNNING..."

state="CREATING"
response=""
for _ in $(seq 1 60); do
    sleep 2
    response="$(curl -fsS -b "$cookie_jar" "$nspawnmgr_url/api/containers/$container_id/status")"
    state="$(echo "$response" | grep -oP '"state":"\K[^"]+' || true)"
    echo "   state=$state"
    [[ "$state" == "RUNNING" || "$state" == "ERROR" ]] && break
done
if [[ "$state" != "RUNNING" ]]; then
    echo "Container did not reach RUNNING: $response" >&2
    exit 1
fi

echo "9. Independently verifying against the real host via machinectl..."
real_state="$(remote_sudo "machinectl show '$container_name' --property=State")"
echo "   $real_state"
if [[ "$real_state" != "State=running" ]]; then
    echo "machinectl disagrees with nspawnmgr's own state (real_state=$real_state)" >&2
    exit 1
fi

echo "10. Creating and running a real script (stdout, sleep 1.5, stderr) via POST .../scripts/run..."
# Exercises ContainerScriptService.run -> RealContainerCliExecutor.runScript's real
# `systemd-run --machine=... --pipe --quiet --wait /bin/sh -s` path (see docs/administrator-guide.md's
# script trust-boundary section) - the dev stack only ever exercises FakeContainerCliExecutor's
# canned output, so this is the only place that proves real per-line timestamped stdout/stderr
# capture actually works against a real container. The literal `\"`/`\n` below are JSON escapes,
# not shell ones - this is a single-quoted literal, sent to curl -d as-is.
run_request='{"name":"real-e2e-script","scriptBody":"echo \"real-e2e-stdout-line\"\nsleep 1.5\necho \"real-e2e-stderr-line\" 1>&2"}'
run_response="$(curl -fsS -b "$cookie_jar" -X POST "$nspawnmgr_url/api/containers/$container_id/scripts/run" \
    -H 'Content-Type: application/json' -d "$run_request")"
run_exit_code="$(echo "$run_response" | grep -oP '"exitCode":\K-?[0-9]+' || true)"
if [[ "$run_exit_code" != "0" ]]; then
    echo "Script did not exit 0: $run_response" >&2
    exit 1
fi
script_id="$(echo "$run_response" | grep -oP '"scriptId":\K[0-9]+' || true)"
if [[ -z "$script_id" ]]; then
    echo "No scriptId in run response: $run_response" >&2
    exit 1
fi
echo "   script id=$script_id, exit code=$run_exit_code"

echo "11. Verifying the run's per-line output has real, distinct stdout/stderr timestamps..."
stdout_ts="$(echo "$run_response" | grep -oP '"timestamp":"\K[^"]+(?="[^}]*"source":"STDOUT")' | head -1 || true)"
stderr_ts="$(echo "$run_response" | grep -oP '"timestamp":"\K[^"]+(?="[^}]*"source":"STDERR")' | head -1 || true)"
if [[ -z "$stdout_ts" || -z "$stderr_ts" ]]; then
    echo "Missing a STDOUT or STDERR line in run response: $run_response" >&2
    exit 1
fi
stdout_ms="$(date -d "$stdout_ts" +%s%3N)"
stderr_ms="$(date -d "$stderr_ts" +%s%3N)"
gap_ms=$(( stderr_ms - stdout_ms ))
echo "   stdout at $stdout_ts, stderr at $stderr_ts (gap ${gap_ms}ms)"
# The script's own `sleep 1.5` guarantees this if (and only if) SshRemoteExecutor's two reader
# threads are really timestamping lines as they arrive, rather than e.g. both being stamped
# together after the whole command finishes.
if (( gap_ms < 1000 )); then
    echo "Expected the real sleep 1.5 in the script to produce a >1s gap between the stdout and stderr lines, got ${gap_ms}ms" >&2
    exit 1
fi

echo "12. Confirming the script persisted (visible via GET .../scripts)..."
script_list_response="$(curl -fsS -b "$cookie_jar" "$nspawnmgr_url/api/containers/$container_id/scripts")"
if ! echo "$script_list_response" | grep -q '"name":"real-e2e-script"'; then
    echo "real-e2e-script not found in script list: $script_list_response" >&2
    exit 1
fi

echo "13. Force-stopping the container..."
curl -fsS -b "$cookie_jar" -X POST "$nspawnmgr_url/api/containers/$container_id/force-stop" >/dev/null
response="$(curl -fsS -b "$cookie_jar" "$nspawnmgr_url/api/containers/$container_id/status")"
state="$(echo "$response" | grep -oP '"state":"\K[^"]+' || true)"
if [[ "$state" != "STOPPED" ]]; then
    echo "Container did not reach STOPPED: $response" >&2
    exit 1
fi

echo "14. Deleting the container..."
curl -fsS -b "$cookie_jar" -X DELETE "$nspawnmgr_url/api/containers/$container_id" >/dev/null
# ApiExceptionHandler maps the "no such container" IllegalArgumentException to 400.
status_code="$(curl -s -o /dev/null -w '%{http_code}' -b "$cookie_jar" "$nspawnmgr_url/api/containers/$container_id/status")"
if [[ "$status_code" != "400" ]]; then
    echo "Expected the deleted container's status endpoint to 400, got HTTP $status_code" >&2
    exit 1
fi

echo "15. Independently verifying removal against the real host..."
if remote_test "-d '/var/lib/machines/$container_name'"; then
    echo "Machine directory /var/lib/machines/$container_name still exists after delete" >&2
    exit 1
fi

echo
echo "Real container-lifecycle test passed."
