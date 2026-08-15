#!/bin/sh
# Lists the Linux users inside machine $1 (getent passwd output) — read-only and always safe, so
# unlike the useradd/chpasswd path (ContainerCliExecutor.runInMachine, password-required tier),
# this is a fixed-shape NOPASSWD command: it never needs a sudo password or admin approval, only
# a running machine to reach into.
set -e
systemd-run --machine="$1" --pipe --quiet --wait getent passwd
