-- Keystroke Button Panel feature: a global, admin-curated list of key combinations (Ctrl+Alt+
-- Delete and similar) shown as buttons on a container/host's session page, sent directly to the
-- live Guacamole client - see KeySequence's own javadoc for why this is global rather than
-- per-container, and Container.keystrokeButtonPanelPlacement for the per-container/host opt-in.
CREATE TABLE key_sequences (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    label VARCHAR(255) NOT NULL,
    ctrl BOOLEAN NOT NULL,
    alt BOOLEAN NOT NULL,
    shift BOOLEAN NOT NULL,
    super_key BOOLEAN NOT NULL,
    main_keysym INT NOT NULL,
    sort_order INT NOT NULL
);

ALTER TABLE containers ADD COLUMN keystroke_button_panel_placement VARCHAR(20) NOT NULL DEFAULT 'NONE';
