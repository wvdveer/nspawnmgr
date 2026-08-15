#!/bin/sh
# Lists "<filename> <size-bytes>" for every file already present in $1 (a host-visible
# CACHE_ROOT/<package-manager>/auto path) - backs the admin Packages page's "Show transitive
# dependencies" button, a read-only view into what
# ContainerFilesystemProvisioner#downloadPackagesIntoContainer has already fetched onto the host but
# deliberately never registers individually in the CachedPackage table (see PackageCacheService's
# own javadoc for why). Read-only and always safe, so NOPASSWD like nspawnmgr-list-template-files.sh.
set -e
DIR="$1"
mkdir -p "$DIR"
for f in "$DIR"/*; do
    [ -e "$f" ] || continue
    echo "$(basename "$f") $(wc -c < "$f")"
done
