#!/bin/sh
# Lists bare stems (no .tar.gz extension) of every template tarball already present in $1 (a
# host-visible TEMPLATES_DIR/<backend-subdir> path) - used by the admin "new template" form to
# offer a dropdown of not-yet-registered .tar.gz files instead of requiring the admin to type an
# exact filename from memory. Read-only and always safe, so NOPASSWD like listMachineImageNames.
set -e
DIR="$1"
mkdir -p "$DIR"
for f in "$DIR"/*.tar.gz; do
    [ -e "$f" ] || continue
    basename "$f" .tar.gz
done
