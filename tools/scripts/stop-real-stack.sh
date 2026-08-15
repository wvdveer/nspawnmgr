#!/usr/bin/env bash
# Stops the Tomcat instance started by start-real-stack.sh (nspawnmgr.war, guacamole.war, and
# auth.war all deploy into this one instance, so stopping it stops all three).
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
log_dir="${TMPDIR:-/tmp}/nspawnmgr-realtest/logs"

tomcat_dir="$root/site/tomcat"
if [[ -f "$log_dir/tomcat_dir" ]]; then
    tomcat_dir="$(cat "$log_dir/tomcat_dir")"
fi
if [[ -x "$tomcat_dir/bin/shutdown.sh" ]]; then
    "$tomcat_dir/bin/shutdown.sh" >/dev/null 2>&1 || true
fi
