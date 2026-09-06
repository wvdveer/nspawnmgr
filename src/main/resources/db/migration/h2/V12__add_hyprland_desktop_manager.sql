-- HYPRLAND is a new bare DesktopManager constant (Wayland-only, no X11 counterpart - see
-- DesktopManager's own javadoc). Per-template install-command/pre-fetch override pair, same
-- pattern as every other desktop-manager pair (Template.java's own javadoc) - mirrors SWAY's own
-- V10 migration exactly.
ALTER TABLE templates ADD COLUMN install_hyprland_command VARCHAR(1000);
ALTER TABLE templates ADD COLUMN hyprland_pre_download_packages VARCHAR(1000);
