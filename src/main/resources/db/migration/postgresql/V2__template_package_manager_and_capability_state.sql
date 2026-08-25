-- Both changes below landed together during 0.2.0 development (nothing has shipped with just one
-- of them, so there's no upgrade path to preserve separately - see
-- feedback_migrations_immutable_after_0.1.0_release.md for why V1 itself can no longer be edited
-- this way).

-- A podman-backed template (see domain/ContainerBackend.PODMAN, added alongside this migration)
-- has no meaningful nspawnmgr-managed package manager concept - especially a freshly "New
-- Pod"-pulled arbitrary image. See Template.packageManager's own comment.
ALTER TABLE templates ALTER COLUMN package_manager DROP NOT NULL;

-- Replaces templates.ssh_preinstalled/rdp_capable/vnc_capable (booleans) with a shared three-state
-- ssh_state/rdp_state/vnc_state (PREINSTALLED/CAPABLE/NOT_CAPABLE) - see
-- domain/TemplateFeatureState.java. CAPABLE/NOT_CAPABLE map 1:1 from the old true/false; the new
-- PREINSTALLED state for RDP/VNC (and NOT_CAPABLE for SSH) has no old equivalent, so existing rows
-- simply can't have started in those states. DEFAULT 'CAPABLE' matches Template's own Java field
-- default, and (unlike the app itself, which always writes every mapped column) lets raw SQL
-- inserts that don't know about these columns yet - e.g. nspawnmgr-install-template.sh,
-- real-lifecycle-test.sh's seed row - keep working without listing them explicitly.
ALTER TABLE templates ADD COLUMN ssh_state VARCHAR(20);
UPDATE templates SET ssh_state = CASE WHEN ssh_preinstalled THEN 'PREINSTALLED' ELSE 'CAPABLE' END;
ALTER TABLE templates ALTER COLUMN ssh_state SET NOT NULL;
ALTER TABLE templates ALTER COLUMN ssh_state SET DEFAULT 'CAPABLE';
ALTER TABLE templates DROP COLUMN ssh_preinstalled;

ALTER TABLE templates ADD COLUMN rdp_state VARCHAR(20);
UPDATE templates SET rdp_state = CASE WHEN rdp_capable THEN 'CAPABLE' ELSE 'NOT_CAPABLE' END;
ALTER TABLE templates ALTER COLUMN rdp_state SET NOT NULL;
ALTER TABLE templates ALTER COLUMN rdp_state SET DEFAULT 'CAPABLE';
ALTER TABLE templates DROP COLUMN rdp_capable;

ALTER TABLE templates ADD COLUMN vnc_state VARCHAR(20);
UPDATE templates SET vnc_state = CASE WHEN vnc_capable THEN 'CAPABLE' ELSE 'NOT_CAPABLE' END;
ALTER TABLE templates ALTER COLUMN vnc_state SET NOT NULL;
ALTER TABLE templates ALTER COLUMN vnc_state SET DEFAULT 'CAPABLE';
ALTER TABLE templates DROP COLUMN vnc_capable;
