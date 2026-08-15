#!/bin/sh
# Prints machine $1's own internal IPv4 address (its host0 interface), or nothing (exit 0, empty
# stdout) if it hasn't been assigned one yet (still booting) - deliberately not an error, so the
# Java caller treats empty output as "not ready yet", not a failure.
#
# The nsenter/ip call is captured into a variable rather than piped directly - plain `sh` (dash)
# has no `pipefail`, so a failure partway through a pipeline (bad PID, missing netns, no host0
# interface) would otherwise be silently swallowed by the trailing awk/cut/head all still exiting
# 0 on empty input, masking a real error as "not ready yet" forever. Command substitution failure,
# unlike a mid-pipeline failure, does trip `set -e`.
set -e
machine="$1"
pid=$(machinectl show "$machine" --property=Leader --value)
addr_output=$(nsenter --net="/proc/$pid/ns/net" -- ip -4 -o addr show host0)
echo "$addr_output" | awk '{print $4}' | cut -d/ -f1 | head -n1
