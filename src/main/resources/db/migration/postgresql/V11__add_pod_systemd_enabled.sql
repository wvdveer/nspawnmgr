-- PODMAN-only: whether to run a pod with podman's own --systemd=always support (see
-- Container.podSystemdEnabled's own javadoc, and nspawnmgr-podman-create-container.sh) -
-- confirmed live (yoga, 2026-09-03) that a pod's Command being an init system (e.g. /sbin/init,
-- from a template converted from an nspawn one) fails hard without this, since podman's own
-- systemd auto-detection never sees past this app's `sh -c` command wrapping. Off by default -
-- meaningless (harmlessly stored) for SYSTEMD_NSPAWN, same posture as pod_command.
ALTER TABLE containers ADD COLUMN pod_systemd_enabled BOOLEAN NOT NULL DEFAULT FALSE;
