#!/bin/sh
# Lists immediate children of $1 as tab-separated "type\tname\tsize\tmtime" lines (one per entry) -
# type is "d" or "f" (symlinks/other special files are skipped rather than misreported). stat-based,
# not `ls -la` parsing, to avoid whitespace/locale/column-count pitfalls - filenames here are
# arbitrary user content and may contain spaces, which is also why this is tab- rather than
# space-separated (unlike nspawnmgr-list-auto-cache.sh, where filenames are always
# nspawnmgr-controlled package names). $1 is always a fully resolved, already-validated absolute
# path computed by nspawnmgr's own Java code (ContainerFileBrowserService) - never taken directly
# from whoever is browsing - same NOPASSWD trust tier as nspawnmgr-list-auto-cache.sh.
set -e
DIR="$1"
[ -d "$DIR" ] || exit 0
for f in "$DIR"/*; do
    [ -e "$f" ] || continue
    name="$(basename "$f")"
    if [ -d "$f" ]; then
        type="d"
    elif [ -f "$f" ]; then
        type="f"
    else
        continue
    fi
    size=$(stat -c%s "$f")
    mtime=$(stat -c%Y "$f")
    printf '%s\t%s\t%s\t%s\n' "$type" "$name" "$size" "$mtime"
done
