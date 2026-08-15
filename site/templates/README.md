# site/templates

`nspawn/` holds tiny placeholder "base template" directory trees, matching the three
`MinimalTemplateFlavor` names (`debian-minimal`/APT, `fedora-minimal`/DNF, `arch-minimal`/PACMAN)
the Templates admin page's independent "Set up X-minimal" buttons register via the admin API on a
fresh dev DB (templates are no longer Flyway-seeded — a real install starts with zero). `alpine-minimal`
is a stale leftover from before Alpine was dropped entirely (its OpenRC-based rootfs has no
systemd/D-Bus, which every in-container command in this app requires via `systemd-run --machine=` -
confirmed live, "Failed to connect to bus" permanently, not a transient boot race) - kept around only
because deleting it isn't worth the churn, not because anything still registers it. The `nspawn/`
subdirectory mirrors `TEMPLATES_DIR`'s real, production layout (one subdirectory per
`ContainerBackend` — `podman/`/`qemu/` join it once those backends exist), even though these dev
stubs stay plain directories rather than real `.tar.gz` files (see below).

These only exercise the copy/`.nspawn`-writing mechanics of `ContainerFilesystemProvisioner` on a
Windows dev machine — they are **not** real, bootable Linux root filesystems, and unlike the real
`RealContainerFilesystemProvisioner` they are **not** gzipped tars either (those are far too large,
and pointless to fake, to keep in a git repo). Provisioning against these with the `dev` Spring
profile (`FakeContainerCliExecutor`) works because the fake never actually boots anything; it just
records what commands it would have run to `%TEMP%\nspawnmgr-dev\fake-machinectl.log`.

Real Linux rootfs templates (e.g. via `debootstrap`) must be prepared separately on the actual
deployment host as a machinectl import-tar-compatible gzipped tar and pointed at via
`TEMPLATES_DIR/nspawn/<name>.tar.gz` — see `docs/administrator-guide.md`'s "Container templates"
section.
