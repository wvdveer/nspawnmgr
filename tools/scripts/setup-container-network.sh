#!/usr/bin/env bash
# One-time-per-run (idempotent) preparation of the shared container bridge (nspawnbr0) for the
# real-container-lifecycle CI job (tools/scripts/real-lifecycle-test.sh).
#
# On a real install, the .deb's postinst writes packaging/nspawnmgr-deb/70-nspawnmgr-bridge.netdev
# and .network into /etc/systemd/network/ and restarts systemd-networkd (see debian/postinst's
# "6h. Shared bridge" step) - that's what makes NspawnSettingsRenderer's Bridge=nspawnbr0 setting
# resolvable when systemd-nspawn brings a container's veth up. This CI job never installs a .deb
# though (packaging/publishing is a separate, later job - see .gitea/workflows/build.yml's
# publish-deb, which needs: real-container-lifecycle rather than the other way around); it builds
# and runs the WARs directly against gitslave-host over SSH+sudo. Without this script, `machinectl
# start` fails outright ("control process exited with error code") because nspawnbr0 simply doesn't
# exist on the runner - confirmed live via a real CI failure log after the bridge-networking
# migration first shipped.
#
# Skips the write+restart if the bridge is already up with the right address, so repeated CI runs
# don't restart systemd-networkd (and briefly disrupt any already-running containers' networking)
# every single time - same "skip if already done" idempotency convention as
# setup-container-template.sh's tarball check.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=lib/ssh-sudo.sh
. "$root/tools/scripts/lib/ssh-sudo.sh"

netdev_content="$(cat "$root/packaging/nspawnmgr-deb/70-nspawnmgr-bridge.netdev")"
network_content="$(cat "$root/packaging/nspawnmgr-deb/70-nspawnmgr-bridge.network")"

if remote_sudo "ip -4 -o addr show nspawnbr0 2>/dev/null | grep -q '10\.100\.0\.1/24'"; then
    echo "nspawnbr0 already up with 10.100.0.1/24, skipping."
    exit 0
fi

echo "nspawnbr0 missing or misconfigured on the runner - installing the bridge files and restarting systemd-networkd..."
remote_sudo "
cat > /etc/systemd/network/70-nspawnmgr-bridge.netdev <<'NSPAWNMGR_NETDEV_EOF'
$netdev_content
NSPAWNMGR_NETDEV_EOF
cat > /etc/systemd/network/70-nspawnmgr-bridge.network <<'NSPAWNMGR_NETWORK_EOF'
$network_content
NSPAWNMGR_NETWORK_EOF
systemctl restart systemd-networkd
"

echo "Waiting for nspawnbr0 to come up..."
for _ in $(seq 1 20); do
    if remote_sudo "ip -4 -o addr show nspawnbr0 2>/dev/null | grep -q '10\.100\.0\.1/24'"; then
        echo "nspawnbr0 is up with 10.100.0.1/24."
        exit 0
    fi
    sleep 1
done

echo "nspawnbr0 never came up with 10.100.0.1/24 after restarting systemd-networkd - check 'systemctl status systemd-networkd' on the runner." >&2
exit 1
