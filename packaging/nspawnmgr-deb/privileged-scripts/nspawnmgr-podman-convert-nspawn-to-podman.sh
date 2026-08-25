#!/bin/sh
# Converts an existing nspawn template's rootfs tarball into a podman image - the action behind
# the Templates admin page's per-row "Create podman template" button (see
# TemplateService.convertToPodman / ContainerFilesystemProvisioner.convertNspawnTemplateToPodman).
# `podman import` accepts a plain rootfs tarball directly (.tar/.tar.gz/etc) - this is literally
# what it's for, no intermediate conversion needed. $1 = source nspawn .tar.gz path, $2 = tag to
# import it as, $3 = destination podman-template .tar path (same "one physical file per template"
# model as nspawnmgr-podman-pull-template.sh).
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-podman-convert-nspawn-to-podman.sh must be run as root." >&2
    exit 1
fi

if [ ! -f "$1" ]; then
    echo "Source template file not found: $1" >&2
    exit 1
fi
if [ -e "$3" ]; then
    echo "Target file already exists" >&2
    exit 1
fi
if ! command -v podman >/dev/null 2>&1; then
    echo "podman is not installed - see the Diagnostics page." >&2
    exit 1
fi

mkdir -p "$(dirname "$3")"
podman import "$1" "$2"
podman save -o "$3" "$2"
