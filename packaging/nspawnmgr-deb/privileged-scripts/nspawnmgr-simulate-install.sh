#!/bin/sh
# Determines which of an uploaded local .deb's dependencies aren't already satisfied on the target
# container's rootfs, without installing anything - used by PackageCacheService's manual "Install
# package" flow so missing dependencies can be pre-fetched onto the host (see
# nspawnmgr-download-packages.sh) before the actual in-container install runs, the same way
# nspawnmgr's own SSH/RDP/VNC/desktop-manager provisioning already pre-fetches its own fixed
# package lists. Same -o Dir=$ROOTFS host-side-apt-against-the-container's-own-state trick those
# scripts already use.
#
# $1 = target container's rootfs dir (host-visible path, e.g. /var/lib/machines/<name>)
# $2 = host-visible path to the uploaded .deb to simulate installing (NOT inside $1 - it lives in
#      the admin package cache; apt resolves this as a plain filesystem path, independent of -o Dir=)
set -e
ROOTFS="$1"
DEB_PATH="$2"
OWN_NAME="$(dpkg-deb -f "$DEB_PATH" Package)"
APT_OPTS="-o Dir=$ROOTFS -o Dir::State::status=$ROOTFS/var/lib/dpkg/status -o APT::Sandbox::User=root"
apt-get $APT_OPTS update >/dev/null
apt-get $APT_OPTS install -s -y "$DEB_PATH" 2>/dev/null | awk '/^Inst / {print $2}' | grep -vFx "$OWN_NAME" || true
