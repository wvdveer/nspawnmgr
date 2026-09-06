-- DesktopManager gets an explicit X11/Wayland split (v0.5.0) - NONE/KDE_STANDARD/XFCE4 rename to
-- NONE_X11/KDE_STANDARD_X11/XFCE4_X11 (existing containers keep the same real desktop manager,
-- just under its now-explicit X11 name), alongside new NONE_WAYLAND/KDE_STANDARD_WAYLAND/
-- XFCE4_WAYLAND values a container can newly choose. GNOME is untouched - already excluded from
-- the selectable UI, out of scope for this split. desktop_manager stays a plain VARCHAR(20), no
-- CHECK constraint to update.
UPDATE containers SET desktop_manager = 'NONE_X11' WHERE desktop_manager = 'NONE';
UPDATE containers SET desktop_manager = 'KDE_STANDARD_X11' WHERE desktop_manager = 'KDE_STANDARD';
UPDATE containers SET desktop_manager = 'XFCE4_X11' WHERE desktop_manager = 'XFCE4';

-- Per-template install-command/pre-fetch overrides for the three new Wayland DesktopManager
-- values, same override-then-package-manager-default pattern as every existing desktop-manager
-- pair above (see Template.java's own javadoc). NONE_WAYLAND gets a pair too, unlike NONE_X11 -
-- unlike bare X11, a Wayland session always needs some compositor running (a minimal one, e.g.
-- cage), so it has a real install requirement.
ALTER TABLE templates ADD COLUMN install_kde_standard_wayland_command VARCHAR(1000);
ALTER TABLE templates ADD COLUMN kde_standard_wayland_pre_download_packages VARCHAR(1000);
ALTER TABLE templates ADD COLUMN install_xfce4_wayland_command VARCHAR(1000);
ALTER TABLE templates ADD COLUMN xfce4_wayland_pre_download_packages VARCHAR(1000);
ALTER TABLE templates ADD COLUMN install_none_wayland_command VARCHAR(1000);
ALTER TABLE templates ADD COLUMN none_wayland_pre_download_packages VARCHAR(1000);
