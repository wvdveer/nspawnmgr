# nspawnmgr Administrator's Guide

This guide walks through setting up a real, production deployment of nspawnmgr from scratch:
the Linux host and `systemd-nspawn`, the database, Tomcat, Apache Guacamole, the `auth` login
app, and nspawnmgr itself. It assumes a single Debian/Ubuntu-family Linux host running
everything, which is the arrangement the project itself is built and tested against; adapt
paths/package names if you're using a different distribution.

For the local development loop (fakes, no real containers, no real Guacamole), see
`site/env/README.md` and `dev_env/README.md` instead — this guide is about a real deployment.

## 1. Architecture overview

**nspawnmgr runs from one of its own systemd-nspawn machines** — a self-hosted Debian container
named `nspawnmgr`, created automatically by the `.deb`'s `postinst`
(`nspawnmgr-bootstrap-app-machine.sh`) before any admin ever touches the app. Only a small,
fixed set of things stay on the bare host:

| Stays on the host | Why |
|---|---|
| `nspawnmgr_exec` (the sudo-capable SSH account, [§3](#3-the-sudo-capable-ssh-account)) | Container creation/management needs real root on the bare host — this is the only account with it |
| Templates and packages (`/var/lib/nspawnmgr/templates`, the admin package cache) | Shared, host-side storage every container (including nspawnmgr's own) is built from |
| `nspawnbr0` (the shared bridge) and dnsmasq | Networking every container, self-hosted ones included, attaches to |

Everything else — Tomcat, all four WARs (`nspawnmgr.war`, `auth.war`, `guacamole.war`, `ROOT.war`),
and `guacd` — runs **inside** the `nspawnmgr` machine, all in one Tomcat 9 instance there, each at
its own context path (`/nspawnmgr`, `/auth`, `/guacamole`, and `/` for `ROOT.war`) exactly as
before — only *where* that Tomcat instance runs has changed, not how the four WARs are laid out
relative to each other. See the comment at the top of the root `pom.xml` for why nspawnmgr itself
is pinned to Boot 2.7/Tomcat 9 (to match Guacamole's own webapp, which can't run on Jakarta
EE/Tomcat 10+ unmodified) and the comment atop `auth/pom.xml` for the same reasoning applied to
`auth`.

Since the `nspawnmgr` machine gets no host-network access (only an ordinary veth into `nspawnbr0`,
like every other container), `postinst` also picks a free host port (8080, or the next free one —
it prints which) and forwards it straight into that machine's own `:8080` via a `Port=` line in
its `.nspawn` file, the same mechanism [custom port mappings](#custom-port-mappings-and-outbound-access)
use for ordinary containers. Browsing to `http://<this host>:<that port>/` therefore still reaches
nspawnmgr exactly as it always did — the self-hosting is invisible from the browser's side.

`auth.war`'s PAM backend (the default — see [§8](#8-auth-login-backend)) authenticates against
whatever host its own JVM's local OS accounts live on. Since `auth.war` now runs inside the
`nspawnmgr` machine, that means its own accounts — created during the [first-boot setup
wizard](#first-boot-setup-wizard), not the bare host's — with no backend code or configuration
needed to make that true.

The database is self-hosted too: the first-boot setup wizard provisions its own Debian database
machine (see [§4](#4-database)) rather than connecting to an existing server. Both the `nspawnmgr`
machine and its database machine show up as ordinary, visible containers in nspawnmgr's own
container list as soon as the first-boot wizard finishes — see [§4](#4-database)'s note on this.
Both are
also set to [auto-start when the host itself boots](#starting-automatically-when-the-host-boots),
with `nspawnmgr` set to require its database machine already started — otherwise a host reboot
could start the `nspawnmgr` machine before its database machine is even up, leaving it running with
no reachable database until someone noticed and started the other machine by hand.

nspawnmgr itself never runs `machinectl`/`systemd-run` directly — the account Tomcat runs under
has no sudo, wherever Tomcat itself happens to run. Instead nspawnmgr SSHes into the **separate,
sudo-capable `nspawnmgr_exec` account on the bare host** and runs privileged commands as root
there — routine operations (starting/stopping/deleting a container, firewall sync) without ever
needing a password, and only the riskier, creation-time-only ones (which run template-authored
content as root inside a fresh container, or provision a whole new machine) requiring one, sourced
either from stored config or a per-request admin approval. On a packaged install this SSH
connection targets `nspawnbr0`'s own fixed address (`10.100.0.1`) rather than `127.0.0.1`, since
nspawnmgr is reaching *out* to the host from inside its own machine rather than talking to itself
— set up automatically by `nspawnmgr-bootstrap-app-machine.sh`, nothing to configure by hand.
Setting up that account is one of the more important, easy-to-miss steps below
([§3](#3-the-sudo-capable-ssh-account)).

## 2. Host prerequisites

On the Linux host that will run containers:

```bash
sudo apt update
sudo apt install -y systemd-container openssh-server
```

`systemd-container` provides `machinectl`, `systemd-nspawn`, and `systemd-run` — including
`machinectl import-tar`, which nspawnmgr uses to clone a container template into a new machine
(talks to `systemd-importd`, socket-activated the same way `systemd-machined` is for
`machinectl start`, so it should just work without any separate setup). Confirm the basics work:

```bash
machinectl list-images   # should run without error, even with an empty list
```

nspawnmgr expects two directories to exist and be writable by the sudo-capable account
(created automatically by `systemd-nspawn`/`machinectl` the first time they're used, but
worth confirming):

- `/var/lib/machines` — where container root filesystems live (`NSPAWN_MACHINES_DIR`)
- `/etc/systemd/nspawn` — where per-container `.nspawn` settings files live
  (`NSPAWN_SETTINGS_DIR`)

These are **real, fixed system paths** — `machinectl`/`systemd-nspawn` never look anywhere
else, regardless of what nspawnmgr's own config says. Don't try to sandbox them.

### Databases (two, separate — one each for nspawnmgr and Guacamole)

Plan for **two independent databases**, both on the same MySQL/MariaDB or PostgreSQL server:
nspawnmgr's own users/containers/settings/templates schema, and Guacamole's own
users/connections/permissions schema (managed separately by Guacamole's `guacamole-auth-jdbc`
extension). **MySQL/MariaDB or PostgreSQL only — no H2 option.** See [§4](#4-database) — the
first-boot setup wizard creates both databases for you, with opinionated fixed names
(`nspawnmgr`/`guacamole`), so there's nothing to prepare by hand ahead of time.

### Container templates (base root filesystems)

nspawnmgr provisions new containers by cloning a "template" into `/var/lib/machines` via
`machinectl import-tar`. Templates themselves live under `TEMPLATES_DIR` (default
`/var/lib/nspawnmgr/templates`), one subdirectory per backend — `nspawn/`, `podman/`, and `qemu/`
(see ["Podman: pods"](#podman-pods) and ["QEMU: virtual machines"](#qemu-virtual-machines) below
for the other two backends' own template formats and how each is populated — this section is
about nspawn's `<name>.tar.gz` files specifically: plain gzipped tars of a root filesystem, exactly
what `machinectl import-tar` itself consumes). You need to prepare at
least one real, bootable one yourself — nspawnmgr does not download or build these for you, with
one exception: `/admin/templates` offers three independent **"Set up X-minimal"** buttons —
**debian-minimal** (APT), **fedora-minimal** (DNF), **arch-minimal** (PACMAN) — each shown only
while that specific flavor's template doesn't already exist yet (setting one up doesn't hide the
others; set up any or all three). Each downloads a real minirootfs (checksum-verified) from
images.linuxcontainers.org, installs and enables an SSH server into it, packs it as
`TEMPLATES_DIR/nspawn/<flavor>-minimal.tar.gz`, and registers it with its "SSH preinstalled" flag
set — a real, working template in one click. That flag (also settable on any hand-created template,
see its edit form) tells container creation the image already has SSH installed and enabled,
skipping the otherwise-redundant download/install/enable step every other template needs. It's not a
general template-management tool: there's no equivalent button for a custom name,
and each button disappears once its own specific flavor's template exists (regardless of what other
templates exist). Same sudo requirement as everything else creation-time-only (§3) — in
admin-approval mode you'll be prompted for the sudo password inline. See
`packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-create-{debian,fedora,arch}-template.sh` for
exactly what each does — **only the Debian one has been confirmed against a real container**; see
["Fedora and Arch templates: verification status"](#fedora-and-arch-templates-verification-status) below for the other two's
verification status, and for the dual-path (host-native vs. chroot) approach all three scripts now
share. The repo's own
`site/templates/nspawn/{debian-minimal,fedora-minimal,arch-minimal,alpine-minimal}` are a
*different* thing — tiny placeholder directories (not even tarballs) used only for local dev-mode
testing (see `site/templates/README.md`) — **do not use them as real templates**, they aren't
bootable.

Deliberately no Alpine flavor among the three: Alpine's official minirootfs has no systemd/D-Bus at
all (it uses OpenRC), and every in-container command nspawnmgr runs goes through
`systemd-run --machine=`, which requires the container itself to be running systemd — an
Alpine-based container fails "Failed to connect to bus" permanently, not as a transient boot race
worth retrying past. Real Alpine support would need systemd installed and working as PID 1 inside
the container first, which is nonstandard on Alpine and untested here.

#### Fedora and Arch templates: verification status

**debian-minimal is the only one of the three "Set up X-minimal" buttons confirmed against a real
container** — it's been created and booted live multiple times over the course of this project.
**fedora-minimal** and **arch-minimal** remain unverified specifically: real Fedora/Arch hosts do
exist and have been used extensively elsewhere in this project (see the RPM/Arch package
installation sections above), but `nspawnmgr-create-fedora-template.sh`/
`nspawnmgr-create-arch-template.sh` — the scripts these two specific admin-UI buttons call — have
never actually been exercised against a real systemd-nspawn container. If you try either, please
report back what breaks — some specific known risk areas, roughly in order of how likely they are
to bite:

- **All three bake scripts (Debian, Fedora, Arch) detect the HOST's own distro and pick one of two
  install paths accordingly**, rather than assuming any one distro. Each script checks
  `command -v apt-get`/`dnf`/`pacman` for its OWN target package manager: if the host has a
  matching one, it runs that tool as a normal **host-side process** pointed at the extracted rootfs
  (apt's `-o Dir=`/`-o DPkg::Options::=--root=` combo, `dnf --installroot=`, `pacman --root=`). If
  the host has no matching package manager at all (e.g. nspawnmgr deployed on a Debian host baking a
  Fedora or Arch template, or vice versa), the script instead **`chroot`s into the freshly extracted
  rootfs and uses the image's own bundled copy of the tool** — `/etc/resolv.conf` copied in (chroot
  doesn't share host network config), `/dev`/`/proc`/`/sys`/`/run` bind-mounted before the chroot'd
  install runs (the `/run` bind mount specifically makes `systemd-resolved`'s NSS module reachable
  for DNS resolution inside the chroot — without it, name resolution can fail even with a correct
  `/etc/resolv.conf` in place), unmounted again immediately after, before the tarball gets packed —
  the same technique `pacstrap`/`arch-chroot`/`debootstrap`'s own chroot stage use. Only the Debian
  script's host-side branch (Debian-on-Debian) has actually been exercised against a real container;
  the Debian script's chroot fallback, and both branches of the Fedora/Arch scripts, are built to
  spec but unverified — these specific bake-a-container-template scripts have never been run for
  real, even though real Fedora/Arch hosts exist and are used elsewhere in this project.
- **arch-minimal is the most speculative of the three.** Known risk areas: (1) the downloaded
  image's `/etc/pacman.d/mirrorlist` ships with every mirror commented out by Arch's own
  convention — the script writes in `geo.mirror.pkgbuild.com` (Arch's official GeoIP redirector)
  explicitly; (2) package signature verification needs a populated keyring that this script doesn't
  set up (real `pacstrap` does, via `pacman-key --init`/`--populate`) — rather than attempt that
  blind with no way to test it, the script disables signature checking (`SigLevel = Never` in the
  target's `pacman.conf`) for this bootstrap install, a real security trade-off worth knowing about
  even though it's a reasonable one for a quick-start dev/test template; (3) the chroot branch also
  disables `CheckSpace` in `pacman.conf` — pacman's disk-space check resolves the cache directory to
  a mount point via `/proc/self/mountinfo`, which inside a chroot still reflects the host's own
  absolute paths rather than the chroot's remapped `/`, so the check fails with a misleading "not
  enough free disk space" regardless of actual space available (a known pacman-in-chroot
  limitation); (4) `pacman.conf` also gets `DisableSandbox` added — pacman's own Landlock-based
  download sandboxing (plus a dedicated unprivileged `alpm` user it switches to) gets blocked by
  `systemd-nspawn`'s default seccomp filter once a container actually boots and runs `pacman`
  live (as opposed to this script's own host-side `chroot`, which has no seccomp restrictions at
  all) — every `pacman` invocation inside a real, running container needs this to work at all, not
  just this script's own bake step.
- **RDP is unavailable for `arch-minimal` entirely.** Confirmed live: `xrdp`/`xorgxrdp` have been
  dropped from Arch's official repos (`pacman -Ss xrdp` finds neither, on a freshly-synced, fully
  populated mirror — not a stale-cache or wrong-mirror issue) and this app has no AUR support to
  fall back on. `arch-minimal` sets its own RDP state to "not capable" by default (see the
  Templates admin page's "RDP" selector), which is what actually disables the "Enable RDP" option on the
  New Nspawn form for it — flip it back on by hand only if a future Arch release restores the
  package, or the template's own install command is hand-edited to something that works (e.g.
  KDE's own `krdp`, still in `extra`, but tied to KDE/Plasma specifically).
- **Every Fedora container needs its `sshd` account-phase PAM check bypassed to be reachable over
  SSH at all.** Every SSH pubkey login attempt into a real, booted Fedora container (confirmed on
  both 43 and 44 — not release-specific) is rejected with `Access denied for user <account> by PAM
  account configuration [preauth]` (`pam_unix`'s account phase, `pam_acct_mgmt`, returns
  `PAM_AUTHINFO_UNAVAIL`) — the account, its password, and its `authorized_keys` are all genuinely
  correct; `unix_chkpwd` itself (the setuid helper `pam_unix` shells out to, to safely read
  `/etc/shadow`) refuses to run with "This binary is not designed for running in this way" — some
  caller-legitimacy check in Fedora's current `shadow-utils` that doesn't tolerate running inside a
  `systemd-nspawn` container. `UsePAM no` in `sshd_config` does **not** work around this — confirmed
  live, sshd's own privileged monitor process still calls `do_pam_account` regardless on this build
  (sshd itself warns `'UsePAM no' is not supported in this build`). The fix that does work: the
  script points `sshd`'s own account phase at `pam_permit.so` (always succeeds) instead of
  `password-auth`'s `pam_unix.so`, in `/etc/pam.d/sshd` only — not a system-wide PAM change. This
  removes PAM's *account*-phase checks (expiration, `nologin`, etc.) for SSH specifically; the real
  identity check (pubkey verification) already succeeds independently before this phase ever runs,
  so this is a narrow, deliberate trade-off for these throwaway provisioned admin accounts.
  Confirmed working live on Fedora 43; the release stays pinned at 43 (not the newer 44) simply
  because that's the exact combination verified end-to-end, not because 44 is otherwise worse.
- **Every Fedora and Arch container's SSH prompt was full of literal escape-sequence text** —
  `start=<uuid>;machineid=<uuid>;user=...;hostname=...;bootid=<uuid>;pid=...;type=shell;cwd=...`
  instead of a plain `[user@host ~]$`. Root cause (confirmed live on Fedora; Arch showed the same
  symptom and shares the same root cause, since it isn't a Fedora-specific quirk — just whichever
  distro's systemd happens to be new enough to ship it, both are here): systemd 257+ ships
  `/usr/lib/systemd/profile.d/80-systemd-osc-context.sh` (symlinked into `/etc/profile.d/` by
  `systemd-tmpfiles`), which emits an OSC 3008 "Hierarchical Context Signalling" escape sequence on
  every prompt; Guacamole's own terminal emulator doesn't recognize/strip it, so it prints as
  literal text. The script only skips itself when `$TERM` is unset or `dumb` (see its own header
  comment), and Guacamole's SSH client reports a real `$TERM`, so it always fires. Disabled the
  documented way (the script's own header comment gives this exact procedure) in both bake
  scripts: remove the `/etc/profile.d/` symlink and mask the `tmpfiles.d` snippet that recreates
  it.
- **Installing the Xfce desktop manager on a Fedora container failed outright** —
  `dnf group install -y "Xfce Desktop"` errored with `No match for argument: Xfce Desktop`.
  Confirmed live: unlike GNOME/KDE, "Xfce Desktop" isn't a comps group on current Fedora at all
  (`dnf group list --available` doesn't list it) — Fedora instead ships a plain named package,
  `xfce4`, that pulls in the whole desktop. Switched to a plain `dnf install -y xfce4`, which also
  makes Xfce-on-DNF pre-fetchable first (see "Package installation: downloaded first" above) —
  unlike GNOME/KDE's own comps-group installs, which still can't be pre-fetched and still need the
  container's own network/DNS to work. Along the way, widened that same pre-fetch mechanism from
  APT-only to APT/DNF/PACMAN generally (the underlying download scripts already supported all
  three; only the gate deciding whether to use them was still APT-only) — SSH/RDP/VNC package
  names are now resolved per-package-manager too (e.g. Arch's SSH package is `openssh`, not
  `openssh-server`; its RDP install additionally needs `xorgxrdp`).
- **That pre-fetch widening then broke Fedora/Arch container creation outright** — `Failed to
  download DNF packages [openssh-server] ... dnf: not found`, and the identical failure for PACMAN.
  Confirmed live on both. Root cause: `nspawnmgr-download-packages-dnf.sh`/`-pacman.sh` (and their
  simulate-install siblings, used by the admin Packages upload flow) ran `dnf`/`pacman` directly on
  the *host* (`--installroot=`/`--root=` pointed at the container's rootfs) — works for APT, since
  this project's `.deb` only targets Debian/Ubuntu hosts, which always have `apt-get`, but neither
  `dnf` nor `pacman` is ever on such a host's own `PATH` at all. Unlike template *baking* (which can
  fall back to a host-side `chroot` into a not-yet-booted rootfs), a live, already-running
  container can't be safely chrooted into the same way — the fix instead runs `dnf`/`pacman`
  *inside* the container itself via `systemd-run --machine=`, the same non-interactive in-container
  execution primitive the real install step already uses, download-only so no installed-package
  state changes. Trade-off: DNF/PACMAN pre-fetch loses APT's own cross-container "already-cached
  package is never re-fetched" reuse, since the shared host-side cache directory isn't visible from
  inside a container's own mount namespace — every DNF/PACMAN pre-fetch re-downloads fresh.
- **The in-container fix above still failed on the first live retry** — dnf5 rejects `--destdir` on
  `install` outright (`Unknown argument "--destdir=..." for command "install" ... available for:
  reposync, download, upgrade`); dnf4's `install --downloadonly --destdir=` combo doesn't carry
  over. dnf5's own download-without-installing command is `download`, and by default it fetches
  only the *named* package(s), not their dependencies — `--resolve` is what pulls in the full
  closure too, the actual dnf5 equivalent of what `install --downloadonly` provided. Fixed:
  `dnf download --resolve --destdir=<dir> <packages>`. Same lesson as the `groupinstall`→
  `group install`/EPEL-on-Fedora bugs above: dnf5's CLI surface differs from dnf4's in real,
  non-obvious ways — confirm live rather than assuming dnf4-era syntax carries over.
- Both scripts also translate `uname -m`'s architecture name (`x86_64`/`aarch64`) into
  images.linuxcontainers.org's own convention (`amd64`/`arm64`) before building the URL — missing
  that translation 404s regardless of the release/build being otherwise correct.
- Both scripts reuse the same `net.ipv4.ping_group_range`/DNS-domain systemd-networkd drop-ins the
  Debian script needs — these are about systemd-nspawn's own generated container network config,
  not anything Debian-specific, so they *should* carry over to any systemd-based rootfs, but that's
  an assumption, not a live-confirmed fact, for Fedora/Arch specifically.

The manual "Install package" flow's own DNF dependency pre-fetch (simulate via
`dnf install --assumeno`, fetch via `dnf install --downloadonly`) carries the identical
unverified-until-tested caveat — see "Uploading and installing arbitrary packages" above.

Alternatively, build a Debian template by hand via `debootstrap` (the same rootfs-fetch idea, if
you'd rather not pull from images.linuxcontainers.org, or want a different release/architecture) —
bake into a scratch directory, then pack it into the real `TEMPLATES_DIR` location as a gzipped
tar:

```bash
SCRATCH=/tmp/debian-minimal-bake
sudo debootstrap --arch=amd64 bookworm "$SCRATCH" http://deb.debian.org/debian
# Bake in an SSH server so nspawnmgr's post-create SSH provisioning step can reach the container.
# Point apt at the tree instead of chrooting/booting into it to run apt-get directly: that runs
# apt's network traffic (including DNS resolution) from inside the container's own environment,
# which is unreliable on some hosts. This way apt runs as a normal host process using the host's
# own working network, and only chroots (via dpkg) for the final unpack/configure step.
# apt's official --root= flag is the "correct" way to do this, but isn't reliably supported across
# apt versions/builds - on at least one real host (apt 2.8.3, Linux Mint 22.1) it's rejected
# outright, even as root, even alone with no other options. -o Dir=/-o Dir::State::status= (apt's
# own package resolution) PLUS -o DPkg::Options::=--root= (forces the dpkg *subprocess* apt spawns
# to also chroot there for unpack/configure) reproduces the same behavior more portably. Without
# that DPkg::Options push-through, dpkg validates the downloaded packages against the HOST's own
# dpkg database instead of the target tree's, causing spurious dependency conflicts if the host
# isn't running the same distro/release as the template.
# -o APT::Sandbox::User=root: without this, apt drops privileges to the unprivileged '_apt' user
# for the actual download step, which fails ("couldn't be accessed by user '_apt' ... Permission
# denied") because the freshly-extracted tree's apt/dpkg directories are only writable by root.
APT_OPTS="-o Dir=$SCRATCH -o Dir::State::status=$SCRATCH/var/lib/dpkg/status -o APT::Sandbox::User=root -o DPkg::Options::=--root=$SCRATCH"
sudo env DEBIAN_FRONTEND=noninteractive apt-get $APT_OPTS update
sudo env DEBIAN_FRONTEND=noninteractive apt-get $APT_OPTS install -y openssh-server
sudo chroot "$SCRATCH" systemctl enable ssh
# Pack into TEMPLATES_DIR as a machinectl import-tar-compatible gzipped tar, then discard the
# scratch directory - the tarball is the only thing nspawnmgr (or machinectl) ever reads.
sudo mkdir -p /var/lib/nspawnmgr/templates/nspawn
sudo tar -czf /var/lib/nspawnmgr/templates/nspawn/debian-minimal.tar.gz --numeric-owner -C "$SCRATCH" .
sudo rm -rf "$SCRATCH"
```

Each `.tar.gz` file under `TEMPLATES_DIR/nspawn/` is one selectable template; register/edit the
matching `Template` row at `/admin/templates` (admin-only) — name, source identifier (the bare
filename, no `.tar.gz`, no backend-folder prefix — e.g. `debian-minimal` for
`TEMPLATES_DIR/nspawn/debian-minimal.tar.gz`), backend, package manager, and optional
install-command overrides. Every template has a **backend** (`domain/ContainerBackend.java`:
`SYSTEMD_NSPAWN`, `PODMAN`, or `QEMU`) recorded against it, each with its own
`TEMPLATES_DIR` subdirectory and file format — see the sections below for the Podman and QEMU
ones. A fresh install starts with **zero** templates —
nothing is seeded — so this page (or the "Set up debian-minimal" button below) is genuinely how
you get your first one; the tarball itself under `TEMPLATES_DIR` still has to be prepared
out-of-band as above regardless, the page only manages metadata pointing at it. Deactivating a
template (rather than deleting it) is the normal way to retire one — it disappears from the
container-creation dropdown but existing containers built from it are unaffected; deleting is only
allowed once no container references it. See [§3](#3-the-sudo-capable-ssh-account)'s "Trust
boundary" section for what this page's admin-only gating is actually protecting against.

**Templates can also be created from an existing machine**, not just downloaded fresh: a stopped
container's own detail page has a "Create template from this machine" field (name + optional
description). It packs that machine's current rootfs (`tar -czf`, same convention every bake script
above already produces) into a brand-new, independent template — useful for snapshotting a
container an owner has already customized rather than re-provisioning from scratch. Deliberately
only offered while the machine is **STOPPED**: packing a live rootfs risks an inconsistent archive
as files change mid-tar. Unlike the admin-only "New template"/"Set up X-minimal" page, this is a
container-owner action (`/api/containers/{id}/create-template`, not under `/api/admin/**`) — the
resulting template is otherwise identical, including the same sudo-password requirement, and can
later be used by anyone the same as any other template. Like the "Install package" endpoint, this
only works in stored-secret mode today (always passes a null sudo-password override) — admin
approval mode isn't wired up for this action yet.

The "New template"/"Edit template" form's source-name field suggests the bare stems of every
`.tar.gz` already present under that template's selected backend subdirectory (fetched from
`GET /api/admin/templates/available-source-files?backend=...`, backed by
`nspawnmgr-list-template-files.sh` — a NOPASSWD, read-only wrapper script like
`nspawnmgr-list-machine-images.sh`), so you don't have to remember an exact filename you prepared
out-of-band. It's a browser `<datalist>`, not a hard-restricted dropdown — the field still accepts
free text, since the suggestion list is best-effort (empty if the SSH host is unreachable or the
directory has nothing in it yet) and shouldn't block registering a template's metadata ahead of the
tarball actually landing on disk.

**Breaking change:** template storage changed from a live, extracted directory tree at
`TEMPLATES_DIR/<name>` (cloned via `cp -a`) to a gzipped tar at
`TEMPLATES_DIR/nspawn/<name>.tar.gz` (cloned via `machinectl import-tar`). A `Template` row
created before this change points at a location nspawnmgr no longer recognizes — delete and
re-create it (e.g. re-click "Set up debian-minimal") or manually pack any hand-placed custom
template into the new location/format as shown above.

#### Installing/updating templates from a CI/CD pipeline

For scripted template management (a CI/CD pipeline building and shipping its own templates) rather
than a human clicking through `/admin/templates`, nspawnmgr ships an SSH-invoked CLI instead of a
web API — this app has no machine-to-machine HTTP authentication at all (Basic auth and form login
are both explicitly disabled; the only login path is the session cookie backed by your external
identity service), so a CI-facing HTTP endpoint would mean inventing a new auth mechanism from
scratch. The CLI reuses this project's existing SSH+sudo trust model instead.

This uses a **second, deliberately isolated** sudo-capable account, `nspawnmgr_ci` — separate from
`nspawnmgr_exec` (see the "Trust boundary" section below for why). It doesn't exist until you opt
in:

```bash
sudo /usr/lib/nspawnmgr/setup-ci-template-account.sh --sudoers-src /usr/share/nspawnmgr/nspawnmgr-ci.sudoers
```

This creates the account, locks password login (key-only auth), and prints a freshly generated SSH
**private** key to stdout exactly once — copy it into your CI system's own secret store immediately;
nothing is kept on the host beyond the public half. Re-run with `--rotate-key` to replace it later
(the old key stops working immediately, it doesn't linger as a second valid credential).

From your CI/CD pipeline, install or update a template (upsert, keyed on `--name`) by piping the
tarball over SSH:

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-template.sh \
  --name my-template --package-manager APT --description "Built by CI" \
  < my-template.tar.gz
```

`--name` becomes part of a filesystem path (`TEMPLATES_DIR/nspawn/<name>.tar.gz`) and is validated
accordingly (letters, digits, `-`, `_` only). `--package-manager` is required (`APT`, `DNF`, `APK`,
or `PACMAN`); `--backend`, `--description`, `--install-ssh-command`, `--install-xrdp-command`,
`--rdp-capable`, `--active` are all optional, matching the admin form's own fields and defaults. The
new/updated tarball is only swapped into place after the database row is confirmed, so a failure
partway through never leaves a half-installed template — an update in progress leaves the previous
version serving right up until the new one is fully ready.

#### Installing/updating packages from a CI/CD pipeline

The same `nspawnmgr_ci` account (no separate opt-in step beyond the one above) can also publish
directly into the [admin package cache](#uploading-and-installing-arbitrary-packages), for a CI
pipeline that builds its own `.deb`/`.rpm`/etc. artifacts and wants them available for container
owners to install without a human uploading them by hand:

```bash
ssh -i nspawnmgr_ci_key nspawnmgr_ci@your-host \
  sudo /usr/lib/nspawnmgr/privileged/nspawnmgr-install-package.sh \
  --package-manager APT --filename my-tool_1.2.3_amd64.deb --description "Built by CI" \
  < my-tool_1.2.3_amd64.deb
```

`--package-manager` (`APT`/`DNF`/`APK`/`PACMAN`/`ISO` — see [Removable media](#removable-media-iso-images)
for what `ISO` means here) and `--filename` are required (the latter may not contain `/` or start
with `.`); `--description` is optional. Install-or-update (upsert) is keyed on `--package-manager` +
`--filename` together — re-running with the same two replaces the previous file and updates its
row in place, same crash-safety posture as template installs (the DB write is confirmed before the
old file on disk is replaced). Since `cached_packages` requires a real uploader account
(`uploaded_by_user_id`), the first CI-installed package auto-provisions a dedicated `nspawnmgr-ci`
pseudo-user — shown as the uploader in the admin page and every container's "Install package"
section, exactly like a human admin's own username would be.

### Restarting containers

A running container's detail page has a **Restart** button alongside Stop/Force stop. It runs
`machinectl reboot` — a clean in-place restart of the container's own OS, unlike Stop+Start: the
machine registration and its veth interface are never torn down and recreated, so custom port
mappings, the outbound-access firewall state, and anything else tied to that veth stay valid
without needing a resync. The container goes through the same BOOTING state as a fresh start while
`ContainerReadinessPollingService` waits for SSH (and RDP, if enabled) to come back up.

### Pausing and resuming containers

A running container's detail page has **Pause**/**Resume** buttons alongside Stop/Force stop.
Unlike Stop, nothing is torn down: Pause runs `systemctl freeze` against the container's own
`systemd-nspawn@<name>.service` unit, suspending every process in its cgroup in place via the
kernel's cgroup freezer (systemd 246+); Resume runs `systemctl thaw` to reverse it, picking up
exactly where it left off. `machinectl` itself has no native pause/resume concept — this is the
modern systemd-native equivalent, the same mechanism `systemctl freeze`/`thaw` already provide for
any other unit type.

A container started via `machinectl start` (which is how nspawnmgr always starts them) runs as the
`systemd-nspawn@<name>.service` unit directly, with no separate `machine-<name>.scope` — that
service unit is what Pause/Resume target. freeze/thaw work against any unit with a cgroup, service
units included. The freeze/thaw *behavior* itself (whether the freezer controller is available/
enabled, whether processes genuinely suspend/resume correctly) is still worth confirming empirically
if you rely on this heavily.

### Starting automatically when the host boots

A MANAGED container's detail page (not shown for EXTERNAL hosts, which have no `machinectl` image
of their own to enable) has a **Machine settings** panel with two fields:

- **Start automatically when the host boots** — a checkbox backed by `systemctl is-enabled`/
  `enable`/`disable` on the container's own `systemd-nspawn@<name>.service` unit.
- **Requires this machine already started** — a dropdown of every other MANAGED container's name,
  backed by a systemd unit drop-in at
  `/etc/systemd/system/systemd-nspawn@<name>.service.d/nspawnmgr-requires.conf`
  (`Requires=`/`After=` against the chosen machine's own unit, `systemctl daemon-reload`d after
  every change). Only meaningful alongside auto-start above — it controls boot *ordering* between
  two machines that both come up on their own, not a runtime dependency Stop/Start otherwise
  enforces.

Both fields are **read live from the host on every page load, not stored in nspawnmgr's own
database** — deliberately, since nothing stops an admin from running `systemctl enable`/`disable`
directly on the host outside nspawnmgr, and a cached value could silently drift from what
`systemd` actually has configured. A transient SSH hiccup reading them shows a fallback message on
the page rather than failing it outright; saving a change goes through the same two wrapper
scripts as the read (`nspawnmgr-set-machine-autostart.sh`/`nspawnmgr-set-machine-requires.sh`,
both NOPASSWD — routine, owner-triggered, same tier as Start/Stop).

**The self-hosted `nspawnmgr` machine and its database machine** (see [§1](#1-architecture-overview))
are both set to auto-start this way automatically, with `nspawnmgr` set to require its database
machine — otherwise a host reboot could bring `nspawnmgr` up before its own database is reachable.
This is wired up by `ContainerDiscoveryService.reconcileSelfHostedInfrastructureNow()` (the same
self-hosted-infrastructure reconciliation pass that also links both machines to the
`debian-minimal` template, provisions their managed SSH access, and sets their container-list
description — see [§1](#1-architecture-overview) and ["Discovering machines created outside
nspawnmgr"](#discovering-machines-created-outside-nspawnmgr)), which runs on its own recurring
~30s schedule from the moment nspawnmgr's own Spring app comes up — not gated on any admin action.
A transient failure (logged at WARN, never fatal) simply gets picked up again on the next pass, no
admin action needed; the same reconciliation also still runs as part of a manual **Discover
machines** click.

### Container networking

Every managed container shares one bridge, `nspawnbr0` (`Bridge=nspawnbr0` in the generated
`.nspawn` file — `machinectl start` enslaves each container's own veth into it automatically at
start), rather than each getting an isolated point-to-point veth on its own private subnet.
`nspawnbr0` and its address (`10.100.0.1/24`, fixed and not admin-configurable — an internal
convention, not a real customization point) are created unconditionally by the `.deb`'s own
postinst (`/etc/systemd/network/70-nspawnmgr-bridge.netdev`/`.network`), not something you set up
by hand. **Network diagnostics** has a read-only check confirming it's actually up.

**SSH/RDP/VNC need no inbound forward at all.** Guacamole's `guacd` and nspawnmgr's own readiness
polling both dial a MANAGED container's internal veth address (its `host0` interface, resolved
live via `machinectl`/`nsenter` — see `nspawnmgr-get-internal-address.sh`) directly, on the
container's real sshd/xrdp/VNC port (22/3389/5900). There's no host port forward in the loop at all
for these, which sidesteps a same-host hairpin-NAT limitation confirmed on real hardware: traffic
from the host itself back through its own DNAT'd/forwarded address to a container frequently isn't
re-NATed correctly, even though a genuinely external client reaching that same address+port works
fine. The container's assigned internal address is logged (at INFO) the moment it reaches RUNNING,
and re-synced to Guacamole's connection config on every subsequent restart in case the address
changes.

### Graphical access: RDP, VNC, and desktop managers

The "New Nspawn" form has two independent checkboxes, **Enable RDP** and **Enable VNC** — either,
both, or neither. Choosing either reveals a **Desktop manager** dropdown (None/GNOME/KDE
(`kde-standard`)/Xfce (`xfce4`)): a graphical protocol is of limited use without an actual desktop
environment inside a minimal template, so picking one installs it during provisioning, shared
between RDP and VNC if both are chosen. **None** means nothing extra gets installed.

Unlike the prompt-credentials access covered below, RDP/VNC chosen at creation time get a real
generated account/password nspawnmgr creates and stores (RDP reuses the SSH account with a login
password set via `chpasswd`; VNC reuses the same account but only sets a VNC-specific password via
`vncpasswd` — it needs no Linux login password of its own). The exact `vncserver`/`xstartup`/
package-install sequence has only been exercised against the one real `debian-minimal` (APT)
template in active use — worth confirming again after installing a `.deb` that includes this.

### Podman: pods

Alongside nspawn containers, the "+" menu's **New Pod** creates a real `podman`-run container
(badge `PODMAN` on the Machines grid, alongside `NSPAWN`/`QEMU`/`HOST`) — same ownership/sharing
rules, same card grid, same detail-page relationship as everything else here. It's available to
any logged-in user, not admin-gated; the link is only disabled while no podman-backend templates
exist yet, same posture as New Nspawn.

**Creation** (`/containers/new-pod`): Name, Template (a dropdown of podman-backend templates
only), Description, and an optional Command — like a Dockerfile `CMD` override; leaving it blank
trusts the image's own baked-in command. A bare interactive shell as the command will exit within
moments once nothing is left attached to its stdin, landing the pod STOPPED rather than failed —
worth knowing if a first pod seems to disappear immediately after creation. Provisioning
(`ProvisioningService.provisionPod()`) loads the template's image, creates and starts the
container, grants the owner access, resolves and persists its internal address, and lands it
straight at **RUNNING** — unlike nspawn containers, there's no `BOOTING`/readiness-polling phase,
since `podman create`+`start` are synchronous and a pod gets no auto-provisioned SSH credential to
poll for in the first place.

**Networking**: pods share the same `nspawnbr0` bridge as nspawn containers, but through a
dedicated podman network definition (`/etc/containers/networks/nspawnbr0.json`, written by
`nspawnmgr-configure-podman-network.sh`) using netavark's **host-local IPAM** rather than DHCP —
netavark's own DHCP proxy transmits from the host's network namespace, and the kernel never loops
that traffic back to the bridge's own receive queue, a confirmed dead end rather than an
unexplored option. The address pool is split from nspawn's own DHCP range to avoid collisions:
pods get `10.100.0.192`–`10.100.0.254`, nspawn containers keep `10.100.0.2`–`10.100.0.191`. DNS is
set explicitly at creation (`podman create --dns 10.100.0.1 --dns-search internal ...`) rather than
relying on any DHCP-delivered config a pod never gets — podman's own `aardvark-dns` is disabled on
this network specifically to avoid fighting with nspawnmgr's own dnsmasq, already bound to that
same address (see ["Resolving containers by name"](#resolving-containers-by-name) above).

**Lifecycle** has full parity with nspawn containers — Start/Stop/Restart/Pause/Resume all dispatch
to native podman commands (`start`/`stop`/`kill`/`restart`/`pause`/`unpause`) rather than any
nspawn-specific mechanism. A separate **`ContainerLivenessPollingService`** re-checks every
`RUNNING` pod's real podman status (and every `RUNNING` QEMU VM's real unit status — see below) on
its own ~30s schedule and flips nspawnmgr's own state to `STOPPED` the moment reality disagrees —
needed because a pod can exit entirely on its own (a bad or missing keep-alive command, see the
Command field above) with nothing else in the app ever noticing, since
pods skip the nspawn-only readiness-polling path entirely. `PAUSED` pods aren't polled.

**Access**: SSH/RDP/VNC are **prompt-credentials only**, the same reachability-gated mechanism
Hosts and discovered containers use
([§ above](#remote-access-for-containers-nspawnmgr-didnt-set-up-itself)) — enabled per-protocol
from the pod's own detail page once the guest's own service is actually listening. A pod never gets an
auto-generated credential the way an nspawn container's SSH access does.

**Files** works via `podman mount`, which exposes the container's merged overlay filesystem as an
ordinary host path — the same browse/upload/download code nspawn containers use then runs against
that path directly.

**Scripts** run via `podman exec -i <name> sh -s` (piped stdin, a real exit code back to
nspawnmgr). Abort is a narrower approximation than nspawn's own transient-unit kill: the script
body is prefixed with `echo $$ > <pidfile>`, and Abort sends `kill -9` to that recorded process
group — a real process-group kill, but not a true cgroup-wide one the way nspawn's abort is,
documented in the code as a known, deliberate narrowing rather than a bug.

**Explicitly not offered for a pod** (all present for nspawn containers): no auto-provisioned
SSH/RDP/VNC credential, no desktop-manager install, no custom inbound port mappings, no
outbound-firewall toggle (a pod already has real network access via netavark — there's nothing to
gate), no ISO mount, no `machinectl`-style autostart/requires configuration.

**Templates** live under `TEMPLATES_DIR/podman/<name>.tar` — a `podman save` archive, loaded via
`podman load` at creation time, distinct from nspawn's plain-tar convention. Populate one either by
pulling straight from a registry (`nspawnmgr-podman-pull-template.sh`) or by converting an existing
nspawn template (`nspawnmgr-podman-convert-nspawn-to-podman.sh`, and the reverse,
`nspawnmgr-podman-convert-podman-to-nspawn.sh`, for going the other way). There's currently no
"create template from this pod" convenience the way a stopped nspawn or QEMU machine's own detail
page offers — only fresh pulls or conversions.

No dedicated automated test suite exists for the podman backend (no `*Podman*` test classes) — it's
covered by the general test suite running against fakes, plus manual dev-stack and live click-through
on yoga. The DNS fix and the netavark host-local-IPAM networking decision above are both confirmed
live (see `nspawnmgr-configure-podman-network.sh`'s and `nspawnmgr-podman-create-container.sh`'s
own header comments) — the process-group-kill abort approximation is the main known, deliberate gap.

### QEMU: virtual machines

Alongside nspawn containers and podman pods, the "+" menu's **New QEMU** creates a real QEMU/KVM
virtual machine (badge `QEMU`), on the same Machines grid with the same ownership/sharing rules.
Available to any logged-in user; the link is disabled while QEMU isn't installed on the host (see
the Diagnostics page).

**Creation** (`/containers/new-qemu`): Name; disk source — **Empty disk** (a size in GB) or **From
template** (clone an existing QEMU-backed Template's own disk), mutually exclusive; **Processor
type**; **Number of CPUs**; **Memory (MB)**; **Network card** (NIC device model — `virtio-net-pci`
by default, or `e1000`/`rtl8139`/`pcnet` for guest OSes that need a specific one, e.g. FreeDOS
typically needs `pcnet`); **Pointer device** (`PS/2` by default, or `USB tablet`, which fixes mouse
cursor drift under VNC for GUI guests — but DOS-family guests have no USB driver stack at all and
need PS/2, which is why it stays the default rather than USB tablet); and an optional **Boot ISO**.

`POST /api/containers/qemu` validates that exactly one of the disk-size/template fields is set,
then `ProvisioningService.createPendingQemu()` persists the row and `provisionQemu()` does the
actual work: clone the template's disk or create a fresh empty one, allocate a VNC port, write the
VM's systemd unit, start it, generate and store a VNC password, and create a matching Guacamole VNC
connection — landing at **RUNNING** immediately, the same synchronous-launch reasoning as pods
above (no `BOOTING`/readiness poll). A separate, asynchronous `QemuAddressPollingService` tries to
resolve a guest IP afterward purely for SSH purposes — "not ready yet, possibly for a long time" is
the expected, normal state for a freshly created VM that may not even have a guest OS installed on
its disk yet.

**Disk creation** (`nspawnmgr-qemu-create-disk.sh`) is a plain `qemu-img create -f qcow2 <path>
<size>G` under `/var/lib/nspawnmgr/qemu-disks/`. Same PASSWORD-tier sudo as any other new
persistent artifact ([§3](#3-the-sudo-capable-ssh-account)) — actually starting the VM afterward is
a separate NOPASSWD step.

**The VM's systemd unit** (`nspawnmgr-qemu-write-unit.sh`) is a real, persistent unit at
`/etc/systemd/system/nspawnmgr-qemu-<name>.service` — rewritten, not just written once, both at
creation and again whenever the mounted ISO changes while the VM is stopped (see below). It's
persistent rather than a transient `systemd-run` invocation because a plain `systemctl start/stop`
against it (which is how nspawnmgr always drives a QEMU VM's lifecycle) takes just a bare machine
name, with nothing VM-specific to reconstruct an invocation from. Its `ExecStart` line covers: the
memory/CPU-model/CPU-count/`-enable-kvm` flags (KVM auto-detected via `/dev/kvm`'s existence); the
qcow2 disk as a virtio drive; the network card on `nspawnbr0` with a MAC address deterministically
derived from the VM's name (`52:54:00:` + the first 3 bytes of an md5 hash of the name — the
address-resolution script has to derive the identical value independently, since neither script
persists it); the pointer-device flags (empty for PS/2, `-usb -device usb-tablet` for USB tablet);
the VNC listener; a Unix-socket QEMU monitor; and the boot order (`-cdrom ... -boot order=d` when an
ISO is mounted, `-boot order=c` otherwise). Falls back to `/usr/libexec/qemu-kvm` when
`qemu-system-x86_64` isn't on `PATH` (a Fedora/RHEL packaging quirk, same fallback
`nspawnmgr-diag-check-qemu.sh` already uses).

**VNC access**: the port is allocated from an admin-configurable range
([`/admin/settings`](#live-editable-settings-adminsettings), validated to start at `5900` or
above — QEMU's own `-vnc host:display` syntax addresses a display number, and `display = port -
5900`), picking the lowest free port not already claimed by another VM. The listener always binds
`nspawnbr0`'s own gateway address (`10.100.0.1`) — unlike nspawn/podman, where Guacamole dials a
container's own internal address directly, every QEMU VM's hypervisor console shares one address
and is differentiated purely by port. A Guacamole VNC connection with a generated password is
created automatically at provisioning time — nothing for the owner to enable, it's just there.
QEMU itself doesn't persist that password across a restart, so `ContainerLifecycleService`
re-applies the stored credential over the HMP monitor (see below) on every start/restart.

**The HMP monitor** is internal-only — there's no UI for sending arbitrary monitor commands.
`nspawnmgr-qemu-monitor-exec.sh` relays one HMP line at a time over SSH to the VM's monitor Unix
socket via `socat` (closing the connection 2 seconds after QEMU stops responding, since HMP's
plain-text REPL has no clean per-response framing to detect completion by — a starting point,
documented as not yet verified against a real `qemu-system-x86_64` monitor). It backs: graceful
Stop (`system_powerdown`, an ACPI request — a no-op if no guest OS is installed yet, by design, not
a bug); Pause/Resume (`stop`/`cont` — QEMU's own equivalent, not the cgroup freezer nspawn
containers use); re-applying the VNC password above; and live ISO swap (`change ide1-cd0`/`eject
ide1-cd0`).

**Files access isn't available for a QEMU VM** — unlike podman's `podman mount`, there's no
host-side directory to browse for a VM whose storage is a single qcow2 disk file, and real
guest-side access (SFTP over the VM's own SSH connection, once enabled) hasn't been built yet. The
FILES pill is disabled on a QEMU VM's card for this reason; planned for a future release.

**ISO mounting** reuses the same `PackageManager.ISO` package cache as nspawn containers
([§ above](#removable-media-iso-images)). Unlike nspawn's static bind-mount (which only takes
effect on the VM's next start), QEMU can **live-swap** the mounted disc through the HMP monitor
while the VM is currently running, and separately persists the same choice into the unit file (via
the same `nspawnmgr-qemu-write-unit.sh` rewrite mentioned above) so it's also correct the next time
the VM cold-starts.

**Templates**: cloning a VM's disk from an existing QEMU-backed Template (`TEMPLATES_DIR/qemu/
<name>.qcow2`) is fully supported alongside the empty-disk-plus-ISO path described above — pick
**From template** on the New QEMU form. A stopped VM's own detail page also has a "Create template
from this machine" field, the same convention as nspawn containers use, to snapshot a VM's current
disk into a brand-new, independent template.

**Lifecycle** has full parity with nspawn/podman through the persistent systemd unit above, plus
the HMP monitor for the operations QEMU itself has to be asked to do gracefully: Start, Force stop,
and Restart are plain `systemctl start/stop/restart` against the VM's own unit; graceful Stop and
Pause/Resume go through HMP as described above rather than `systemctl freeze`/`thaw`.

**Crash reconciliation**: the same `ContainerLivenessPollingService` described above for podman
also covers QEMU — every `RUNNING` VM's own unit is re-checked (`systemctl is-active`) on the same
~30s schedule, and nspawnmgr's own state flips to `STOPPED` the moment the unit itself has stopped
or gone missing out from under it. **Still a real limit, not fully solved**: this only detects the
unit/process itself going away, not a guest-OS-only crash where the process stays alive but
whatever's running inside has hung or died — `systemctl is-active` has no visibility into that, and
neither backend offers a way to ask. Worth keeping in mind if a VM's badge ever seems to disagree
with reality despite the process still technically running.

No dedicated automated test suite exists for the QEMU backend either (no `*Qemu*` test classes) —
covered by the general suite against fakes, plus manual dev-stack and live click-through; the
pointer-device setting specifically has been confirmed live against a real KolibriOS VM on yoga.
The HMP monitor's response-framing heuristic above, and some of `nspawnmgr-diag-check-qemu.sh`'s
own checks, are explicitly marked unverified against a real `qemu-system-x86_64` monitor in their
own header comments.

**Discover machines** ([§ above](#discovering-machines-created-outside-nspawnmgr)) covers all three
backends in one click — it runs a separate pass over `machinectl`, `podman`, and QEMU's own
systemd units each, registering anything untracked it finds in any of them, skipping a backend
outright if it isn't installed on the host at all.

### Package installation: downloaded first, not installed straight from a live network fetch

A package manager run *from inside* a running container has been confirmed unreliable at resolving
its own mirrors, even when the host's own network/DNS works fine. SSH, RDP, VNC, and the
desktop-manager package all get the same treatment: nspawnmgr downloads them (with their full
dependency closure, download-only — nothing gets installed yet) before running the real install
*inside* the container. Applies to **APT, DNF, and PACMAN** templates using the default
(unoverridden) install commands — a custom install-command override can't be safely parsed for
package names to pre-fetch, and falls back to today's in-container-only install (which needs the
container's own network/DNS to actually work). **APK** is excluded entirely: its own local install
already resolves dependencies from configured repos on its own, no pre-fetch needed (moot anyway —
Alpine-based containers don't fully work in this app today, see below).

**APT's own download step runs host-side** — a process pointed directly at the container's own
rootfs directory (`apt-get -o Dir=<rootfs>`), using the host's own working network — since `apt-get`
is always on this host's own `PATH` (this project's `.deb` only targets Debian/Ubuntu). **DNF and
PACMAN can't do that**: neither is ever on this host's own `PATH` at all, so their own download step
instead runs *inside the container itself*, via `systemd-run --machine=` (the same non-interactive
in-container execution primitive the real install step already uses) — download-only, same as APT,
so it still doesn't touch dpkg/rpm/pacman installed-package state. One consequence: DNF/PACMAN don't
get APT's own cross-container "already-cached, still-valid package is never re-fetched" reuse (that
relies on a plain host-side cache directory dnf/pacman running *inside* a container's own mount
namespace can't see) — every DNF/PACMAN pre-fetch re-downloads fresh. All three still cache the
closure under `/var/cache/nspawnmgr/packages/<manager>/auto/` for the admin Packages page's
visibility, regardless of where the download itself ran.

One exception: GNOME/KDE on DNF install via a comps *group* (`dnf group install`), not a plain named
package — `dnf --downloadonly` (what pre-fetch uses) has no equivalent for resolving/caching an
entire group's membership ahead of time, only individual packages, so those two combinations
deliberately skip pre-fetch and fall straight through to the in-container group install (needing the
container's own network/DNS, same as an overridden command would). Xfce doesn't have this problem —
confirmed live, Fedora ships it as a plain named package (`xfce4`), not a comps group at all.

That real in-container install step itself never re-runs `apt-get update`/`dnf`'s own metadata
refresh: it's redundant, since the pre-download step already refreshed the index (host-side for
APT, in-container for DNF/PACMAN) moments earlier, so what the install step reads is already fresh,
and every package it needs is already sitting in the container's own local cache — each pre-fetch
script leaves a copy there for exactly this reason.

The top-level package itself (not its transitive dependencies, which stay a cache-directory
implementation detail) is also registered in the **Packages** admin cache described just below, so
what nspawnmgr fetched for its own provisioning is visible and reusable there too, not just a hidden
side effect of one container's creation.

### Uploading and installing arbitrary packages

Admins can also upload any package file directly: **Packages** (from the containers list, admin
only) accepts a `.deb`/`.rpm`/whatever-your-package-manager-uses file plus an optional description.
Every container owner then sees a matching **Install package** section on their own container's
detail page (only packages for that container's own package manager are offered) — picking one and
clicking Install copies it onto the container, then, for **APT, DNF, and PACMAN** packages, first
*simulates* the install (`apt-get install -s` / `dnf install --assumeno` / `pacman -U --print`, no
changes made) against the container's own state to find any dependency it doesn't already have.
Anything missing is fetched the same way SSH/RDP/VNC/desktop-manager provisioning already does (see
above — host-side for APT, inside the container itself via `systemd-run --machine=` for DNF/PACMAN,
since neither is ever on this host's own `PATH`) and registered here in the package cache too, then
the real install runs via the package manager's own local-file install command
(`apt-get install <path>` / `dnf install <path>` / `pacman -U --noconfirm <path>`) — its own
dependency resolution picks up both the uploaded file and whatever was just pre-fetched in one
coherent pass. DNF/PACMAN's own local install would normally resolve dependencies straight from the
container's own network access, same as either does for any named package - the pre-fetch step runs
anyway, deliberately, for consistency with APT's own "never let a container reach the network
directly for a live package-manager mirror lookup" posture (DNF/PACMAN's own pre-fetch does still
need the container's network for the in-container download itself — it just keeps that need
contained to a single, download-only, non-interactive step instead of the real install command).
This sub-step
needs the same sudo-password tier as container creation, so it fails outright (no silent partial
install) if no stored sudo secret is configured and the request didn't supply one. **DNF and PACMAN
support for installing an uploaded package *into a Fedora/Arch container* is unverified** —
distinct from installing *nspawnmgr itself* on a real RPM/Arch host, which is verified (see the RPM
and Arch package installation sections above); this specific in-container package-upload flow has
never been exercised against a real Fedora/Arch container, only built to each tool's documented
CLI contract as carefully as possible — flag any live discrepancy found. **PACMAN is the more
speculative of the two**: unlike
`apt-get install -s`/`dnf install --assumeno`, which are apt/dnf's own well-documented dry-run modes,
`pacman -U --print`'s behavior for a full local-file dependency-closure simulation has never been
exercised anywhere in this project, not even manually. **APK** packages skip all of this and just
run a single local install (`apk add <path>`) with no dependency resolution — a missing dependency
there is still a visible error in the output, not fixed automatically (APK's own local install
actually would resolve dependencies from configured repos, but Alpine-based containers don't fully
work in this app today regardless - see below). Packages nspawnmgr auto-downloaded (either for its
own SSH/RDP/VNC/desktop-manager provisioning, or as a dependency fetched by this flow) show up here
too, attributed to whichever container's creation or install first fetched them, alongside anything
an admin uploaded by hand.

The Packages page's **"Show transitive dependencies"** button fills the gap this deliberately
leaves: pick a package manager (APT/DNF/PACMAN, the same three with a pre-fetch cache dir at all)
and it lists every file actually sitting in that manager's shared
`/var/cache/nspawnmgr/packages/<manager>/auto` directory, with size in bytes. This is generated
fresh by shelling out and reading the real directory every time the button's clicked
(`nspawnmgr-list-auto-cache.sh`, a NOPASSWD read-only wrapper script) — nothing about it is stored
in the database, unlike the top-level packages in the table above. Useful for confirming a
dependency actually landed on disk, or for eyeballing how much of that shared cache directory a
given package manager has accumulated over time.

### Removable media (ISO images)

**ISO** is a real `PackageManager` value, not a separate cache/entity/admin-page — upload one from
the same **Packages** admin page just like a `.deb`/`.rpm`, picking `ISO` instead of `APT`/`DNF`/
`APK`/`PACMAN`. The `.deb`/`.rpm`-style install machinery doesn't apply to it (there's no install
command for `ISO`, and `Template.packageManager` can never be `ISO` — the Templates admin form's own
dropdown excludes it), but the upload/cache/CI-publish path is identical either way, by deliberate
choice over building a second parallel one. Any container owner can then configure an uploaded ISO
from their own container's detail page's "Removable media" section — at most one per container at a
time, like a real CD drive, always mounted read-only at the fixed `/mnt/cdrom`. Mounting a different
ISO while one's already configured auto-ejects the old one first; there's no separate
eject-then-mount step.

**A persistent, declarative setting — exactly like [custom port
mappings](#custom-port-mappings-and-outbound-access), not a live operation.** Mounting/ejecting
rewrites the container's `.nspawn` file immediately (a static `[Files]` `BindReadOnly=` line), but
only takes effect the next time the container is (re)started, and stays configured across restarts
until explicitly changed or ejected — it does *not* require the container to be running to set, and
a stop/restart does *not* clear it. The host-side half (an ISO file loop-mounted at a fixed
per-container path, `nspawnmgr-mount-iso.sh`/`nspawnmgr-unmount-iso.sh`) is set up/torn down as soon
as you mount/eject, independent of whether the container happens to be running at that moment; a
host reboot, however, doesn't currently re-establish that loop mount on its own, so a container
booted after a host restart with an ISO still configured will fail to start until this is
addressed by hand (`mount -o loop,ro <iso> /var/lib/nspawnmgr/iso-mounts/<name>`) — a known
limitation, not automatically reconciled today.

**This makes `systemd-networkd` a hard prerequisite, not just a nicety for outbound access** —
nspawnmgr's own postinst uses it to create and configure `nspawnbr0` itself (see above), and
nspawnmgr's readiness check and `guacd` both dial a container's `host0` address directly once it
has one, so a container that never gets one (`host0` never enabled inside the template — see step 2
below) never leaves `BOOTING`, full stop, not just slowly. Audit any of your own templates for
`systemctl enable systemd-networkd` if containers stop reaching `RUNNING`.

The only remaining host-level inbound forwarding is [custom port
mappings](#custom-port-mappings-and-outbound-access) — entirely optional, owner-managed, and using
the same `Port=tcp:<host-port>:<container-port>` `.nspawn` mechanism (which `systemd-nspawn` still
sets up as DNAT rules automatically on start).

Concretely, to finish setting this up:

1. `sudo systemctl enable --now systemd-networkd` (**Network diagnostics** has a check + one-click
   fix for this), and `sudo sysctl -w net.ipv4.ip_forward=1` (persist it under `/etc/sysctl.d/`) —
   `IPMasquerade=yes` in `nspawnbr0`'s own `.network` file (see above) adds the NAT rule, but actual
   packet forwarding between interfaces is a separate, kernel-wide setting this package doesn't
   turn on for you. If NetworkManager/ifupdown already manages your main NIC, tell it to leave
   `nspawnbr0` alone (e.g. NetworkManager.conf's
   `unmanaged-devices=interface-name:nspawnbr0`) so networkd stays free to manage it.
2. Inside the container **template**, before baking (the same step as the `openssh-server` bake in
   [§2](#container-templates-base-root-filesystems)): `systemctl enable systemd-networkd` so
   `host0` actually picks up its DHCP config from the bridge — `debootstrap` output doesn't enable
   it by default. **Required**, not optional: skip this and containers from that template never
   leave `BOOTING`.
3. Start (or restart) a container — `machinectl start` enslaves its veth into `nspawnbr0`, it gets
   an address and route via DHCP from the bridge, and nspawnmgr/`guacd` can now reach it directly.

### Resolving containers by name

Managed containers can reach each other over IP already (nothing in nspawnmgr's own firewall setup
blocks container-to-container `FORWARD` traffic — the `NSPAWNMGR-OUTBOUND` chain's DROP rule only
matches a container's *own* outbound packets, regardless of destination). What's missing without
this section is a way to look a peer up by name instead of its internal address, which is
DHCP-assigned per container and can change across restarts.

`dnsmasq` is a real `apt` dependency of this package (unlike guacd/Tomcat, which are bundled — see
[§2](#2-host-prerequisites); `dnsmasq`'s hosts-file-serving behavior is simple and stable enough
across versions that there's no need to pin one). Installed and configured automatically: bound to
`nspawnbr0` only (never reachable from the host's own LAN/uplink interface — it's not, and must
never become, an open resolver), serving whatever's in `/etc/nspawnmgr/dns-hosts`. Every container
also gets `nspawnbr0`'s own address (`10.100.0.1`) as its DNS server automatically, straight from
`nspawnbr0`'s `.network` file — no extra admin step needed. nspawnmgr regenerates `/etc/nspawnmgr/dns-hosts` (`ContainerDnsSyncService`, every
~15s) from every currently-`RUNNING` MANAGED container's own name and internal address — the same
address `guacd`/readiness already resolve (see above), so nothing new needs discovering. dnsmasq
doesn't notice a changed `addn-hosts` file on its own (no automatic/inotify-based reload for it,
only SIGHUP or a restart), so every write is followed by a reload
(`nspawnmgr-reload-dnsmasq.sh`/`DnsReloader`) — without it, containers would keep failing to
resolve each other no matter how current the file on disk actually is.

Since this `dnsmasq` instance runs directly on the host, it also reads and serves the host's own
`/etc/hosts` to containers by default (confirmed as the wanted behavior live) — an admin's own
static LAN entries there (e.g. `192.168.1.15 acer`) become resolvable from inside every container
too, not just from the host itself. The one caveat: if `/etc/hosts` also maps the host's bare
hostname to a loopback address (Debian's own `127.0.1.1 <hostname>` convention) *and* that same
bare name is set as the external-hostname setting below, the two sources collide and dnsmasq may
answer with either address — avoid picking an already-`/etc/hosts`-mapped short name for that
setting.

`/etc/nspawnmgr/dns-hosts` also carries one more, fixed entry: the host's own external hostname
(`nspawnmgr.host.external-hostname`/`HOST_EXTERNAL_HOSTNAME` — detected automatically at install
time by `setup-sudo-account.sh`, live-editable afterward at
[`/admin/settings`](#live-editable-settings-adminsettings)), pointing at `nspawnbr0`'s own fixed
address (`10.100.0.1`). A container has no other route back to the host at all — this is what lets
one resolve the host's own name for reaching anything the host forwards back in (e.g. a [custom
port mapping](#custom-port-mappings-and-outbound-access)). Kept in sync the same way and on the
same schedule as the container entries above; omitted entirely while it's still at its
unconfigured `localhost` default (mapping "localhost" itself to `10.100.0.1` would be actively
wrong, not just unhelpful).

This same `dnsmasq` instance is also every container's *only* DNS server — not just for `.internal`
names — so it also forwards anything outside `.internal` to the configured upstream resolvers,
`nspawnmgr.dns.upstream-servers` (default `1.1.1.1,9.9.9.9`), live-editable at
[`/admin/settings`](#live-editable-settings-adminsettings) — e.g. to point containers at a
corporate DNS server instead. Without some upstream configured, a container's own
`dnf`/`pacman`/`apt` (fetching from their real package mirrors) or anything else needing a real
internet hostname fails outright with "Could not resolve host" — confirmed live. Still not an open
resolver in the sense above: forwarding happens over the host's own normal internet route, and
`dnsmasq` itself is still bound only to `nspawnbr0`, unreachable from outside the container bridge.

The upstream servers live in their own file, `/etc/dnsmasq.d/nspawnmgr-upstream.conf` — separate
from the main `nspawnmgr.conf` above — auto-included alongside it by dnsmasq's own
`conf-dir=/etc/dnsmasq.d/` (Debian's default `/etc/dnsmasq.conf`), no extra directive needed.
`ContainerDnsSyncService` keeps it in sync with the current setting the same way it keeps
`dns-hosts` in sync with running containers (polled every ~15s, only rewritten when the effective
value actually changes). `postinst` seeds it with the same `1.1.1.1`/`9.9.9.9` default on first
install (only if the file doesn't already exist), so upstream resolution works from the very first
boot, before nspawnmgr itself is even up to take over syncing it.

Containers resolve each other by their bare nspawnmgr name (`b1`) or by an FQDN under the fixed
`.internal` suffix (`b1.internal`) — dnsmasq's `domain=`/`expand-hosts` options serve both forms
from the same `dns-hosts` entries automatically, no separate config. `internal` is IANA's
special-use TLD reserved for exactly this (RFC 8375, the same category as `home.arpa`), not a
made-up domain, so it's guaranteed to never collide with a real public one. Scope is MANAGED
containers only (EXTERNAL, admin-configured hosts already have their own `hostname` and aren't
added here), and the namespace is flat across all of them — this is purely network-level
reachability, independent of which containers a given user can see or connect to in the web UI
(the Machines grid only shows machines a user owns or has been shared, except for an admin, who
sees everything regardless of ownership).

Two more pieces are needed for this to work end-to-end:

- **The container side**: `systemd-resolved` refuses to send an unqualified (undotted) name like
  `b2` to a real DNS server at all — only to LLMNR/mDNS — unless the link has a routing/search domain
  configured to qualify it with. DHCP could supply this, but that needs the container's own
  `80-container-host0.network` (generated by `systemd-nspawn` itself, not something this template
  controls) to opt in with `UseDomains=yes`, which it doesn't by default. The template instead ships
  a static drop-in at `/etc/systemd/network/80-container-host0.network.d/nspawnmgr.conf`
  (`[Network]\nDomains=internal`), merged by filename the same way a systemd unit drop-in is —
  sidesteps DHCP entirely and doesn't depend on any option actually being sent.
- **The dnsmasq side**: `domain=`/`expand-hosts` alone only control the suffix dnsmasq *decorates its
  own answers with* — they don't make it authoritative for a query that already *arrives*
  pre-qualified (exactly what a container with the routing domain above now sends). Without also
  setting `local=/internal/`, an incoming `b2.internal` query falls through hosts/`addn-hosts`
  matching entirely and gets forwarded upstream like any other name — `.internal` doesn't exist
  publicly, so that just fails (and would otherwise leak container names to whichever public
  resolver is configured). `local=/internal/` marks `.internal` as dnsmasq's own authoritative
  zone: answer only from its own hosts data, `NXDOMAIN` for anything genuinely unknown there,
  never forward.

If you ever hand-edit either dnsmasq file directly on a running host: `domain=`, `expand-hosts`,
`local=` (in `nspawnmgr.conf`), and `server=` (in `nspawnmgr-upstream.conf`) are all structural —
dnsmasq only parses them at process startup, confirmed live — unlike `addn-hosts`, which
`DnsReloader.reload()`/`nspawnmgr-reload-dnsmasq.sh` hot-reloads correctly via `SIGHUP`. A plain
`systemctl reload dnsmasq` after hand-editing any of the structural ones has no effect; use
`systemctl restart dnsmasq`. `ContainerDnsSyncService` already knows this distinction: an
`addn-hosts` change goes through `DnsReloader.reload()` (SIGHUP) as above, but an upstream-servers
change goes through the separate `DnsReloader.restart()`/`nspawnmgr-restart-dnsmasq.sh` (a full
`systemctl restart`) instead — using `reload()` for that one would leave the file on disk correct
while dnsmasq silently kept answering with whatever it last actually started with. A normal package
install/upgrade doesn't need either: `.deb` postinst always issues its own full `restart` when it
(re)installs `nspawnmgr.conf`.

### Discovering machines created outside nspawnmgr

If a machine was created by hand directly on the host — `machinectl clone`/`debootstrap`/
`import-tar` run yourself, or an image restored from backup — nspawnmgr has no idea it exists until
an admin clicks **Discover machines** on the containers list. That compares every image name
`machinectl` currently knows about against nspawnmgr's own database and registers whatever isn't
already tracked as an ordinary MANAGED container, **owned by whichever admin ran the discovery**.
Running it again is safe — anything already tracked (by name) is skipped.

Discovery registers the machine's existence and lets you start/stop/delete it and see it resolved
by name (see above). It deliberately never installs an SSH/RDP/VNC admin account the way creating a
container through nspawnmgr does — unlike a container nspawnmgr provisioned itself, there's no way
to know what already exists inside a hand-built image, so it never assumes an account name or runs
`useradd`/installs a server for any of the three. What it *does* do: right after registering each
machine, it checks whether SSH (port 22), RDP (port 3389), or VNC (port 5900) is already listening,
and if so, wires up a Guacamole connection for it automatically — in **prompt-credentials** mode,
the same mechanism the Hosts page below uses, so you're asked for a
username/password each time you connect rather than nspawnmgr generating and storing one. If none
of those ports were open yet at discovery time (or you enable one on the box afterward), do it
manually from the container's own detail page instead — see "Remote access" below.

### Remote access for containers nspawnmgr didn't set up itself

A container's detail page has a **Remote access** section for each of SSH, RDP, and VNC whenever
nspawnmgr has no generated credential for that protocol on it — always true for a discovered
container, and also true for an ordinary nspawnmgr-created container if RDP/VNC was declined when
it was created. Clicking **Enable SSH/RDP/VNC access** checks that the port is actually listening
right now and, only if so, wires up a prompt-credentials Guacamole connection exactly like
discovery's own auto-wiring step above; **Disable** removes it again. This check happens once, at
the moment you click Enable — if the service inside the container stops again afterward, the
Connect button stays enabled until the next failed connection attempt, rather than nspawnmgr
continuously re-probing every container in the background.

This section is intentionally never offered for a protocol nspawnmgr already manages with a real
generated credential (every container's SSH, and RDP when requested at creation) — that connection
is left completely alone, so this feature can never silently replace working generated credentials
with a prompt-credentials connection.

### Hosts: admin-managed external machines

A **Host** is an entry for an arbitrary machine on the network that isn't an nspawnmgr-managed
container at all — an existing Windows box, a NAS, another team's server, anything reachable over
SSH/RDP/VNC that's convenient to access through the same Guacamole SSO flow as everything else
here. There's no separate Hosts page: a Host is a `Container` row under the hood (kind
`EXTERNAL`), so it shows up as an ordinary card — a fixed `HOST` badge instead of a backend badge
— right alongside nspawn/podman/QEMU machines on the main **Machines** grid, and its detail page
is the same `/containers/{id}` route every other machine uses. Admins add one from the "+" menu's
**New Host** item (`/admin/hosts/new`, admin-only): a name, a hostname/IP, an owner username (must
belong to a user who has already logged in at least once), and which of SSH/RDP/VNC to offer plus
the port for each. An admin viewing that host's own detail page gets **Edit host** (back to the
same form, at `/admin/hosts/{id}/edit`) and **Delete host** buttons in its Manage panel — there is
no separate hosts list page; the database is the sole source of truth.

**Visibility follows the same owner/admin/shared rule as every other machine** — a Host isn't
public just because it's admin-created; only an admin, its owner, or someone it's been explicitly
shared with sees it in their own Machines grid (`ContainerRepository.findVisibleToUserOrderByName`
applies this uniformly across nspawn, podman, QEMU, and Host rows alike).

**RUNNING/STOPPED is resolved live, not stored.** Since nspawnmgr doesn't control a Host's
lifecycle at all, its state badge comes from a single TCP reachability check
(`HostLivenessService`) against whichever of its configured SSH/RDP/VNC ports is enabled — SSH
first if present, then RDP, then VNC — cached for one minute per host so the Machines grid and the
host's own detail page don't each trigger a fresh probe on every request. A Host with none of the
three enabled has nothing to probe and always shows RUNNING.

Connections always prompt for credentials live — nspawnmgr never stores a password for a host, the
same prompt-credentials mechanism discovery's own auto-wiring and the per-container Remote access
section above both use.

The hostname/IP field can be a real hostname, not just an address — on a self-hosted install,
Guacamole's own SSH/RDP/VNC client runs inside the self-hosted `nspawnmgr` container, whose only DNS
path is nspawnmgr's own dnsmasq (container names plus public upstream resolvers), with no visibility
into a private LAN's own name resolution. To work around that, nspawnmgr re-resolves the hostname
itself on the underlying host (via the same sudo-capable SSH account used for every other privileged
operation) every time someone connects, and hands Guacamole the resolved address directly instead of
the hostname — so a LAN-only name that only your network's own DNS/NetBIOS/mDNS knows about still
works, and a DHCP-reassigned address is picked up automatically on the next connect without needing
an admin to notice and re-save the entry. If the hostname doesn't resolve on the host at connect
time, the connection attempt fails with a clear error rather than proceeding with a stale address.

Sharing works the same way it does for containers: the owner manages who else can connect from the
entry's own detail page. An admin who isn't the owner sees a **Take ownership** button under
Manage there instead — useful for taking over a Host (or any machine) whose owner has since left,
without needing database access.

The SSH/RDP/VNC buttons on both the Machines grid and a Host's own detail page open the Guacamole
session in a new browser tab rather than navigating away — useful when connecting to several
machines from the same page. Opening one from a Host's card uses `/hosts/{name}/session/{protocol}`,
its own URL namespace distinct from an ordinary machine's `/containers/{name}/session/{protocol}` —
a Host is a Container row under the hood as noted above, but the *session* URL a user actually sees
in their browser deliberately doesn't say "containers" for something that isn't one from an admin's
point of view. Both routes render the identical template/JS underneath (an iframe plus a fetch to
the same `/api/containers/{id}/session/{protocol}` API endpoint); only the page URL differs. Both
key off the machine's **name**, not its numeric id — a deliberate choice so the URL in a shared
link or a browser's history stays meaningful.

### Custom port mappings and outbound access

Beyond SSH/RDP above, a container's **owner** can self-service two more things from its detail
page — no admin action needed for either:

- **Custom inbound port mappings**: any additional TCP or UDP host-port → container-port forward,
  with the owner choosing both port numbers exactly. nspawnmgr checks the requested host port isn't
  already bound by another custom mapping before accepting it. A mapping is written into the
  `.nspawn` file immediately but only takes effect the next time the container is (re)started —
  adding one to a running container shows a "restart required" notice rather than restarting it
  automatically.
- **Outbound internet access toggle**: unlike the host-wide, all-or-nothing masquerade setup
  above, each container can individually have its outbound access blocked. nspawnmgr manages this
  itself with a dedicated `NSPAWNMGR-OUTBOUND` iptables chain (created automatically the first
  time it's needed, jumped to from the top of `FORWARD`) holding one `DROP` rule per
  outbound-disabled container, keyed on that container's actual host-side veth interface — which
  nspawnmgr looks up dynamically each time (via the veth's peer ifindex), since, as above, the
  veth name isn't a predictable string derived from the container's name. Toggling this takes
  effect immediately, with no restart needed, for a running container.
- **Outbound allowlist**: while outbound access is disabled, the owner can still punch through
  specific destinations — a literal IPv4 address, port, and protocol (TCP/UDP) — e.g. `127.0.0.1`
  so the container can reach another co-located container/service without granting it general
  internet access. Implemented as ACCEPT rules ahead of the container's DROP rule in the same
  `NSPAWNMGR-OUTBOUND` chain; every change flushes and rebuilds that container's rules from
  scratch rather than patching them in place. Has no effect while outbound access is enabled —
  everything is already reachable in that case. Also takes effect immediately, no restart needed.

Both require the `iptables` command to be available and usable password-less via the sudo-capable
account from [§3](#3-the-sudo-capable-ssh-account) — the same account and mechanism nspawnmgr
already uses to write `.nspawn` files and start/stop containers.

## 3. The sudo-capable SSH account

Create a dedicated local account on the same host, with scoped sudo access, that nspawnmgr will
SSH into (always over loopback, `127.0.0.1`) to actually run `machinectl`/`systemd-run` and touch
root-owned paths. **Recommended:** let `packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh` do
this for you — it's the same script the `.deb`'s `postinst` runs, but it's fully runnable
standalone, without building or installing the package at all:

```bash
sudo packaging/nspawnmgr-deb/scripts/setup-sudo-account.sh
```

Run from a checkout of this repo (no flags needed — it auto-detects the sibling
`privileged-scripts/` and `debian/nspawnmgr.sudoers` next to itself), it creates the
`nspawnmgr_exec` system account, generates and stores a random password for it, generates an SSH
keypair, installs the wrapper scripts referenced below into `/usr/lib/nspawnmgr/privileged/`,
installs and validates the sudoers grant, and adds an sshd `PasswordAuthentication` carve-out for
the account if your host disables it globally. It's idempotent — safe to re-run after an upgrade
or to pick up updated wrapper scripts. See the script's own header comment for the full detail.

If you'd rather set this up entirely by hand instead (e.g. to use a different account name), see
what the script itself does as a reference — but note the two privilege tiers below, since a
blanket `usermod -aG sudo` (any command, always via a password) no longer matches how nspawnmgr
actually calls out to this account.

### Two privilege tiers

sudoers access for this account is split into two tiers, not one:

- **NOPASSWD** — the fixed-shape, always-safe commands: `machinectl start/poweroff/terminate/
  reboot/remove/show`, `systemd-run --machine=... --pipe --quiet --wait /bin/sh -s` (running a stored
  container script — see "Trust boundary: container scripts" below for why this one specific
  `systemd-run` shape is NOPASSWD while the general one below isn't), and the wrapper scripts under
  `/usr/lib/nspawnmgr/privileged/` that handle writing `.nspawn` settings, deleting a container's
  files, and outbound-firewall sync. These are routine, owner-triggered actions (starting a
  container, editing its port mappings, deleting it, running a script they wrote) that must never
  block waiting on an admin, regardless of which container-creation mode below is active.
- **Password-required** (no `NOPASSWD` tag) — `systemd-run --machine=... --pipe --quiet --wait`
  (runs arbitrary template-authored content as root inside a fresh container — see "Trust
  boundary" below), the `nspawnmgr-clone-template.sh` wrapper, and the
  `nspawnmgr-create-debian-template.sh` wrapper (downloads/extracts a real Debian rootfs — see
  §2's "Container templates", the Templates admin page's "Set up debian-minimal" button). All
  three are creation-time-only — the first two called exactly once per container from
  `ProvisioningService`, the third only ever on-demand from an admin when no templates exist yet.
  Which password is used — and whether one is even available without an admin's involvement —
  depends on the mode below.

Every privileged command routes through one of these two fixed-argument wrapper-script or
`machinectl`/`systemd-run` invocations — nspawnmgr never asks sudo to run an arbitrary inline
script, precisely so the sudoers grant above can match on an exact command/path rather than having
to wildcard-match script text (which would be fragile: any future change to the script content
would silently invalidate — or silently over-broaden — the grant).

### Container-creation mode: stored secret vs. admin approval

Whether creating a container is fully self-service or requires an admin's sign-off is **derived**
from whether `nspawnmgr.ssh.password`/`SSH_PASSWORD` is configured — there's no separate toggle:

- **Stored-secret / self-service mode** (password configured, the `.deb`'s default): an owner's
  "create container" request provisions immediately and automatically, same as before this
  feature existed.
- **Admin-approval mode** (password left blank): a new container lands in a `PENDING_APPROVAL`
  state instead of provisioning right away. The **Requests** page (`/requests` — its sidebar nav
  item only appears, to anyone, while this mode is active) lists it alongside any pending
  in-container user-account requests in one combined view. An admin sees and can act on every
  pending item from every user; a non-admin only sees their own and can **Deny** them (moves to a
  terminal `DENIED` state, no SSH ever attempted) but not **Approve** — approving needs a sudo
  password, supplied inline, used only for that one item's creation-time steps, held in memory and
  zeroed once that run completes, never persisted — deliberately only ever asked of an admin.

SSH transport login and the sudo password share the same configured value, so blanking
`SSH_PASSWORD` to select admin-approval mode would otherwise leave the SSH session itself with
nothing to authenticate with — even for the NOPASSWD tier above. **Admin-approval mode therefore
requires `nspawnmgr.ssh.private-key-path`/`SSH_PRIVATE_KEY_PATH` to be set**, so SSH transport
auth uses a key instead of the (now blank) password. `setup-sudo-account.sh` generates this key
unconditionally regardless of mode, so switching modes later really is just blanking/setting one
env var and restarting — nothing else to set up. nspawnmgr fails to start if neither a password
nor a private key is configured at all (`SshPropertiesValidator`), rather than surfacing this as a
confusing connection failure on the first container action.

### Admin/user roles

A user's role (`USER`/`ADMIN`) is needed to gate the approval page above. Two modes, again
selected by whether a config value is set — this time `nspawnmgr.auth.user-is-admin-json`:

- **App-managed** (default, blank): the **first user ever to log in** is automatically promoted
  to `ADMIN`; everyone else defaults to `USER`. From then on, any admin can promote or demote any
  other user at `/admin/users`. Roles are sticky — never silently recomputed on login.
- **External-managed** (`nspawnmgr.auth.user-is-admin-json` set to a JsonPath into the same
  identity JSON `auth.war` already returns, alongside `user-id-json`/`user-username-json` etc.):
  role is recomputed fresh from that JSON on every login instead — promote and demote both — and
  the manual grant/revoke page rejects changes entirely, since the external identity source is
  authoritative in this mode.

### Trust boundary: template-authored provisioning commands

The password-required tier above lets `systemd-run` execute content as root inside a container.
That content always comes from one of: a literal string in `ProvisioningService` itself, or
`Template.installSshCommand`/`installXrdpCommand`. Templates are editable through
`/admin/templates`, gated by the existing ADMIN role on `/admin/**`, not a separate approval
workflow. In other words: **whoever holds ADMIN role effectively controls what runs as root inside
every container created from a template they edit.** In app-managed role mode, any current admin
can grant ADMIN to anyone else at `/admin/users`, self-service, with no additional approval step.
Ordinary (non-admin) logged-in users still cannot reach this at all — only `GET /api/templates`
(active templates, summary only) is exposed outside `/admin/**`.

### Trust boundary: container scripts

A container's owner (or anyone that container has been shared with — see "Shared with" on the
container detail page) can define named scripts and run them as root inside that same container,
via `/containers/{id}/scripts`. This is a different trust shape from template editing above: the
author is the container's own owner/shared-user, and the script only ever runs inside **that one
container**, never anyone else's. Those users already have full interactive root-shell access to
that exact container through their own Guacamole SSH session — running a saved script through this
feature grants no privilege they didn't already have; it's purely a convenience (named, reusable,
one click instead of retyping it over SSH each time). That's why running a script is NOPASSWD
(`/usr/bin/systemd-run --machine=* --pipe --quiet --wait /bin/sh -s`, fixed-shape, only that exact
command) unlike template-authored content above, which runs inside *other* people's containers and
is authored by an admin, not the container's own owner.

**"Shared with" grants more than session access.** Sharing a container grants the other user a
Guacamole SSH/RDP session *and* the ability to create, edit, delete, and run that container's
scripts (full root access, effectively — see above); there's no separate toggle to grant one
without the other. If you've shared containers with people purely for remote-desktop convenience,
they have script rights too.

### Other setup notes

- This account also needs read/write access to wherever you point `TEMPLATES_DIR`.
- Because this is loopback-only by design, nspawnmgr defaults to
  `strict-host-key-checking: false` for this connection. Only turn that on if you ever point
  it at a non-localhost host, and make sure the Tomcat account has a populated
  `~/.ssh/known_hosts` for the target first.
- **This all assumes nspawnmgr manages containers on the same host it runs on** (the `.deb`'s only
  supported arrangement). Pointing `nspawnmgr.ssh.host` at a different host instead is a
  manually-configured, unsupported-by-tooling scenario: you'd need to independently repeat this
  section's account/sudoers/keypair setup on that remote host yourself.
- **`nspawnmgr_exec`'s SSH access is loopback-only by design** — don't hand its credentials to
  anything outside this host. If you want an external CI/CD pipeline to be able to install/update
  container templates, use the separate, deliberately narrower `nspawnmgr_ci` account instead (see
  "Installing/updating templates from a CI/CD pipeline" above) — it's isolated in its own sudoers
  file with exactly one fixed-shape grant, unlike `nspawnmgr_exec`'s broad NOPASSWD/PASSWORD access,
  and is meant to be reached over the network.

You'll plug this account's username/password (or private key) into nspawnmgr's own config as
`nspawnmgr.ssh.*` (or `SSH_USERNAME`/`SSH_PASSWORD`/`SSH_PRIVATE_KEY_PATH`) in
[§9](#9-configuring-nspawnmgr).

## 4. Database

MySQL, MariaDB, or PostgreSQL — no H2 option. H2 is used internally by the dev-stack/CI test
harness only (an in-memory database, gone the moment that JVM stops); it was never a supported
deployment target and there's no code path left that can select it as one. MySQL and
MariaDB share the same JDBC driver, schema, and Flyway migration location — choosing one over the
other only changes which machine name the wizard defaults to (below), not which code path runs.
`spring.datasource.url` and `spring.flyway.locations: classpath:db/migration/<vendor>` must agree
(see `DB_VENDOR` in the env var reference — always `mysql` or `postgresql`, never `mariadb`).
Flyway runs migrations automatically on startup; `spring.jpa.hibernate.ddl-auto` is `validate`,
never `update` — the schema is entirely Flyway's responsibility.

The database is **self-hosted**, the same way nspawnmgr itself is ([§1](#1-architecture-overview))
— the wizard below always provisions a brand-new Debian container to run it, rather than asking you
to point it at an existing server.

### First-boot setup wizard

You don't need to prepare any database or set `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`/`DB_VENDOR`
yourself before starting Tomcat the first time — this wizard does it for you. It lives in its own
WAR (`ROOT.war`), deployed at Tomcat's root context inside the self-hosted `nspawnmgr` machine
(`http://<host>:<forwarded port>/`, [§1](#1-architecture-overview)) rather than inside
`nspawnmgr.war` itself: visiting `/` redirects you straight to `/nspawnmgr/` once a working database
is configured, or shows this wizard otherwise. Hitting `/nspawnmgr/` directly while no database is
configured yet just redirects you back to `/` — the wizard is always the one place that decides
which state you're in.

Pick a **database engine** (MySQL, MariaDB, or PostgreSQL) and, optionally, a non-default
**database machine name** — defaults to `mysqldb`, `mariadb`, or `postgresdb` per engine, editable.
Also fill in an **initial nspawnmgr username and password** — a real Linux account, created inside
the self-hosted `nspawnmgr` machine itself, that you'll log in with once setup finishes (see
[§8](#8-auth-login-backend) for why this is all `auth.war`'s PAM backend needs, with no extra
configuration).

On submit, the wizard:

1. Provisions the database machine (`nspawnmgr-bootstrap-db-machine.sh`, run over the same
   sudo-capable SSH account every other privileged operation in this app uses, see
   [§3](#3-the-sudo-capable-ssh-account)) — clones a Debian template, installs the chosen engine
   (MySQL and MariaDB both install Debian's own `mariadb-server`; there's no separate Oracle MySQL
   package on Debian), and waits for a first-boot systemd unit inside that machine to create the
   opinionated `nspawnmgr`/`guacamole` databases and users with freshly generated passwords once the
   engine is genuinely running (not attempted offline — both engines really need to run briefly to
   execute `CREATE DATABASE`/`CREATE USER`).
2. Runs nspawnmgr's own Flyway migrations, then Guacamole's schema scripts (every install always
   starts from a brand-new database, so there's no "does a schema already exist" check to run
   here), and wires up Guacamole's `guacamole-auth-jdbc` extension for you (copies the extension JAR
   into `GUACAMOLE_HOME/extensions/` and writes the `<vendor>-hostname`/`-port`/`-database`/
   `-username`/`-password` properties into `GUACAMOLE_HOME/guacamole.properties` — see
   [§7](#7-guacamole)'s "GUACAMOLE_HOME and the auth backend" for what that's for). If that last
   step fails for some reason, it's non-fatal — nspawnmgr's own database (the thing that actually
   decides whether this wizard keeps showing up) is already working at that point, and the failure
   is just surfaced as a warning telling you to finish that one step by hand.
3. Creates the initial nspawnmgr Linux account inside the self-hosted `nspawnmgr` machine, via the
   same sudo-capable account reaching back into that machine — the same mechanism
   `ProvisioningService` already uses to create an ordinary managed container's own login account.
4. Saves the working nspawnmgr connection settings to `/etc/nspawnmgr/db-config/db.properties`
   inside the `nspawnmgr` machine (owned `tomcat:tomcat`, created automatically by
   `nspawnmgr-bootstrap-app-machine.sh`).

The success page immediately reloads both `nspawnmgr.war`'s and Guacamole's own contexts in place —
no button to click, no Tomcat restart needed — by touching `/opt/tomcat9/conf/Catalina/localhost/
nspawnmgr.xml` and `guacamole.xml` (same `nspawnmgr-write-file.sh` wrapper other privileged
operations use, run via the wizard's own Spring-free SSH helper since there's no application
context yet at this point in boot); Tomcat's own background auto-deploy thread notices each change
and redeploys that context in place. For `/nspawnmgr` that re-runs its startup reachability check
and boots the real application this time. Guacamole needs the same treatment: on a fresh boot its
own webapp starts (and reads `guacamole.properties`/loads extensions, once, at that point) before
an admin has had a chance to fill in this wizard at all — without also redeploying it here,
Guacamole would keep running with no database-backed auth extension loaded and reject every login,
including the `guacadmin` account this wizard's own schema step just created. The page polls
`/nspawnmgr/` and takes you there automatically once it's up — usually a few seconds, not the full
Tomcat restart this used to require.

The wizard itself registers both the `nspawnmgr` machine and its database machine as ordinary,
visible containers in nspawnmgr's own container list — owned by the account created in step 3
above, with a "Virtual machine management"/"Database server" description each — directly in its
own database work right after migrations, no login required first (see ["Discovering machines
created outside nspawnmgr"](#discovering-machines-created-outside-nspawnmgr) for the same
underlying registration mechanism, otherwise admin-triggered by hand). When you do log in for the
first time (via that same account), you're simply reconnected to the admin identity the wizard
already created ([§3](#adminuser-roles)) — both machines are already there waiting. They aren't
hidden or special-cased afterward; you can SSH into either one, share them, delete them, like any
other container — though deleting the `nspawnmgr` machine you're currently
running from is, self-evidently, not a good idea.

**The wizard form itself is unauthenticated and reachable from any host.** There's no database yet,
so there's no users table, so there's no login system for it to sit behind — anyone who can reach
this port before the database is configured can set it up. Restrict network access to this port
yourself (firewall rules, keeping it off a public interface until §4 is done) if that matters for
your deployment.

## 5. Installing nspawnmgr

Two paths from here — pick one. **Option A (the `.deb`) does §3 and most of §6 for you**; Option B
is the fully manual walkthrough in §6 onward. (Arch Linux and Fedora/RHEL packages also exist,
same automation as Option A — see ["Installing on Arch Linux"](#installing-on-arch-linux) and
["Installing on Fedora/RHEL (RPM)"](#installing-on-fedorarhel-rpm) right after it.) Either way, §4
(database), the Guacamole `GUACAMOLE_HOME`/JDBC setup in §7, the config values in §9, and
verification in §10 are still your own responsibility — none of the three packages automate more
than the *sudo account* and *deploying the WARs into Tomcat*, not Guacamole's own storage backend
or nspawnmgr's application-level settings.

**What you need to *build* each package format is not the same as what you need to *install* it**
— worth knowing before you pick a path, especially if the machine you're building on isn't the one
you're deploying to:

| Format | Build needs | Install needs | Cross-buildable? |
|---|---|---|---|
| `.deb` (`packaging/nspawnmgr-deb/`) | JDK 21 + Maven (the `jdeb` plugin is pure Java) | `apt`, Debian/Ubuntu | **Yes** — build on any host with a JDK, including Arch/Fedora/Windows/macOS |
| Arch (`packaging/nspawnmgr-arch/`) | JDK 21 + Maven, **plus `makepkg`/`base-devel`** | `pacman`, Arch Linux | **No** — `makepkg` is native Arch tooling with no cross-platform equivalent; the build host must itself be Arch (or the `archlinux/devtools` container image) |
| RPM (`packaging/nspawnmgr-rpm/`) | JDK 21 + Maven, **plus `rpm-build`** | `dnf`, Fedora/RHEL | **No** — despite `rpm-maven-plugin`'s reputation, it genuinely shells out to a real `rpmbuild` binary; confirmed live it fails outright on a non-RPM build host (e.g. Windows) with no cross-platform equivalent, same story as Arch's `makepkg` |

If you don't have a spare Arch or Fedora machine to build these on,
`packaging/ci/arch-runner/bootstrap-arch-runner.sh` and `packaging/ci/fedora-runner/
bootstrap-fedora-runner.sh` show one way to get either without dual-booting or bare metal: both
bake a real rootfs into a plain `systemd-nspawn` container (not a
Docker/Podman image — nspawn turned out simplest here, since it shares the host's network
namespace by default rather than needing its own bridge just for CI). `.gitea/workflows/build.yml`'s
`arch-package` and `rpm-package` jobs show the exact build commands that run once each container
exists (install the JDK/Maven/native packaging tooling, then `BUILD_ARCH_PKG=1`/`BUILD_RPM=1
tools/scripts/build-all.sh`, same as shown below).

### Option A: the `.deb` package (recommended)

Debian/Ubuntu only for the **host** — the self-hosted `nspawnmgr`/database machines it creates are
always Debian regardless, per [§1](#1-architecture-overview). Handles §3 (the sudo-capable
account, sudoers, SSH keypair) and creates+boots the self-hosted `nspawnmgr` machine with Tomcat,
all four WARs, and `guacd` already installed inside it — the *rest* of §6 isn't skippable, though:
"Enabling HTTPS" and "Using a different port" in particular are still worth reading (see "What's
still manual after this" below), just applied inside that machine now rather than on the host.
Continue to §7 once it's installed.

**Get a `.deb`**, either by building one yourself:

```bash
mvn -DskipTests install                          # root -> target/nspawnmgr.war (installed, not just packaged - the next module needs it)
mvn -f auth/pom.xml -DskipTests package          # -> auth/target/auth.war
mvn -f packaging/nspawnmgr-deb/pom.xml package   # -> packaging/nspawnmgr-deb/target/nspawnmgr_*.deb
```

(or `BUILD_DEB=1 tools/scripts/build-all.sh`, which does the same three steps — that env var exists
because building a `.deb` needs network access to fetch the `jdeb` Maven plugin on first use, which
a plain dev build shouldn't be forced into), or by installing a pre-built one from wherever your
team publishes it — this repo's own CI (`.gitea/workflows/build.yml`'s `publish-deb` job) publishes
every successful build to a Gitea Debian package registry as a working reference if you want to set
up the same thing for your own fork/instance (needs a repo Actions secret `PACKAGE_REGISTRY_TOKEN`,
a Gitea access token with package-write scope — see that job's own comment in the workflow file).

**Install it:**

```bash
sudo apt install ./nspawnmgr_0.4.0_all.deb   # pulls in openssh-server, openssl, dnsmasq, systemd-container - not a JRE, not tomcat9
```

Neither `tomcat9` nor `guacd`/`guacamole-tomcat` are in this package's `Depends:` — apt's own
`tomcat9` availability varies enough by release, and `guacd`/`guacamole-tomcat` aren't packaged on
any current release at all (see `packaging/nspawnmgr-deb/debian/control`'s own note). `tomcat9`,
`guacd`, and `guacamole.war` are
all bundled instead and need nothing from you (see §6 and §7) — the only manual step left in §7 is
the database-backed auth extension, since that genuinely needs credentials only you have.

**What just happened, automatically** (see `packaging/nspawnmgr-deb/debian/postinst` and
`nspawnmgr-bootstrap-app-machine.sh` for the exact scripts):

- A `nspawnmgr_exec` system account was created on the **host**; a random password was generated
  for it (first install only — untouched on upgrade) and written to `/etc/nspawnmgr/nspawnmgr.env`
  (this is the §3 "stored-secret" sudo password — see §3 for what that means and how to switch to
  admin-approval mode instead); an SSH keypair was generated and installed into that account's
  `authorized_keys` regardless of mode. The NOPASSWD/password-tier sudoers split from §3 →
  `/etc/sudoers.d/nspawnmgr_exec`, validated with `visudo -cf` before being trusted.
- The shared bridge (`nspawnbr0`) and dnsmasq were set up on the host, same as for any other
  managed container — see "Resolving containers by name" above.
- `debian-minimal` was baked (the same tarball "Set up debian-minimal" on `/admin/templates` would
  produce) and cloned into a fresh machine named `nspawnmgr`.
- While still just an extracted rootfs, not yet booted: a JRE, the bundled Apache Tomcat 9.0.120
  tarball, all four WARs (`nspawnmgr.war`/`auth.war`/`guacamole.war`/`ROOT.war`), and the
  self-contained `guacd` bundle (own OpenSSL 3.x, minimal FFmpeg, FreeRDP2, libssh2) were installed
  directly into that machine's own filesystem — `tomcat`/`guacd` system users created inside it, the
  `manager`/`host-manager`/`examples`/`docs` webapps stripped, `GUACAMOLE_HOME` seeded with a
  minimal `guacamole.properties` pointing at that same machine's own `guacd`, and
  `guacamole-auth-jdbc` plus both JDBC driver jars extracted in (all no network access needed —
  everything bundled, nothing downloaded).
- A rewritten copy of `/etc/nspawnmgr/nspawnmgr.env` was written into that machine (`SSH_HOST` and
  `HOST_PUBLIC_ADDRESS` repointed at `nspawnbr0`'s own address instead of `127.0.0.1`, so nspawnmgr
  can reach back out to the host's `nspawnmgr_exec` account once it boots), along with a copy of
  the SSH private key.
- A free host port was picked (`8080` first, incrementing past anything already in use — printed
  during install) and forwarded into that machine's own `:8080` via a `Port=` line in its `.nspawn`
  file, so `http://<this host>:<that port>/` reaches nspawnmgr exactly like a non-self-hosted
  install always has.
- The machine was started. Tomcat inside it comes up serving `ROOT.war`'s first-boot database
  wizard (§4) — there's no database configured yet at this point, same as before, just reachable at
  a different underlying address now.

**Check it landed correctly:**

```bash
sudo machinectl list                             # should show "nspawnmgr" running
sudo visudo -cf /etc/sudoers.d/nspawnmgr_exec    # should print "parsed OK"
curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<port shown during install>/
```

Nothing Tomcat-related runs on the host itself anymore — don't look for `tomcat9.service` or
`/opt/tomcat9` there; both live inside the `nspawnmgr` machine now (`sudo machinectl shell
nspawnmgr` to look around inside it, or use nspawnmgr's own SSH access to it once you're logged in
— see §4's note on it appearing in the container list). The `.deb` never writes
`DB_URL`/`DB_USERNAME`/`DB_PASSWORD` into that machine's `nspawnmgr.env` — only the sudo/hostname
settings — so the curl check above:

- **`200`** — no working database yet, so you're looking at the first-boot setup wizard described in
  §4's "First-boot setup wizard". This is the normal state right after a fresh `.deb` install; fill
  in the wizard to continue.
- **`302`** (redirect to `/nspawnmgr/`) — a working database is already configured. Follow it and
  expect another `302` (to the login page) if the real app booted normally, or a `404` if it didn't:
  nspawnmgr's Spring context failed to start. Check `sudo machinectl shell nspawnmgr journalctl -u
  tomcat9` before assuming the package itself is broken (the nspawnmgr web UI's own "View log" page
  won't help here — nspawnmgr itself never got far enough to boot); it's usually a missing/wrong
  value in that machine's own `/etc/nspawnmgr/nspawnmgr.env` (§9 covers what every setting means).

**What's still manual after this**: pointing the first-boot wizard (§4) at a MySQL/PostgreSQL
server — it creates both the `nspawnmgr` and `guacamole` databases, runs both apps' schemas, and
wires up Guacamole's `guacamole-auth-jdbc` extension for you, but you still need to run it once and
still need to create the Guacamole admin account afterward; at least one container template (§2's
"Container templates" — nothing can be created until one exists; a fresh install starts with zero,
so `/admin/templates`' one-click "Set up debian-minimal" button is available immediately);
reviewing/adjusting the rest of `/etc/nspawnmgr/nspawnmgr.env` against §9 (Guacamole base-url,
etc. — the generated file fills in the sudo credential, `APP_SECRET_KEY`, and
`USER_ID_URL`/`AUTH_LOGIN_URL` pointed at this host's own bundled `auth.war`, but not application
config that has no sensible auto-generated default), enabling HTTPS (§6's "Enabling HTTPS" — the
`.deb` leaves Tomcat on plain HTTP by default, same as the manual path; strongly recommended if
you're using admin-approval mode, per that section), and verification (§10).

`postrm` deliberately never deletes `nspawnmgr_exec` or `/etc/nspawnmgr` on package removal/purge
— that account is the only credential your containers are reachable through.

**To upgrade an existing install to a newer package build** (a bug fix, not a fresh install):
`sudo /usr/lib/nspawnmgr/upgrade-nspawnmgr.sh <path-to-the-new-package-file>`. A plain
`apt install`/`dnf install`/`pacman -U` — or even `apt install --reinstall` — isn't enough by
itself: those can silently no-op if the recorded installed-version string hasn't changed, which
matters since every build within a dev cycle republishes under the same fixed version. This script
installs the given package file directly instead (always applies its content, regardless of the
recorded version), which in turn re-triggers the package's own postinstall — and that always calls
`nspawnmgr-bootstrap-app-machine.sh`, which fully reconciles the self-hosted `nspawnmgr` machine's
contents on every call, not just on first install: the four bundled WARs, `guacd`'s own bundle and
service, Tomcat's service unit, and the SSH-back credential file are all refreshed, and the
machine is stopped/restarted around that so nothing is overwritten while still in use. Its existing
host-forwarded port is preserved across the upgrade, not re-picked. Non-destructive —
`/var/lib/machines` (every *other* container) and both databases are left completely alone; the
base rootfs clone and the `tomcat`/`guacd` system accounts inside the machine are also left alone
(re-touching those could clobber real admin customization, or fail outright on a second run) — a
Tomcat *version* bump specifically still needs a full reinstall, same as before.

**To remove all of it anyway** (test machines, starting over from scratch — not something to run
on a real deployment without thinking about it first, since it deletes the sudo/SSH credentials
your containers stay reachable through): `sudo /usr/lib/nspawnmgr/uninstall-nspawnmgr.sh`. Beyond
what `apt purge` already does, it also removes `/opt/tomcat9`, `/etc/nspawnmgr`, `/etc/guacamole`,
`/var/lib/nspawnmgr/templates` (`TEMPLATES_DIR` — template tarballs, including anything the
"Set up debian-minimal" button downloaded; a leftover template file surviving a purge is
exactly what makes that button's "must not already exist" check fail on a later reinstall), the
`tomcat`/`nspawnmgr_exec` system accounts, and any [machine boot
settings](#starting-automatically-when-the-host-boots) nspawnmgr configured (auto-start unit
enablement, the requires-another-machine drop-in) — that's pure systemd unit-file state keyed only
by machine name, untouched by `apt purge` or even by removing the containers themselves, and a
stale `Requires=` drop-in surviving a previous install is enough to break a fresh reinstall
outright (`machinectl start nspawnmgr` failing with "A dependency job for
systemd-nspawn@nspawnmgr.service failed." because the unit it required no longer existed) —
everything here is what `postrm` deliberately leaves behind, for the cases where that conservatism
isn't what you want. By default it still does **not** touch
nspawnmgr's own database, Guacamole's own database, or `/var/lib/machines` (your actual
containers) — only the management layer around them (plus the templates used to create them) —
but it separately asks (its own y/n prompt each, never implied by `--yes`) whether to also drop
the `nspawnmgr`/`guacamole` databases and their DB users (only supported when `DB_URL` points at
`localhost`/`127.0.0.1`, read from `db.properties`/`nspawnmgr.env` before those files are removed)
and whether to remove every container currently registered with `machinectl`. Useful for quickly
resetting a real test host between iterations, since those two steps are real data loss.

### Installing on Arch Linux

Build and install both verified live on real Arch-family systems: `makepkg -f` against this exact
`PKGBUILD` (the `arch-runner` systemd-nspawn container on acer — see `packaging/ci/arch-runner/`)
produces a real `nspawnmgr-0.3.0-1-any.pkg.tar.zst` via `.gitea/workflows/build.yml`'s
`arch-package` job, and the resulting package's own `pacman -U` + `nspawnmgr.install` hooks have
been exercised repeatedly on a real SteamOS system (Arch-based, `pacman`-compatible once
`steamos-readonly disable` is run) — fresh installs, uninstall/reinstall cycles, and in-place
upgrades via `upgrade-nspawnmgr.sh` have all been confirmed working, including the self-hosted
machine coming up with a real network lease and the web UI answering correctly. A **separate**
package, `packaging/nspawnmgr-steamos/`, exists specifically for SteamOS (see its own `provides`/
`conflicts` against this one — install exactly one of the two, never both) since SteamOS's small
root partition needs storage relocated under `/home`; this plain Arch package is what a
non-SteamOS Arch host should install instead. That non-SteamOS path — installing this exact
package on genuinely vanilla Arch (as opposed to SteamOS, which shares the same underlying
`pacman`/`systemd` mechanics but isn't identical) — hasn't been directly tested yet; report back
what breaks if you try it.

`packaging/nspawnmgr-arch/` (a `PKGBUILD` + `nspawnmgr.install`, not a Maven module — no
Maven-native Arch packaging plugin exists) is otherwise the same self-hosted architecture as Option
A above, just a different package format: same `nspawnmgr_exec` account/sudoers/bridge/dnsmasq
setup, same self-hosted `nspawnmgr` machine (still Debian-minimal regardless of this host's own
distro — see [§1](#1-architecture-overview) — an Arch host doesn't change what the self-hosted
*app machine* runs, only what the *bare host* itself needs), same "What just happened," "Check it
landed correctly," and "What's still manual after this" as Option A — read those above, they apply
here unchanged. The differences are narrow:

- **Dependencies**: `openssh`, `openssl`, `dnsmasq` — no JRE, no `apache2-utils`-equivalent (both
  install *inside* the self-hosted app machine, not needed on the bare host at all — see
  `nspawnmgr-bootstrap-app-machine.sh`), no `systemd-container`-equivalent (`machinectl`/
  `systemd-nspawn` ship in Arch's own base `systemd` package already).
- **No firewall step**: unlike the `.deb`'s `ufw` DHCP carve-out, Arch ships no firewall enabled by
  default, so there's nothing to work around. If you've set up `nftables`/`iptables`/`ufw` yourself,
  make sure inbound UDP/67 on `nspawnbr0` is allowed (same requirement the `.deb`'s own `ufw` step
  exists for).
- **Removal stays conservative by default**: `pacman -R`/`-Rns` doesn't give the same purge-vs-remove
  distinction `dpkg`/`apt` does, so `nspawnmgr.install`'s `post_remove()` deliberately does as little
  as `postrm`'s own default (non-purge) behavior — same `uninstall-nspawnmgr.sh` script as the `.deb`
  handles full cleanup, still installed at the same path.

Build and install:

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_ARCH_PKG=1 tools/scripts/build-all.sh   # needs `makepkg` on PATH - a real Arch host, or the
                                               # archlinux/devtools container image

sudo pacman -U packaging/nspawnmgr-arch/nspawnmgr-0.4.0-1-any.pkg.tar.zst
```

### Installing on Fedora/RHEL (RPM)

Build and install both verified live on a real Fedora 43 host under `Enforcing` SELinux (the
`fedora-runner` systemd-nspawn container on acer for building — see
`packaging/ci/fedora-runner/` — and a separate `fedora-test-vm` QEMU guest for install
verification): the real end-to-end flow (DB setup wizard, login, container creation, and repeated
in-place upgrades via `upgrade-nspawnmgr.sh`) has been confirmed working, including under
SELinux Enforcing specifically.

`packaging/nspawnmgr-rpm/` (a real Maven module — `rpm-maven-plugin` genuinely shells out to
`rpmbuild`, it isn't pure Java despite appearances) is otherwise the same self-hosted architecture
as Option A above — same `nspawnmgr_exec` account/sudoers/bridge/dnsmasq setup, same self-hosted
`nspawnmgr` machine (still Debian-minimal regardless of this host's own distro), same "What just
happened," "Check it landed correctly," and "What's still manual after this" as Option A. The
differences are narrow:

- **Dependencies**: `openssh-server`, `openssl`, `dnsmasq`, `systemd-container`, and
  `iptables-nft` — Fedora's nftables-backed package that actually provides `/usr/bin/iptables`
  (the plain `iptables` package name doesn't exist on Fedora; the per-container outbound-internet
  toggle needs a real `iptables` binary regardless of backend).
- **firewalld carve-out**: Fedora ships `firewalld` active by default. Installing adds `nspawnbr0`
  to firewalld's `trusted` zone and reloads — without this, firewalld's default zone policy
  silently blocks DHCP leases to containers, same failure shape as SteamOS's own `firewalld`
  carve-out (below).
- **SELinux policy module**: under `Enforcing` mode, `systemd_machined_t` needs a small custom
  policy module (`nspawnmgr_machined_cgroup.te`, compiled from source at install time via
  `checkmodule`/`semodule_package`/`semodule -i` rather than shipped as a precompiled `.pp`, so it
  matches whatever policy version is actually running) granting `watch` on `cgroup_t` files — a
  general SELinux policy gap on any stock Enforcing Fedora host, not nspawnmgr-specific, that
  otherwise breaks every `machinectl`/`systemd-nspawn` container start with "Failed to register
  machine: Access denied."
- **Removal stays conservative by default**, same posture and same `uninstall-nspawnmgr.sh` script
  as the other two package formats.

One environment-topology caveat, not a code bug: `AUTH_LOGIN_URL`'s auto-detected hostname needs
to be resolvable from wherever the browser actually connects (a deliberate design choice — see
[§9](#9-configuring-nspawnmgr) — that avoids a worse cookie-scoping login loop). This can bite
specifically when testing through a NAT/tunnel/port-forward topology rather than a directly
reachable real hostname; adjust `AUTH_LOGIN_URL` by hand in that case.

Build and install:

```bash
mvn -DskipTests install
mvn -f auth/pom.xml -DskipTests package
mvn -f root-wizard/pom.xml -DskipTests package
BUILD_RPM=1 tools/scripts/build-all.sh   # needs a real `rpmbuild` binary (`rpm-build` package) -
                                          # a real Fedora/RHEL host, no cross-platform equivalent

sudo dnf install ./packaging/nspawnmgr-rpm/target/rpm/noarch/nspawnmgr-0.4.0-1.noarch.rpm
```

### Option B: build from source, deploy manually

**This path deploys Tomcat directly on the host you're working on — it does not self-host
nspawnmgr into its own machine the way Option A does.** That's fine; self-hosting is an
opinionated choice the `.deb`'s `postinst` makes, not a hard requirement — a manually-built,
host-Tomcat deployment is still fully supported, it's just the older/simpler topology. If you want
the self-hosted model without the `.deb`, the most direct path is reading through
`nspawnmgr-bootstrap-app-machine.sh` and doing what it does by hand (bake a template, clone it,
install a JRE/Tomcat/the WARs into that container's rootfs, etc.) rather than following §6 below,
which deploys Tomcat on the host itself, same as it always has.

From the repo root:

```bash
mvn -DskipTests package                # -> target/nspawnmgr.war
mvn -f auth/pom.xml -DskipTests package  # -> auth/target/auth.war
```

(`tools/scripts/build-all.sh` does both, plus the dev-only fake modules — the fakes aren't
needed for a real deployment.) Continue to §6 for the manual Tomcat/account/sudoers setup the
`.deb` would otherwise have done for you.

The `.deb`'s `postinst` also creates `/etc/nspawnmgr/auth-live/`, owned `tomcat:tomcat` mode
`750` — the shared file `/admin/settings` writes auth.war's live config to (see
[§9](#9-configuring-nspawnmgr)). A manual deploy needs the same, once Tomcat's `tomcat` user
exists (§6):

```bash
sudo mkdir -p /etc/nspawnmgr/auth-live
sudo chown tomcat:tomcat /etc/nspawnmgr/auth-live
sudo chmod 750 /etc/nspawnmgr/auth-live
```

## 6. Tomcat 9 (nspawnmgr + Guacamole + auth)

**This section describes deploying Tomcat directly on the host** — the shape a manual (§5 Option
B) install takes. If you installed via the `.deb`/Arch/RPM package (§5 Option A), Tomcat isn't
on the host at all — it's inside the self-hosted `nspawnmgr` machine, already set up by
`nspawnmgr-bootstrap-app-machine.sh`, and none of this section applies; skip straight to §7.

Guacamole's official webapp still targets `javax.servlet`, so it and nspawnmgr are deployed
side by side into the **same Tomcat 9** instance.

**Not an apt dependency.** Like `guacd` (§7), the apt `tomcat9` package's own availability
varies enough by Debian/Ubuntu/Mint release that this project bundles a vanilla upstream Apache
Tomcat binary distribution instead of relying on it — a current patch release (9.0.120), not
whatever an apt archive happens to carry, and this package owns the whole instance itself
(`/opt/tomcat9`, its own `tomcat` system user, its own `tomcat9.service`). **If a previous version
of this package (which did depend on apt `tomcat9`) is already installed, remove that package's
`tomcat9` first** — two Tomcat instances both trying to bind `:8080` will fail.

Otherwise (Option B), extract the same bundled tarball the `.deb` ships —
`packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz` in a repo checkout — rather than
downloading a fresh copy yourself, so a manual install matches the exact patch release this
project is tested against:

```bash
sudo mkdir -p /opt/tomcat9
sudo tar -xzf packaging/nspawnmgr-deb/vendor/apache-tomcat-9.0.120.tar.gz -C /opt/tomcat9 --strip-components=1
sudo chmod +x /opt/tomcat9/bin/*.sh
```

Run Tomcat as its own unprivileged, non-sudo system user (never root, and deliberately not
the same account as [§3](#3-the-sudo-capable-ssh-account)):

```bash
sudo useradd -r -M -d /opt/tomcat9 -s /usr/sbin/nologin tomcat
sudo chown -R tomcat:tomcat /opt/tomcat9
```

**If you did [§3](#3-the-sudo-capable-ssh-account) before this** (the documented order), go back and
make the SSH keypair it generated (`SSH_PRIVATE_KEY_PATH`, default `/etc/nspawnmgr/ssh_id_ed25519`)
readable by this `tomcat` user now that it exists — `SshRemoteExecutor` opens that file directly
from inside Tomcat's own process on every privileged operation, and the key is created `root:root`
mode `600` (no group access at all) since `tomcat` doesn't exist yet at that point:

```bash
sudo chown root:tomcat /etc/nspawnmgr/ssh_id_ed25519
sudo chmod 640 /etc/nspawnmgr/ssh_id_ed25519
```

Skipping this leaves every privileged operation failing with "Failed to establish SSH connection
to 127.0.0.1:22" — a permissions problem, not a connectivity one, despite the wording.

The upstream tarball bundles `manager`/`host-manager`/`examples`/`docs` webapps that Debian's own
`tomcat9` package splits into separate, not-installed-by-default sub-packages; the `.deb`'s
`postinst` strips these on first install for the same reason — real, avoidable attack surface if
left deployed unconfigured — worth doing by hand here too:

```bash
sudo rm -rf /opt/tomcat9/webapps/manager /opt/tomcat9/webapps/host-manager \
       /opt/tomcat9/webapps/examples /opt/tomcat9/webapps/docs
```

Deploy nspawnmgr:

```bash
sudo cp target/nspawnmgr.war /opt/tomcat9/webapps/nspawnmgr.war
```

nspawnmgr, Guacamole, and `auth` (§8) each take their own context path below — none of them can
claim the server root without giving up that path — so drop in a tiny static redirect page for
bare `http://<hostname>:8080/`, using this repo's own `site/root-index/index.html` as a
reference (redirects to `/nspawnmgr/`):

```bash
sudo mkdir -p /opt/tomcat9/webapps/ROOT
sudo cp site/root-index/index.html /opt/tomcat9/webapps/ROOT/index.html
sudo chown -R tomcat:tomcat /opt/tomcat9/webapps/ROOT
```

Set `SPRING_PROFILES_ACTIVE=prod` (plus every other env var from [§9](#9-configuring-nspawnmgr))
in whatever wraps Tomcat's startup (a systemd unit's `Environment=`/`EnvironmentFile=`, or
`bin/setenv.sh` under `CATALINA_OPTS` — quote every `-D` value if it contains a `;`, since
`catalina.sh` re-evaluates `$CATALINA_OPTS` as a shell command line and an unescaped `;` gets
parsed as a command separator, silently truncating the launch). Without a profile active,
nspawnmgr defaults to `dev` (in-memory H2, fake executors) — not what you want here.

Set it up as a systemd service so it survives reboots, e.g. `/etc/systemd/system/tomcat9.service`
(the same unit the `.deb` installs — `packaging/nspawnmgr-deb/tomcat9.service` in a repo
checkout is a ready-made reference):

```ini
[Unit]
Description=Apache Tomcat 9 (bundled by nspawnmgr)
After=network.target

[Service]
Type=simple
ExecStart=/opt/tomcat9/bin/catalina.sh run
ExecStop=/opt/tomcat9/bin/catalina.sh stop
User=tomcat
Group=tomcat
Restart=on-failure
RestartSec=2
EnvironmentFile=/etc/nspawnmgr/nspawnmgr.env

[Install]
WantedBy=multi-user.target
```

`Type=simple` with `catalina.sh run` (foreground) rather than `Type=forking` with
`startup.sh`/`shutdown.sh` — systemd supervises the JVM directly this way, so a crash is
detected and `Restart=on-failure` actually fires; a forking unit only knows whether the
*wrapper script* exited, not whether Tomcat itself is still alive.

```bash
sudo systemctl enable --now tomcat9
```

### Using a different port

Tomcat listens on `8080` by default (`conf/server.xml`'s `<Connector port="8080" .../>`). To
change it, edit that `port` attribute directly:

```bash
sudo sed -i 's/port="8080"/port="8180"/' /opt/tomcat9/conf/server.xml
```

Or use the **Tomcat** section on `/admin/settings` instead of editing `server.xml` by hand — it
reads/writes the same file (located via the `catalina.base` JVM system property Tomcat's own
startup script always sets, so it finds the right `server.xml` whether you're running the `.deb`'s
Debian-packaged `tomcat9` or a manually-extracted one under `/opt/tomcat9`), going through the same
sudo-capable SSH account and `nspawnmgr-write-file.sh` wrapper script every other privileged
operation already uses — no new sudoers grant needed. It's the **file itself that's authoritative**,
not a database copy: the page always shows and edits whatever's actually on disk, so hand-editing
`server.xml` directly (as above) and using the settings page are fully interchangeable — neither one
goes stale relative to the other.

Every other `:8080` in this guide (and in your own config —
`nspawnmgr.auth.user-id-url`/`AUTH_LOGIN_URL`, `nspawnmgr.guacamole.base-url`, and whatever URL you
tell users to visit) must be updated to match — nothing derives the port automatically from
`server.xml`, whichever way you change it. On `/admin/settings` this is mostly one click per field:
each of those URL fields has a "Refresh hostname/port/protocol" button that rewrites it from the
Tomcat section's current port/HTTPS state plus `host.external-hostname` (§8) — no need to hand-edit
each URL's port separately. If you're behind a firewall, make sure the new port is open instead of
`8080`. Either way, the change only takes effect after a restart — use the Restart Tomcat button on
`/admin/settings` (see above) or `sudo systemctl restart tomcat9` yourself.

### Enabling HTTPS

Two options, in order of how most real deployments actually do this:

1. **Terminate TLS with a reverse proxy** (nginx, Apache, Caddy, a cloud load balancer) in front
   of Tomcat, which keeps listening on plain HTTP on `127.0.0.1:8080` only (bind it to loopback
   in `server.xml`'s `<Connector address="127.0.0.1" .../>` so it's not reachable directly).
   This is usually the easier path for certificate renewal (e.g. Certbot/Let's Encrypt) since
   it's decoupled from Tomcat's own keystore format. Point every `nspawnmgr.*`/`AUTH_LOGIN_URL`
   URL in this guide at `https://<hostname>/...` (whatever port the proxy listens on) instead of
   `http://<hostname>:8080/...` — the proxy, not Tomcat, is what the hostname/cookie
   requirements in [§8](#hostname-and-the-shared-session-cookie) actually apply to.

2. **Configure a Tomcat SSL connector directly**, if you'd rather not run a reverse proxy. Since
   Tomcat 8.5/9, `<SSLHostConfig>`'s `<Certificate>` element accepts a PEM certificate/key
   directly (`certificateFile`/`certificateKeyFile`/`certificateChainFile`) — no Java keystore
   conversion needed, which matters because this is exactly the format Let's Encrypt/ACME
   clients (e.g. Certbot) hand you (`fullchain.pem`/`privkey.pem`). Point Certbot at this host
   (`certbot certonly --standalone -d nspawnmgr.example.com`, or whatever plugin fits your setup)
   and add a connector to `server.xml`:

   ```xml
   <Connector port="8443" protocol="org.apache.coyote.http11.Http11NioProtocol"
              SSLEnabled="true" scheme="https" secure="true" maxThreads="150">
       <SSLHostConfig>
           <Certificate certificateFile="/etc/letsencrypt/live/nspawnmgr.example.com/fullchain.pem"
                        certificateKeyFile="/etc/letsencrypt/live/nspawnmgr.example.com/privkey.pem"
                        type="RSA"/>
       </SSLHostConfig>
   </Connector>
   ```

   The `tomcat` system user needs read access to `/etc/letsencrypt/live/.../*.pem` (Let's
   Encrypt's own directories are usually root-only by default — either loosen permissions on
   just those two files or copy them somewhere Tomcat can read, and re-copy on every renewal).
   Restart Tomcat, then use `https://<hostname>:8443/...` everywhere in this guide instead of
   `http://<hostname>:8080/...`. Either remove the plain HTTP connector entirely or set its
   `redirectPort="8443"` so a stray HTTP request gets bounced to HTTPS rather than served in the
   clear. Certbot's renewal doesn't restart Tomcat for you — add a
   `--deploy-hook "systemctl restart tomcat9"` (or a `renewal-hooks/deploy/` script) so a renewed
   certificate actually takes effect.

   The **Tomcat** section on `/admin/settings` builds/edits exactly this connector block for
   you — an "HTTPS" dropdown plus the two PEM paths — using the same file-is-authoritative,
   SSH-wrapper-script mechanism described in "Using a different port" above. It never removes the
   plain HTTP connector or sets `redirectPort` for you, and it always fully replaces the existing
   `<Certificate>` element's paths on save rather than merging — if you've customized the
   connector beyond what's shown here (a non-`RSA` certificate type, multiple `SSLHostConfig`
   entries, etc.), edit `server.xml` by hand instead.

Whichever option you pick, every `http://` URL referenced elsewhere in this guide — including
inside `application.yml`/env vars, not just what a browser sees — needs to become `https://` to
match; a mismatch between what nspawnmgr is configured with and what's actually served is a
common source of redirect loops or cookie-not-sent failures.

**If you're using admin-approval mode** ([§3](#3-the-sudo-capable-ssh-account)), enabling HTTPS
here is strongly recommended even if nothing else prompted you to: the approval page submits an
admin's sudo password as a plain form field, and that's a meaningfully bigger exposure over
plaintext HTTP than anything else nspawnmgr serves. The documented default install stays HTTP —
this is a recommendation for that specific mode, not a change to the default.

## 7. Guacamole

**None of Guacamole's three components are apt packages on any current Debian/Ubuntu/Mint
release**: `guacd` and `guacamole-tomcat` return zero results on bookworm, trixie, jammy, and noble,
and even Debian unstable only builds `guacd` for `ia64`/`riscv64`, not `amd64`. Each is handled
differently, and none of them alone gets you a working setup:

| Component | Packaged? | What it does |
|---|---|---|
| `guacd` | **No.** The `.deb` bundles a self-contained build instead (own OpenSSL 3.x, a minimal FFmpeg, FreeRDP2, and libssh2 — see `/usr/share/doc/nspawnmgr/guacd-bundle-README.md` for exactly why and how) and runs it as its own `guacd.service` systemd unit — no system package, no manual step, on any install option. | the native proxy daemon |
| `guacamole-tomcat` | **No.** Not bundled either (it's the *packaging glue* that would normally deploy `guacamole.war` for you) — but `guacamole.war` itself is: the `.deb` deploys it into the bundled Tomcat directly, same as `nspawnmgr.war`/`auth.war` (see below). | deploys `guacamole.war` into Tomcat automatically |
| `guacamole-auth-jdbc` | **No.** Not an apt package, but bundled the same way as `guacd` — a tarball downloaded once, checksum-verified, and committed to `packaging/nspawnmgr-deb/vendor/` (see `vendor/README.md`), not fetched fresh at install time. The `.deb`'s `postinst` extracts it automatically, no network needed; manual installs run the same script by hand (see below). **Required, not optional** — see below. | the JDBC extension that gives Guacamole a MySQL/PostgreSQL connection-storage backend, plus its SQL schema scripts |

`guacamole-auth-jdbc` isn't one option among several backends you could pick instead — nspawnmgr
manages every Guacamole connection and user through Guacamole's REST API (see "GUACAMOLE_HOME and
the auth backend" below), and that API only exists when Guacamole is running a database-backed auth
extension. Guacamole's own default (`user-mapping.xml`, a static XML file with no API) doesn't
expose it. Skipping this step doesn't give you a working nspawnmgr with reduced functionality — it
gives you an nspawnmgr that can't create or manage any container connection at all, since every
"give this user access to this container" action ultimately calls through to this API. Even with
the `.deb`'s automation, extracting the tarball is only half of what §7 step 1 below describes —
the JAR/driver still need to be copied into `GUACAMOLE_HOME` by hand, and neither `guacd` nor
`guacamole.war` being deployed implies any of this is done; confirm it separately.

### guacd

If you installed via the `.deb` (§5 Option A), this is already done — `nspawnmgr-bootstrap-app-
machine.sh` extracted the self-contained bundle to `/opt/guacd-bundle` and started `guacd.service`
**inside the self-hosted `nspawnmgr` machine**, not on the host (`sudo machinectl shell nspawnmgr
systemctl status guacd` to confirm) — and skip to "guacamole.war" below.

Otherwise (Option B, host-Tomcat deployment — [§6](#6-tomcat-9-nspawnmgr--guacamole--auth)), you
need a real `guacd` binary from somewhere, since apt won't provide one on
any current release. The most direct path is to reuse the same self-contained build the `.deb`
ships: `packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz` in a repo checkout (or build your own
copy following `packaging/nspawnmgr-deb/vendor/README.md`'s recipe — it documents every step,
including two real pitfalls that cost real time to find: CMake silently caching a stale OpenSSL
path across reconfigures, and `-Wl,-rpath` not being enough on its own without a matching `-L`).
Extract it and install the systemd unit the same way `postinst` does:

```bash
sudo tar -xzf packaging/nspawnmgr-deb/vendor/guacd-bundle.tar.gz -C /opt
sudo adduser --system --home /nonexistent --no-create-home --group guacd
sudo cp packaging/nspawnmgr-deb/guacd.service /etc/systemd/system/guacd.service
sudo systemctl daemon-reload
sudo systemctl enable --now guacd
```

### guacamole.war

If you installed via the `.deb` (§5 Option A), this is already done too —
`nspawnmgr-bootstrap-app-machine.sh` deployed `packaging/nspawnmgr-deb/vendor/guacamole-1.5.5.war`
(the same official Apache release, downloaded once and checksum-verified, not fetched fresh at
install time) via a context descriptor pointing at `/usr/share/nspawnmgr/guacamole.war` **inside
the self-hosted `nspawnmgr` machine**, alongside `nspawnmgr.war`/`auth.war`. Confirm with
`curl -o /dev/null -s -w '%{http_code}\n' http://localhost:<forwarded port>/guacamole/` (expect
`200`, or a redirect into Guacamole's own login flow) and skip to "GUACAMOLE_HOME and the auth
backend" below.

Otherwise (Option B, host-Tomcat deployment), download and deploy the same file yourself:

```bash
GUACAMOLE_VERSION=1.5.5
curl -fsSL -o guacamole.war \
  "https://archive.apache.org/dist/guacamole/${GUACAMOLE_VERSION}/binary/guacamole-${GUACAMOLE_VERSION}.war"
sudo cp guacamole.war /opt/tomcat9/webapps/guacamole.war
```

### GUACAMOLE_HOME and the auth backend

Guacamole needs its own `GUACAMOLE_HOME` (commonly `/etc/guacamole`) containing `guacamole.properties`
plus the `guacamole-auth-jdbc` extension JAR/JDBC driver for its **connection-storage backend** —
this is a separate concern from nspawnmgr's own database. **§4's first-boot database wizard now does
steps 1–2 below automatically** (copying the right extension JAR in, writing the `<vendor>-*`
properties, running the schema) as part of setting up the `guacamole` database — the walkthrough
below is for doing it by hand instead (no wizard access, the automatic wiring failed and left a
warning, or you're changing the database after the fact). If you installed via the `.deb`, this
directory and a minimal `guacamole.properties` (just `guacd-hostname`/`guacd-port`, pointed at the
`guacd` instance the same install already started) already exist, `tomcat:tomcat`-owned — created
once, first-install-only, so a later edit (by hand or via `/admin/settings`' Guacamole editor)
always survives an upgrade. Otherwise (Option B), create it yourself:
`sudo mkdir -p /etc/guacamole && sudo chown tomcat:tomcat /etc/guacamole`. As covered above, the
JDBC auth extension itself is required, not a choice among alternatives: nspawnmgr manages
connections/users through Guacamole's REST API using an admin account
(`nspawnmgr.guacamole.admin-username`/`admin-password`), and only `guacamole-auth-jdbc` exposes
that API. So:

1. Get the `guacamole-auth-jdbc` tarball extracted — unlike `guacd`/`guacamole-tomcat` above,
   there's no apt package for this on any release, but like `guacd` it's bundled directly rather
   than downloaded at install time: `packaging/nspawnmgr-deb/vendor/guacamole-auth-jdbc-1.5.5.tar.gz`
   in a repo checkout is the same tarball the `.deb` ships, already downloaded once and
   checksum-verified against Apache's own `.sha256`. `install-guacamole-auth-jdbc.sh` extracts it
   (no network needed) into a fixed, version-independent **opinionated install location**,
   `/etc/guacamole/guacamole-auth-jdbc/` (`mysql/schema/` and `postgresql/schema/` subfolders,
   regardless of which database you end up using — the tarball ships both). This is not a path
   Guacamole itself requires, just nspawnmgr's own convention:
   - **`.deb` installs**: this already ran automatically, as part of `postinst` — if it failed
     (e.g. the tarball is somehow missing from `/usr/share/nspawnmgr/`), rerun
     `sudo /usr/lib/nspawnmgr/install-guacamole-auth-jdbc.sh` by hand.
   - **Manual installs**, or to redo it (e.g. to bump the Guacamole version — re-vendor the tarball
     first): run `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-auth-jdbc.sh` from a repo
     checkout (`--source-tarball`/`--target-dir`/`--force` flags available — see the script's own
     header comment).

   Either way, from `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/`, copy the extension JAR
   for your chosen database (`nspawnmgr.guacamole.data-source`, e.g. `mysql`) into
   `GUACAMOLE_HOME/extensions/` — still a manual step, since it depends on a choice (which database)
   nothing can make for you.

   The JDBC driver itself (the actual `java.sql.Driver`, separate from the extension JAR above —
   `guacamole-auth-jdbc` never bundles it) is a different story: nspawnmgr.war already bundles both
   the MySQL and PostgreSQL drivers for its own, unrelated database use (root `pom.xml`), so rather
   than a second separate download, `install-guacamole-jdbc-drivers.sh` just copies both of
   nspawnmgr's own already-built driver jars into `GUACAMOLE_HOME/lib/` — no network access needed
   at all, and no harm in both sitting there even though only one is actually used. Like the schema
   tarball above, this already ran automatically as part of `.deb` `postinst` (best-effort — rerun
   `sudo /usr/lib/nspawnmgr/install-guacamole-jdbc-drivers.sh` if it failed for some reason); for a
   manual install, run
   `sudo packaging/nspawnmgr-deb/scripts/install-guacamole-jdbc-drivers.sh --source-dir target/guacamole-jdbc-drivers`
   from a repo checkout after `mvn -DskipTests package`.
2. Run that extension's schema script against a database Guacamole owns (this is **not**
   the same database as nspawnmgr's own — Guacamole needs its own users/connections schema).
   The Guacamole section on `/admin/settings` has a **"Test database connection"** button that does
   this for you: it connects with whatever's currently entered in the Database fields, checks
   whether the schema looks set up (probing for the `guacamole_connection` table), and if not,
   offers to run every `.sql` file in a directory you point it at — the "Schema scripts directory"
   field already defaults to `/etc/guacamole/guacamole-auth-jdbc/<mysql|postgresql>/schema` (matching
   the database type selected above it), so this is usually a no-edit "Test" click if step 1 used
   the opinionated location.
3. Create the admin account nspawnmgr will use (`guacadmin`/`guacadmin` is the well-known
   default the JDBC extension ships with on first run — change the password immediately in
   a real deployment, and update `nspawnmgr.guacamole.admin-password` to match).
4. Set `guacd-hostname`/`guacd-port` in `guacamole.properties` (defaults to `localhost:4822`,
   fine if guacd runs on the same host).

Restart Tomcat after dropping files into `GUACAMOLE_HOME` — Guacamole doesn't hot-reload
extensions.

Point nspawnmgr at it (`nspawnmgr.guacamole.base-url`) once it's up, e.g.
`http://your-hostname:8080/guacamole`. Also set `nspawnmgr.guacamole.home` (`GUACAMOLE_HOME`,
default `/etc/guacamole`) if you used a non-default path — this is what
`/admin/settings`'s Guacamole editor reads/writes `guacamole.properties` from (see
[§9](#9-configuring-nspawnmgr)). No extra permission setup needed: nspawnmgr and Guacamole both
run as the same `tomcat` user in the same Tomcat instance, and `GUACAMOLE_HOME` is already
`tomcat`-owned for Guacamole's own use.

## 8. `auth` (login backend)

`auth.war` is the thing that actually checks a username/password against your OS accounts
(PAM) or a Windows machine over SMB, and issues the shared session cookie nspawnmgr trusts.
It targets `javax.servlet` (Servlet 4.0), the same as nspawnmgr and Guacamole's webapp, so it
deploys into the **same Tomcat 9 instance** from §6 — no separate servlet container needed.
(For quick local iteration only, it can also be run standalone via `mvn -f auth/pom.xml
jetty:run`, which starts it on Jetty on port 9092 without a WAR rebuild/redeploy cycle — not
something you'd use for a real deployment.)

Set these via context-params in `auth/src/main/webapp/WEB-INF/web.xml` (rebuild the WAR after
editing) or the matching system properties (`-D...`), documented in that file:

| Setting | System property | Purpose |
|---|---|---|
| `auth.backend` | `AUTH_BACKEND` | `pam` (default, local Linux accounts on auth's own host) or `smb` (remote Windows machine) |
| `smb.server` | `SMB_SERVER` | Required if `auth.backend=smb` — the Windows host to authenticate against |
| `smb.domain` | `SMB_DOMAIN` | Optional NTLM domain |
| `auth.required-group` | `AUTH_REQUIRED_GROUP` | Optional, `pam` only — a Unix group; login is refused for authenticated users who aren't a member |
| `smb.required-share` | `SMB_REQUIRED_SHARE` | Optional, `smb` only — an SMB share on `smb.server`; login is refused unless the user has access to it (see below for why this is a share check, not a group check) |
| `cookie.name` | — | Must match nspawnmgr's `nspawnmgr.auth.cookie-name` (default `nspawnmgr_session`) |

**Why `smb` gates on share access, not group membership:** Windows restricts *remote* SAM/group
queries to `BUILTIN\Administrators` by default (`RestrictRemoteSAM`) — this would exclude
ordinary users from ever passing a group check, by design, regardless of registry tweaks.
Share access is a normal, ACL-gated SMB operation with no such restriction, so grant/deny
access by setting ordinary share and NTFS permissions on `smb.required-share` for the users
who should/shouldn't be allowed to log in.

**`pam` needs the Tomcat account readable access to `/etc/shadow`.** Verifying a password via PAM
ultimately means reading the target user's hash out of `/etc/shadow` (mode `640`, `root:shadow`) —
normally handled transparently through `pam_unix`'s own setgid-`shadow` `unix_chkpwd` helper
regardless of the calling process's own group, but that fallback isn't reliable on every host (a
real install hit exactly this: `unix_chkpwd`'s setgid promotion silently didn't take effect for
*any* non-root caller at all, so every PAM login failed with a bare "Login failed" and no
actionable error in `auth.war`'s own log). The `.deb`'s `postinst` adds `tomcat` to the `shadow`
group directly (`usermod -aG shadow tomcat`) to sidestep this — `pam_unix` can then read
`/etc/shadow` itself, no `unix_chkpwd` fallback needed either way. A manual (non-`.deb`) install
needs the same: `sudo usermod -aG shadow tomcat`, then restart Tomcat (group membership only
applies to processes started *after* the change, not an already-running one). If PAM logins fail
after that, check `/var/log/auth.log` for the actual `pam_unix(login:auth)` line — it's the most
direct way to see what PAM itself rejected, since `auth.war`'s own "Login failed" page is
deliberately generic (no credential-enumeration hints).

Deploy it at its own `/auth` context path in the same Tomcat 9 instance as nspawnmgr/Guacamole
(which take `/nspawnmgr` and `/guacamole`) so it serves `/auth/login`, `/auth/userinfo`,
`/auth/logout` (matching `nspawnmgr.auth.user-id-url` below):

```bash
sudo cp auth/target/auth.war /opt/tomcat9/webapps/auth.war
```

`tools/scripts/setup-auth-tomcat.sh` is a reference for exactly this, adapted for local testing.
`auth`'s own login/logout pages build their internal links (e.g. "Try again") from
`request.getContextPath()`, not a hardcoded path, so they resolve correctly regardless of
whether it's deployed at `/auth` here or at the server root (e.g. via `jetty:run` for local
iteration).

### Hostname and the shared session cookie

nspawnmgr, `auth`, and Guacamole **must all be reachable through the same hostname** — the
session cookie `auth` sets is only useful to nspawnmgr if both are on the same origin's
cookie scope. Since all three now share one Tomcat instance, this is largely automatic (same
host, same port), but still pick a real hostname (not `localhost`, unless everything really is
on one box you'll only ever access as `localhost`), point it at the host's IP in DNS or
`/etc/hosts`, and set it once in **`nspawnmgr.host.external-hostname`** (`HOST_EXTERNAL_HOSTNAME`
— live-editable on `/admin/settings`, "External hostname" under Host; seeded automatically to this
machine's real hostname by `setup-sudo-account.sh` on `.deb` installs, see §5). This is *not* the
same setting as `nspawnmgr.host.public-address` right below it on that page — see that field's own
description, or [§9](#9-configuring-nspawnmgr), for the difference.

Everywhere else this hostname needs to appear is a plain URL field, not derived automatically —
`nspawnmgr.auth.user-id-url` (`http://<hostname>:8080/auth/userinfo`),
`nspawnmgr.guacamole.base-url`, and the login page administrators/users are told to visit
(`http://<hostname>:8080/auth/login?returnTo=...`) — but `/admin/settings` closes that gap: each of
those URL fields has a **"Refresh hostname/port/protocol"** button that rewrites it from External
hostname above plus the Tomcat section's current port/HTTPS state (see §6), so a hostname or port
change only has to be typed in one place before clicking through the rest.

If you terminate HTTPS in front of this, the certificate's CN/SAN must match that hostname —
a mismatch here is the most common cause of "login works but nspawnmgr still shows the
login-required page."

**Always browse to nspawnmgr via the same hostname as `HOST_EXTERNAL_HOSTNAME`/`AUTH_LOGIN_URL`
— not `localhost`, an IP address, or any other alias, even if it resolves to the same box.** The
cookie `auth.war` issues has no `Domain` attribute, so it's scoped to the exact host:port that
served the login page — whatever `AUTH_LOGIN_URL` points at, not whatever hostname you originally
typed. nspawnmgr's redirect-to-login always sends `returnTo` back to that same host:port too
(regardless of which hostname you started at), so a mismatch here doesn't loop forever, but you
will land on the canonical hostname rather than the one you typed — simplest to just always use
the right one from the start.

### The nspawnmgr → auth redirect

When nspawnmgr can't validate a session cookie, it redirects the browser to
`nspawnmgr.auth.login-url` (env var `AUTH_LOGIN_URL`) with a `returnTo` query parameter
pointing back at the page the user was trying to reach; `auth.war` redirects back there after
a successful login. If `login-url` is left blank, nspawnmgr instead shows its own static
"login required" page with no redirect — set `AUTH_LOGIN_URL` to `auth`'s `/auth/login` URL
(e.g. `http://<hostname>:8080/auth/login`) for the full automatic flow.

## 9. Configuring nspawnmgr

All settings live under `nspawnmgr.*` in `src/main/resources/application.yml`, each overridable
by an environment variable — see `site/env/.env.example` for the full list as env vars, and
`dev_env/application-dev_env.example.yml` for the same settings as YAML. The important groups:

- **`nspawnmgr.ssh.*`** — the sudo-capable account from [§3](#3-the-sudo-capable-ssh-account)
  (`SSH_HOST`/`SSH_PORT`/`SSH_USERNAME`/`SSH_PASSWORD`, host always `127.0.0.1`), plus
  `SSH_PRIVATE_KEY_PATH`, `SSH_CONNECT_TIMEOUT_MS`, `SSH_STRICT_HOST_KEY_CHECKING`. Leaving
  `SSH_PASSWORD` blank switches container creation to admin-approval mode and requires
  `SSH_PRIVATE_KEY_PATH` to be set instead (SSH transport auth needs *something* to authenticate
  with either way).
- **`nspawnmgr.auth.user-is-admin-json`** — optional JsonPath for external-managed admin roles
  ([§3](#3-the-sudo-capable-ssh-account)); leave blank for the default app-managed mode
  (first-ever user becomes admin, manageable afterward at `/admin/users`).
- **`nspawnmgr.guacamole.*`** — `base-url`, `admin-username`/`admin-password`, `data-source`,
  `home` (`GUACAMOLE_HOME`, default `/etc/guacamole`), from [§7](#7-guacamole).
- **`nspawnmgr.auth.*`** — `user-id-url` (validates an existing cookie against `auth`),
  `cookie-name`, `login-url` (the redirect target from §8), cache/timeout tuning, `settings-file`
  (where the shared auth-settings file below is written — must match auth.war's own
  `auth.settings-file`/`AUTH_SETTINGS_FILE`, default `/etc/nspawnmgr/auth-live/auth-settings.properties`).
- **`nspawnmgr.nspawn.*`** — `templates-dir`, `machines-dir`, `settings-dir`,
  `privileged-scripts-dir` from [§2](#2-host-prerequisites).
- **`nspawnmgr.dns.upstream-servers`** — comma-separated IP literals dnsmasq forwards non-`.internal`
  lookups to, default `1.1.1.1,9.9.9.9` — see ["Resolving containers by
  name"](#resolving-containers-by-name). `hosts-file`/`upstream-servers-file` (which files
  `ContainerDnsSyncService` writes) are deploy-time paths, not live-editable.
- **`nspawnmgr.host.external-hostname`** (`HOST_EXTERNAL_HOSTNAME`) — the shared hostname from
  [§8](#hostname-and-the-shared-session-cookie); what users outside this host use, and what
  `/admin/settings`' URL "Refresh" buttons pull into every Guacamole/Auth URL.
- **`nspawnmgr.host.public-address`** (`HOST_PUBLIC_ADDRESS`) — a different, easily-confused-for-the-above
  setting, not used by the SSH/RDP path any more (`guacd` and nspawnmgr's own readiness check now
  dial a MANAGED container's internal veth address directly instead — see [Container
  networking](#container-networking)). Its only remaining consumer is the "HOST_PUBLIC_ADDRESS not
  loopback" check on the Network Diagnostics page; whether that check still earns its keep is worth
  a follow-up look, but hasn't been revisited yet. `setup-sudo-account.sh` still auto-detects and
  seeds this host's real address here on install.
- **`nspawnmgr.crypto.secret-key`** (`APP_SECRET_KEY`) — generate with
  `openssl rand -base64 32`; used to encrypt secrets nspawnmgr stores (e.g. Guacamole
  credentials it manages per container). Losing/rotating this invalidates anything already
  encrypted with the old key.
- **`nspawnmgr.provisioning.*`** — `admin-account-name` (the fallback account nspawnmgr creates
  inside a new container when its owner's own username can't be used — see `Container users`
  below), `rdp-password-length`.
- **`CONTAINER_CLI_EXECUTOR=real`** — must be `real` for an actual deployment; `fake` is dev/CI
  only, and never touches SSH/sudo/passwords at all regardless of the container-creation mode
  above. Selects which Spring beans get wired at context startup, so it can't be changed at
  runtime at all — not exposed on `/admin/settings`, deliberately: this is a deployment-time
  choice, and given what `fake` does (every container operation becomes a silent no-op), it's not
  worth the risk of exposing it as a runtime toggle.

Set `SPRING_PROFILES_ACTIVE=prod` — this activates the real SSH-backed executors instead of
the in-memory fakes used for local development.

### Live-editable settings (`/admin/settings`)

A subset of the groups above can also be changed at runtime at `/admin/settings` (admin-only):
`guacamole.base-url`/`data-source`, `host.external-hostname`/`public-address`,
every `auth.*` field including `http-timeout-ms`, `provisioning.admin-account-name`/`rdp-password-length`,
`nspawnmgr.ssh.*`, `nspawnmgr.nspawn.*`, and `nspawnmgr.dns.upstream-servers`. These take effect immediately for every subsequent
request/allocation — `SettingsService` holds an in-memory snapshot refreshed the moment a change
is saved, not a per-request database read. One exception, called out on the page itself:

- **`nspawnmgr.nspawn.privileged-scripts-dir`** takes effect immediately like everything else in
  its group, but changing it *without also updating* `/etc/sudoers.d/nspawnmgr_exec`'s hardcoded
  paths to match breaks **every** privileged operation (container start/stop, outbound-access
  sync, Restart Tomcat below) — sudo fails safe, simply refusing the new path, rather than
  following this setting. There's no live validation for this one (it's a local path, possibly not
  even created yet at save time) — just the warning shown on the page.
- **`nspawnmgr.dns.upstream-servers`** takes effect in `SettingsService`'s own snapshot immediately
  like everything else, but reaching the actual running dnsmasq is one step removed from that:
  `ContainerDnsSyncService` only picks up the new value, rewrites
  `/etc/dnsmasq.d/nspawnmgr-upstream.conf`, and restarts dnsmasq on its own ~15s poll — see
  ["Resolving containers by name"](#resolving-containers-by-name) for why that's a full
  `systemctl restart`, not just a reload.

**Everything else stays static/env-var/restart-only**, deliberately:
`nspawnmgr.crypto.secret-key`/`nspawnmgr.guacamole.admin-username`/`admin-password` (secrets, plus
rotating the crypto key live would invalidate anything already encrypted with the old one),
and `CONTAINER_CLI_EXECUTOR` (see above). Hosts are not a static setting at all — they're fully
admin-managed via each host's own detail page and `/admin/hosts/new` (see "Hosts: admin-managed
external machines" above).

Every change is validated before being accepted:
- **Guacamole base URL, auth user-ID URL, auth login URL**: a live HTTP reachability probe (any
  response, even a 404, counts as reachable — this only proves the URL resolves to something
  listening, not that authentication itself succeeds).
- **The five JsonPath fields**: must compile as valid JsonPath expressions.
- **Host public address**: format-only (hostname/IP syntax) — deliberately *not* probed, since a
  public address is often only reachable from outside this host; self-probing it would prove
  nothing.
- Cookie name, cache TTL, admin account name, and RDP password length get basic
  format/range checks.
- **`dns.upstream-servers`**: must be a comma-separated list of IP literals (IPv4 or IPv6) — a
  hostname is rejected, since dnsmasq's own `server=` directive needs one to already be resolvable
  without any DNS server at all (it's what dnsmasq itself uses to resolve everything else).
- **`ssh.*`**: if any SSH field is present in the submitted change, a real SSH connection is
  opened with the *resulting* settings (transport login only — no command execution, so this
  doesn't depend on the NOPASSWD sudoers grant being correct) before the change is accepted. The
  settings page always resubmits every field together (like every other section here), so in
  practice this runs on every save from the UI — the same way the existing Guacamole/auth URL
  reachability probes already do. Calling the API directly with a partial payload that omits every
  `ssh.*` key skips it.

#### Auth section (conditional on auth.war being detected)

If auth.war looks reachable (a live probe of `auth.login-url`), `/admin/settings` also shows a
section for auth.war's **own** backend config: `auth.backend` (`pam`/`smb`), SMB server/domain,
and the required-group/required-share gates from
[§8](#8-auth-login-backend) — today these only live in auth.war's `web.xml`
context-params/system properties, fixed at deploy time.

Saving this section (along with the cookie name above, which auth.war also needs to agree on —
it's the one that actually sets the cookie) writes them to the shared properties file at
`nspawnmgr.auth.settings-file`. `AuthConfig` checks this file **first**, on every request, ahead
of its own context-params/system properties — so a save here takes effect on auth.war's very
next request, no restart of either webapp. A value blank/unset here just means "no override";
auth.war falls back to its own `web.xml`/system-property default exactly as before this existed.
The file write is best-effort: if it fails (e.g. a manual install skipped the
`/etc/nspawnmgr/auth-live/` setup in [§5](#5-installing-nspawnmgr)), the database save still
succeeds and a warning is logged — it doesn't block the rest of the settings update.

#### Guacamole section (conditional)

If Guacamole looks reachable (a live probe of `guacamole.base-url`), `/admin/settings` also shows
a structured editor for `guacamole.properties` (at `nspawnmgr.guacamole.home`): individual fields
for `guacd-hostname`/`guacd-port`/`guacd-ssl`, plus a database-type selector (MySQL/MariaDB or
PostgreSQL) that reveals every field the corresponding `guacamole-auth-jdbc` extension supports —
connection, SSL/TLS, password policy, per-connection concurrency limits, external-authentication
integration, and access-window enforcement. Field labels and help text are sourced directly from
the [Apache Guacamole manual](https://guacamole.apache.org/doc/gug/configuring-guacamole.html)
([MySQL](https://guacamole.apache.org/doc/gug/mysql-auth.html) /
[PostgreSQL](https://guacamole.apache.org/doc/gug/postgresql-auth.html) auth extension pages), not
invented locally.

Loading the page reads the existing file and pre-fills every field, including any already-set
password (rendered in a standard masked `<input type="password">`, same as changing a saved
credential anywhere else in this app — not plaintext-visible on screen, but note this is a
deliberate design choice: unlike the rest of `/admin/settings`, which keeps secrets out of the
live-edit surface entirely, this editor's whole point is to let an admin see and adjust an
existing Guacamole DB configuration without SSHing in). Saving only touches the keys documented
above: it clears whichever database extension's keys you *didn't* select (so the file doesn't
accumulate stale config from a previous choice) and preserves any other key already in the file
untouched (e.g. a hand-added extension's own settings). Saving does **not** restart Tomcat —
Guacamole won't see the change until you do (`sudo systemctl restart tomcat9`).

#### Settings report

"Download settings report" produces a plain-text file with every setting on the page (plus the
database wizard's persisted `DB_URL`/`DB_USERNAME`/`DB_VENDOR` and the Guacamole structured
editor's current file values), grouped the same way as the page itself. Every password-shaped
value — `ssh.password`, `DB_PASSWORD`, any Guacamole `*-password` key — is replaced with a literal
`********`: the report confirms *that* a value is set, never what it is.

#### Restart Tomcat

Fires `sudo systemctl restart --no-block tomcat9` over the same sudo-capable SSH account and
NOPASSWD sudoers grant every other routine privileged operation already uses (see
[§3](#3-the-sudo-capable-ssh-account)) — the `.deb` ships the required wrapper script
(`/usr/lib/nspawnmgr/privileged/nspawnmgr-restart-tomcat.sh`) and sudoers entry automatically. A
manual (non-`.deb`) install needs to add both by hand: copy the script from
`packaging/nspawnmgr-deb/privileged-scripts/nspawnmgr-restart-tomcat.sh` into
`nspawn.privileged-scripts-dir`, then add its path to the `NSPAWNMGR_NOPASSWD` alias in
`/etc/sudoers.d/nspawnmgr_exec` (validate with `visudo -cf` before trusting it).

The restart is fired asynchronously (`--no-block` queues the systemd job and returns almost
instantly) rather than waited on — waiting wouldn't work anyway, since the very request asking for
the restart is served by the Tomcat instance about to go down. After clicking the button and
confirming, the page waits 5 seconds, clears the session cookie client-side, and reloads — landing
back on the login page once the (by-then-restarted) app sees the missing cookie, the same way it
would for any other expired session.

## 10. Verifying the deployment

**On a `.deb` install** (self-hosted — [§1](#1-architecture-overview)): `<hostname>:<port>` below
means the port the install printed during `postinst` (8080 unless already taken), and
`machinectl list`/log-checking commands need `sudo machinectl shell nspawnmgr <command>` — Tomcat,
`guacd`, and both WARs' logs all live inside that machine, not on the host. On a manual, Option B
(host-Tomcat) install, everything below runs directly on the host instead, same as it always has.

1. Confirm the self-hosted `nspawnmgr` machine is up: `sudo machinectl list` on the host should
   show it `running` (and, once you've been through §4, its database machine too). Inside it,
   `guacd` and Tomcat (`nspawnmgr.war` + `guacamole.war` + `auth.war`) should both be running.
2. Visit `http://<hostname>:<port>/auth/login` directly and confirm you can log in with the
   initial account created during §4's wizard (and, if configured, that an account outside
   `auth.required-group`/`smb.required-share` is correctly refused).
3. Visit `http://<hostname>:<port>/nspawnmgr/` with no cookie present — you should be redirected
   to the `auth` login page and, after logging in, back to nspawnmgr. The `nspawnmgr`/database
   machines should already show up as ordinary containers in the container list at this point —
   the wizard registers them directly, no login needed first.
4. Create a new container through nspawnmgr's UI and confirm it actually boots (`sudo machinectl
   list` on the host should show it) and that a Guacamole connection appears for it.
5. Check nspawnmgr's own "View log" page (once it's at least far enough along to serve pages), or
   `sudo machinectl shell nspawnmgr journalctl -u tomcat9` for lower-level failures, if anything
   above fails — most first-deployment issues are a hostname/cookie mismatch (§8) or the sudo
   account (§3) not actually having sudo/SSH access configured correctly.

## 11. Day-2 operations

- **Logs**: `<tomcat-dir>/logs/catalina.out.<date>.log` for the single Tomcat instance (nspawnmgr,
  Guacamole, and auth all log there); `journalctl -u guacd` for Guacamole's proxy daemon — on a
  `.deb` (self-hosted) install both live *inside* the `nspawnmgr` machine (`sudo machinectl shell
  nspawnmgr <command>`), not on the host. The `.deb` wires Tomcat's own stdout/stderr through
  `rotatelogs` (`apache2-utils`) via `tomcat9.service`'s `ExecStart`, producing a new dated file
  daily — unlike a plain `catalina.sh start`, this package's `tomcat9.service` runs `catalina.sh
  run` directly, which never produces an undated `catalina.out` on its own (that's only what you'd
  see running Tomcat interactively, e.g. the dev stack). Every logged-in user can view the last 100
  lines and the full current log at nspawnmgr's own "View log" page; admins can also browse and
  delete individual rotated-out days from there.
- **Restarting**: restart Tomcat after changing any `-D`/env var configuration — none of it is
  hot-reloaded, and since all three webapps share the one instance, restarting it restarts all
  three together. Restart just `guacd` after changing `guacd-hostname`/`guacd-port` in
  `guacamole.properties`.
- **Backups**: back up nspawnmgr's own database (container/user metadata), Guacamole's own
  database (connection history/parameters), and `/var/lib/machines` (container root
  filesystems) separately — they're independent stores with no cross-referential integrity
  enforced beyond what nspawnmgr manages at the application level.
- **Rotating `APP_SECRET_KEY`**: there's no built-in re-encryption tool; treat this as a
  break-glass, plan-ahead operation, not something to change casually on a running system.
- **Pending container requests** (admin-approval mode only): show up at
  `/requests`. `DENIED` is currently a terminal state — there's no resubmission
  affordance, the requesting user has to create a new container from scratch.
