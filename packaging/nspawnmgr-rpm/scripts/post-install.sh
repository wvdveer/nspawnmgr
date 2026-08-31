#!/bin/sh
# RPM %post scriptlet - the RPM/Fedora equivalent of ../nspawnmgr-deb/debian/postinst and
# ../nspawnmgr-arch/nspawnmgr.install's _setup(). See those files' own comments for the full
# rationale behind each step; this translates the same sequence into RPM's scriptlet shape rather
# than re-deriving it. Runs for both a fresh install and an upgrade (RPM distinguishes the two via
# $1 - 1 for install, 2+ for upgrade - but every step below is already idempotent/always-overwrite
# by design, same as the .deb/Arch versions, so there's no need to branch on it).
set -e

/usr/lib/nspawnmgr/setup-sudo-account.sh

# Shared bridge (nspawnbr0) - always overwritten, holds no admin customization, same reasoning as
# ../nspawnmgr-deb/debian/postinst's own copy of these two files.
cp /usr/share/nspawnmgr/70-nspawnmgr-bridge.netdev /etc/systemd/network/70-nspawnmgr-bridge.netdev
cp /usr/share/nspawnmgr/70-nspawnmgr-bridge.network /etc/systemd/network/70-nspawnmgr-bridge.network
# enable, not just restart - confirmed live on this exact Fedora 43 host: a plain restart only
# affects the current boot, so nspawnbr0 (and every container's networking with it) never came
# back after a real reboot until enable --now was run by hand. See
# ../nspawnmgr-deb/debian/postinst's own copy of this same fix.
systemctl enable --now systemd-networkd \
    || echo "nspawnmgr: systemd-networkd enable/start failed - check 'systemctl status systemd-networkd'." >&2
# `enable --now` on a systemd-networkd that's already running (e.g. any reinstall) is a no-op for
# the "now" part - it does NOT notice the 70-nspawnmgr-bridge.netdev/.network files just copied
# above, so nspawnbr0 silently never gets created. `networkctl reload` forces networkd to re-read
# every .netdev/.network file without restarting the daemon or disrupting any link it's already
# managing - safe to call unconditionally here regardless of whether the enable/start above was a
# fresh start or a no-op against an already-running daemon. Confirmed live (SteamOS, same
# underlying systemd-networkd mechanism): without this, a fresh install on a host where networkd
# was already active left nspawnbr0 never created at all.
networkctl reload \
    || echo "nspawnmgr: networkctl reload failed - nspawnbr0 may not pick up its config; check 'systemctl status systemd-networkd'." >&2

bridge_ready=0
i=1
while [ "$i" -le 20 ]; do
    if /usr/sbin/ip -4 -o addr show nspawnbr0 2>/dev/null | grep -q '10\.100\.0\.1/24'; then
        bridge_ready=1
        break
    fi
    sleep 0.5
    i=$((i + 1))
done
[ "$bridge_ready" -eq 1 ] \
    || echo "nspawnmgr: nspawnbr0 didn't come up with 10.100.0.1/24 within 10s - the self-hosted machine bootstrap below may fail; check 'systemctl status systemd-networkd'." >&2

# Fedora ships firewalld active by default (confirmed on SteamOS, which is Arch-based but shares
# this quirk with Fedora/RHEL - see packaging/nspawnmgr-steamos/nspawnmgr.install's own comment
# for the live-confirmed failure mode: firewalld's default zone policy silently blocks
# nspawnbr0's own DHCP server, containers self-assign a 169.254.x.x link-local address and are
# completely unreachable). UNVERIFIED specifically for plain Fedora Server/Workstation (only
# confirmed live on SteamOS so far) - applying the same fix defensively here regardless, since
# it's a harmless no-op if firewalld isn't active/installed (same "only if actually present and
# running" tolerance the .deb's own ufw carve-out uses). Same "assign the whole bridge to
# trusted" reasoning as SteamOS's fix - libvirt's own virbr0 is conventionally handled this way
# too, and nspawnmgr needs much more than DHCP to flow freely on this bridge anyway.
if command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
    firewall-cmd --permanent --zone=trusted --change-interface=nspawnbr0 \
        || echo "nspawnmgr: failed to add nspawnbr0 to firewalld's trusted zone - containers may never get a DHCP lease; check 'sudo firewall-cmd --list-all --zone=trusted'." >&2
    firewall-cmd --reload \
        || echo "nspawnmgr: firewalld reload failed after the zone change - the interface assignment may not have taken effect yet." >&2
