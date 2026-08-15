#!/bin/sh
# Base64-encodes $1's content to stdout. Base64 rather than a raw byte passthrough - the SSH stdout
# channel this returns over is read back as a Java String (UTF-8 decoded), which would corrupt
# arbitrary binary content otherwise (mirrors nspawnmgr-upload-package.sh's reasoning, in reverse).
# -w 0 disables GNU base64's default 76-column line wrapping - RealContainerFilesystemBrowser.download
# uses Base64.getDecoder() (the strict, non-MIME variant), which rejects embedded newlines outright.
# $1 is always computed by nspawnmgr's own Java code, never taken directly from whoever is browsing -
# same NOPASSWD trust tier as nspawnmgr-upload-package.sh.
set -e
path="$1"
[ -f "$path" ] || exit 1
base64 -w 0 "$path"
