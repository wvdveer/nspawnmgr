#!/bin/sh
# Copies one specific cached package file into a container's own local package-cache directory, so
# its later in-container install (already running via systemd-run) finds it locally and never needs
# network access. $1 = source file, $2 = destination directory (both always computed by nspawnmgr's
# own Java code). Distinct from nspawnmgr-download-packages.sh's own bulk copy step - this handles a
# single admin-uploaded file rather than everything currently in the shared auto-fetch cache.
set -e
mkdir -p "$2"
cp "$1" "$2/"
