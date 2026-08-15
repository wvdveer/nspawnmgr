#!/usr/bin/env bash
# Shared helper, sourced by tools/scripts/setup-container-template.sh and
# tools/scripts/real-lifecycle-test.sh: runs commands as root on the container host by SSHing into
# the sudo-capable account from dev_env/ssh-account.env and piping its password to both the SSH
# login (via sshpass — plain OpenSSH needs a helper for non-interactive password auth from a shell
# script) and to `sudo -S` once connected, mirroring what SshRemoteExecutor
# (src/main/java/com/nspawnmgr/cli/real/SshRemoteExecutor.java) does from the Java side: `sudo -S`
# reads exactly one line of stdin as the password, then hands the rest of stdin through to the
# child process — here that child is a bare `bash` reading its script from stdin.
#
# Host key checking is intentionally off (matches nspawnmgr.ssh.strict-host-key-checking's own
# default): the target is always the loopback interface of the same box this script runs on.

ssh_env_file="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)/dev_env/ssh-account.env"
if [[ ! -f "$ssh_env_file" ]]; then
    echo "Missing $ssh_env_file - copy dev_env/ssh-account.env.example there and fill in real values." >&2
    exit 1
fi
set -a
# shellcheck source=/dev/null
. "$ssh_env_file"
set +a
: "${SSH_HOST:?SSH_HOST not set in $ssh_env_file}"
: "${SSH_PORT:=22}"
: "${SSH_USERNAME:?SSH_USERNAME not set in $ssh_env_file}"
: "${SSH_PASSWORD:?SSH_PASSWORD not set in $ssh_env_file}"

if ! command -v sshpass >/dev/null; then
    echo "sshpass is required (apt install sshpass) so these scripts can SSH into the sudo-capable account non-interactively." >&2
    exit 1
fi

_ssh_sudo_opts=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -p "$SSH_PORT")

# remote_sudo <script>: runs <script> as root on the SSH target via `bash -euo pipefail`.
remote_sudo() {
    local remote_script="$1"
    sshpass -p "$SSH_PASSWORD" ssh "${_ssh_sudo_opts[@]}" "${SSH_USERNAME}@${SSH_HOST}" \
        "sudo -S -p '' bash" <<EOF
$SSH_PASSWORD
set -euo pipefail
$remote_script
EOF
}

# remote_test <test-expression>: e.g. `remote_test "-f /some/marker"`. Exit code is the remote
# test's exit code (0/1), not treated as a failure by this function itself.
remote_test() {
    local expr="$1"
    sshpass -p "$SSH_PASSWORD" ssh "${_ssh_sudo_opts[@]}" "${SSH_USERNAME}@${SSH_HOST}" \
        "sudo -S -p '' bash" <<EOF
$SSH_PASSWORD
test $expr
EOF
}
