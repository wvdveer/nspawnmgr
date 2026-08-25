-- Container.backend (see domain/Container.java's own comment) - tracked on the container itself,
-- not just derived from its template, since template is null for a discovered/ad-hoc row (same
-- reasoning as the existing packageManager admin-supplied-fallback field). Backfilled from the
-- linked template's own backend where one exists; SYSTEMD_NSPAWN otherwise (every pre-podman
-- discovered container and EXTERNAL host, which has no backend concept at all).
ALTER TABLE containers ADD COLUMN backend VARCHAR(20);
UPDATE containers SET backend = (
    SELECT t.backend FROM templates t WHERE t.id = containers.template_id
);
UPDATE containers SET backend = 'SYSTEMD_NSPAWN' WHERE backend IS NULL;
ALTER TABLE containers ALTER COLUMN backend SET NOT NULL;
ALTER TABLE containers ALTER COLUMN backend SET DEFAULT 'SYSTEMD_NSPAWN';
