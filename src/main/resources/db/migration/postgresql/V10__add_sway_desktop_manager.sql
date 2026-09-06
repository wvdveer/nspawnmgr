-- SWAY is a new bare DesktopManager constant (Wayland-only, no X11 counterpart - see
-- DesktopManager's own javadoc for why). Per-template install-command/pre-fetch override pair,
-- same pattern as every other desktop-manager pair (Template.java's own javadoc).
ALTER TABLE templates ADD COLUMN install_sway_command VARCHAR(1000);
ALTER TABLE templates ADD COLUMN sway_pre_download_packages VARCHAR(1000);
