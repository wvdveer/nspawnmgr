-- Container.qemuPointerDevice - PS2 (default, required for DOS-family guests with no USB driver
-- stack) or USB_TABLET (absolute positioning, fixes VNC cursor drift for GUI guests like
-- KolibriOS), chosen at VM creation (New QEMU page) and baked into the systemd unit. Nullable: an
-- existing QEMU VM (or a row from before this migration) gets NULL, which
-- nspawnmgr-qemu-write-unit.sh treats the same as PS2 (no extra USB flags - matches behavior
-- before this feature existed).
ALTER TABLE containers ADD COLUMN qemu_pointer_device VARCHAR(20);
