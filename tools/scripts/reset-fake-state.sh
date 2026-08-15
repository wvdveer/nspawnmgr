#!/usr/bin/env bash
# Clears fake-guacamole-server's in-memory users/connections/permissions between test runs.
# (The nspawnmgr H2 dev database and fake-machinectl state reset themselves on app restart.)
set -euo pipefail

curl -fsS -X POST "http://localhost:8080/guacamole/fake/reset" >/dev/null
echo "fake-guacamole-server state reset."