fi

# dnsmasq - same "always overwrite the fixed config, seed dns-hosts/upstream only if missing"
# reasoning as the .deb. mkdir + conf-dir-enable applied defensively, same as
# ../nspawnmgr-arch/nspawnmgr.install's own fix for this - UNVERIFIED whether Fedora's dnsmasq
# package needs this the way Arch's does (Debian's own package enables conf-dir by default; never
# confirmed live for Fedora specifically), but harmless either way: mkdir -p is a no-op if the
# directory already exists, and the grep guard means the conf-dir line is only appended if no
# active one is already present.
mkdir -p /etc/dnsmasq.d
grep -qE '^conf-dir=/etc/dnsmasq\.d' /etc/dnsmasq.conf \
    || echo 'conf-dir=/etc/dnsmasq.d,*.conf' >> /etc/dnsmasq.conf
cp /usr/share/nspawnmgr/dnsmasq-nspawnmgr.conf /etc/dnsmasq.d/nspawnmgr.conf
mkdir -p /etc/nspawnmgr
[ -f /etc/nspawnmgr/dns-hosts ] || touch /etc/nspawnmgr/dns-hosts
[ -f /etc/dnsmasq.d/nspawnmgr-upstream.conf ] || printf 'server=1.1.1.1\nserver=9.9.9.9\n' > /etc/dnsmasq.d/nspawnmgr-upstream.conf
systemctl restart dnsmasq \
    || echo "nspawnmgr: dnsmasq restart failed - check 'systemctl status dnsmasq'." >&2

# SELinux policy fix - confirmed live (real Fedora 43 host, Enforcing mode): without this,
# systemd-machined can't watch a container's own cgroup.events file (a real AVC denial,
# `avc: denied { watch } ... scontext=...systemd_machined_t tcontext=...cgroup_t ...`), and every
# single container start fails outright with "Failed to register machine: Access denied" - not
# specific to nspawnmgr's own containers, this breaks systemd-nspawn/machinectl generally on a
# stock Enforcing Fedora host. Compiled from source (not shipped as a precompiled .pp) so it
# matches whatever policy version is actually running here, rather than baking in one specific
# version at build time. `getenforce` itself only exists when SELinux is present at all; the
# `checkmodule`/`semodule_package`/`semodule` guard covers hosts missing any single piece of the
# policy toolchain (all three are part of Fedora's own base image in practice, confirmed live, but
# failing closed here would be worse than a skipped optimization) - no-op everywhere else
# (Debian/Arch never reach this script at all).
if command -v getenforce >/dev/null 2>&1 && [ "$(getenforce)" != "Disabled" ] \
        && command -v checkmodule >/dev/null 2>&1 && command -v semodule_package >/dev/null 2>&1 \
        && command -v semodule >/dev/null 2>&1; then
    SELINUX_WORKDIR="$(mktemp -d)"
    checkmodule -M -m -o "$SELINUX_WORKDIR/nspawnmgr_machined_cgroup.mod" \
        /usr/share/nspawnmgr/selinux/nspawnmgr_machined_cgroup.te \
        && semodule_package -o "$SELINUX_WORKDIR/nspawnmgr_machined_cgroup.pp" \
            -m "$SELINUX_WORKDIR/nspawnmgr_machined_cgroup.mod" \
        && semodule -i "$SELINUX_WORKDIR/nspawnmgr_machined_cgroup.pp" \
        || echo "nspawnmgr: failed to compile/load the nspawnmgr_machined_cgroup SELinux policy module - containers may fail to start with 'Failed to register machine: Access denied'; check 'sudo semodule -l | grep nspawnmgr'." >&2
    rm -rf "$SELINUX_WORKDIR"
fi

# Self-hosted app machine - bakes/clones debian-minimal and installs a JRE/Tomcat/the WARs/guacd
# into it, same as the .deb/Arch. Deliberately stays Debian-minimal regardless of this host's own
# distro (see docs/administrator-guide.md) - idempotent, safe to call on every install/upgrade.
/usr/lib/nspawnmgr/privileged/nspawnmgr-bootstrap-app-machine.sh
