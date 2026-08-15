#!/bin/sh
# Writes stdin to $1, creating the parent directory first. Fixed-shape, no arbitrary content in
# the command line itself (only in stdin) — safe for a NOPASSWD sudoers grant on this exact path.
set -e
path="$1"
mkdir -p "$(dirname "$path")"
cat > "$path"
