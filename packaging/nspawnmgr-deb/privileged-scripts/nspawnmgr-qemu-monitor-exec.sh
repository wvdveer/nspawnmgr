#!/bin/sh
# Relays one HMP (Human Monitor Protocol - plain text, not QMP/JSON) command line, read from this
# script's own stdin, to VM $1's monitor socket - the mechanism behind
# ContainerCliExecutor.stopGraceful/pause/resume and VNC password re-application for QEMU (see
# RealContainerCliExecutor#qemuMonitorCommand). NOPASSWD: fixed shape (only $1 - a machine name - is
# ever part of the argv this sudoers rule matches against); the actual HMP command text travels
# entirely as stdin, never interpolated into a shell string, same security posture as script bodies
# run via startScript elsewhere in this app.
#
# `-T2`: HMP is an interactive REPL with no clean per-response boundary marker (just a `(qemu) `
# prompt after every reply, no length-prefixed framing) - rather than trying to parse that boundary,
# this closes the connection 2 seconds after QEMU stops sending anything new. This is a starting
# point, not yet verified against a real qemu-system-x86_64 monitor - see
# project_qemu_lifecycle_design_corrections memory for this and other flagged-unverified QEMU pieces.
# $1 = VM name.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-qemu-monitor-exec.sh must be run as root." >&2
    exit 1
fi
if ! command -v socat >/dev/null 2>&1; then
    echo "socat is not installed - required to relay QEMU monitor commands." >&2
    exit 1
fi

socket_path="/var/lib/nspawnmgr/qemu-sockets/$1.monitor.sock"
if [ ! -S "$socket_path" ]; then
    echo "No monitor socket for '$1' at $socket_path - is the VM running?" >&2
    exit 1
fi

exec socat -T2 - "UNIX-CONNECT:$socket_path"
