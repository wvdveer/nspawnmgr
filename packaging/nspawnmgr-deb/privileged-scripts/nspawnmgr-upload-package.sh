#!/bin/sh
# Writes a base64-encoded package file (read from stdin) to $1, decoded to raw bytes. Base64 rather
# than a raw byte passthrough because the SSH stdin channel this arrives over is a Java String
# (UTF-8 encoded) - arbitrary binary content (a .deb/.rpm) would be corrupted by that round trip
# otherwise. $1 is always computed by nspawnmgr's own Java code, never taken directly from whoever
# uploaded the file - same NOPASSWD trust tier as nspawnmgr-write-file.sh.
set -e
path="$1"
mkdir -p "$(dirname "$path")"
base64 -d > "$path"
