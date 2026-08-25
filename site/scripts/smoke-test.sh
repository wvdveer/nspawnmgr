#!/usr/bin/env bash
# End-to-end dev-loop smoke test: log in via auth.war (real OS auth), create a container, poll
# until provisioned, and mint a session URL — all against the fakes started by
# tools/scripts/start-dev-stack.sh. Intended for the Linux CI runner as well as local use (see
# site/scripts/smoke-test.ps1 for the Windows dev-loop equivalent).
#
# Usage: smoke-test.sh <username> <password> [nspawnmgr_url] [auth_url]
set -euo pipefail

username="${1:?Usage: smoke-test.sh <username> <password> [nspawnmgr_url] [auth_url]}"
password="${2:?Usage: smoke-test.sh <username> <password> [nspawnmgr_url] [auth_url]}"
nspawnmgr_url="${3:-http://localhost:8080/nspawnmgr}"
auth_url="${4:-http://localhost:8080/auth}"
container_name="smoke-$(date +%s)"
cookie_jar="$(mktemp)"
trap 'rm -f "$cookie_jar"' EXIT

echo "1. Logging in as $username via auth.war..."
curl -fsS -c "$cookie_jar" -X POST "$auth_url/login" \
    --data-urlencode "username=$username" --data-urlencode "password=$password" >/dev/null

echo "2. Fetching templates from nspawnmgr..."
template_id="$(curl -fsS -b "$cookie_jar" "$nspawnmgr_url/api/templates" | grep -oP '"id":\K[0-9]+' | head -1 || true)"
if [[ -z "$template_id" ]]; then
    # A fresh install starts with zero templates (no longer Flyway-seeded - see
    # docs/administrator-guide.md §2 "Container templates"), so create the dev placeholder here
    # instead of assuming a migration already did it. Requires $username to be ADMIN - true for
    # the first-ever login against a fresh dev DB (app-managed role mode's default), which is
    # exactly the case this branch actually runs in.
    echo "   None found — creating 'debian-minimal' via the admin API (matches site/templates/nspawn/debian-minimal)..."
    curl -fsS -b "$cookie_jar" -X POST "$nspawnmgr_url/api/admin/templates" \
        -H 'Content-Type: application/json' \
        -d '{"name":"debian-minimal","description":"Minimal Debian/Ubuntu base image (apt)","sourcePath":"debian-minimal","backend":"SYSTEMD_NSPAWN","packageManager":"APT","rdpState":"CAPABLE","active":true}' >/dev/null
    template_id="$(curl -fsS -b "$cookie_jar" "$nspawnmgr_url/api/templates" | grep -oP '"id":\K[0-9]+' | head -1 || true)"
fi
if [[ -z "$template_id" ]]; then
    echo "Failed to create or find a template via the admin API" >&2
    exit 1
fi
echo "   Using template id=$template_id"

echo "3. Creating container '$container_name'..."
container_id="$(curl -fsS -b "$cookie_jar" -X POST "$nspawnmgr_url/api/containers" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$container_name\",\"templateId\":$template_id,\"rdpEnabled\":false}" \
    | grep -oP '"id":\K[0-9]+' || true)"
if [[ -z "$container_id" ]]; then
    echo "Failed to create container (no id in response)" >&2
    exit 1
fi
echo "   Container id=$container_id, polling for provisioning to finish..."

state="CREATING"
for _ in $(seq 1 30); do
    sleep 1
    response="$(curl -fsS -b "$cookie_jar" "$nspawnmgr_url/api/containers/$container_id/status")"
    state="$(echo "$response" | grep -oP '"state":"\K[^"]+' || true)"
    echo "   state=$state"
    [[ "$state" == "RUNNING" || "$state" == "ERROR" ]] && break
done
if [[ "$state" != "RUNNING" ]]; then
    echo "Container did not reach RUNNING: $response" >&2
    exit 1
fi

echo "4. Minting an SSH session URL..."
url="$(curl -fsS -b "$cookie_jar" -X POST "$nspawnmgr_url/api/containers/$container_id/session/ssh" \
    | grep -oP '"url":"\K[^"]+')"
echo "   $url"

echo
echo "Smoke test passed."
echo "Run tools/scripts/reset-fake-state.sh to clear fake-guacamole-server state between runs."
