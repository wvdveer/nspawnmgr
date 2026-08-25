#!/bin/sh
# Pulls a podman image and saves it as a single portable file - the action behind the Templates
# admin page's "New Pod" button (see TemplateService.createFromPodmanPull /
# ContainerFilesystemProvisioner.pullPodmanTemplate). $1 = image reference to pull (e.g.
# docker.io/library/alpine:latest), $2 = destination .tar path (TEMPLATES_DIR/podman/<name>.tar) -
# kept as one physical file per template, the same "artifact lives under TEMPLATES_DIR" model
# nspawn templates already use, so listAvailableSourceFiles/delete-cleanup work unchanged for both
# backends with no special-casing.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-podman-pull-template.sh must be run as root." >&2
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
podman pull "$1"
podman save -o "$2" "$1"
