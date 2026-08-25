#!/bin/sh
# Flattens an existing podman template's image back into a plain rootfs tarball - the action
# behind the Templates admin page's per-row "Create nspawn template" button (see
# TemplateService.convertToNspawn / ContainerFilesystemProvisioner.convertPodmanTemplateToNspawn).
# `podman export` operates on a CONTAINER, not directly an image, so this creates (but never
# starts) a throwaway container purely to have something to export - cleaned up via `trap` so a
# failed export doesn't leave it behind. $1 = source podman-template .tar path (as produced by
# nspawnmgr-podman-pull-template.sh/nspawnmgr-podman-convert-nspawn-to-podman.sh), $2 =
# destination nspawn-template .tar.gz path.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-podman-convert-podman-to-nspawn.sh must be run as root." >&2
    exit 1
fi

if [ ! -f "$1" ]; then
    echo "Source template file not found: $1" >&2
    exit 1
fi
if [ -e "$2" ]; then
    echo "Target file already exists" >&2
    exit 1
fi
if ! command -v podman >/dev/null 2>&1; then
    echo "podman is not installed - see the Diagnostics page." >&2
    exit 1
fi

mkdir -p "$(dirname "$2")"

# podman load prints "Loaded image: <ref>" (or, on older versions, just the image ID) - the last
# whitespace-separated token on its final non-empty line is the reference either way.
LOADED_REF="$(podman load -i "$1" | tail -1 | awk '{print $NF}')"
if [ -z "$LOADED_REF" ]; then
    echo "Could not determine the loaded image reference from 'podman load' output." >&2
    exit 1
fi

TMP_NAME="nspawnmgr-convert-$$"
cleanup() {
    podman rm -f "$TMP_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

podman create --name "$TMP_NAME" "$LOADED_REF" >/dev/null
podman export "$TMP_NAME" | gzip > "$2"
