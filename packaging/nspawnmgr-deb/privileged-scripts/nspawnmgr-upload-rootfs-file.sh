#!/bin/sh
# Writes a base64-encoded file (read from stdin) to $1, decoded to raw bytes. Refuses to overwrite
# an existing file/directory (exit 2, a distinct code from any other failure) - RealContainer
# FilesystemBrowser.upload turns that into a clear "already exists" error rather than silently
# clobbering something. Checked here rather than in Java first, to avoid a check-then-write race
# between two separate SSH round trips. Base64 rather than a raw byte passthrough - the SSH stdin
# channel this arrives over is a Java String (UTF-8 encoded), which would corrupt arbitrary binary
# content otherwise (same reasoning as nspawnmgr-upload-package.sh). $1 is always computed by
# nspawnmgr's own Java code, never taken directly from whoever is uploading - same NOPASSWD trust
# tier as nspawnmgr-upload-package.sh.
set -e
path="$1"
if [ -e "$path" ]; then
    echo "Already exists: $path" >&2
    exit 2
fi
mkdir -p "$(dirname "$path")"
base64 -d > "$path"
