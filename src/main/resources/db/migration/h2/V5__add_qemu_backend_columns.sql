-- Container.qemuVncPort (see domain/Container.java's own comment) - the TCP port on nspawnbr0's
-- own address (10.100.0.1) a QEMU VM's hypervisor VNC listener binds to, allocated once at
-- creation from the admin-configured range below.
ALTER TABLE containers ADD COLUMN qemu_vnc_port INT;

-- Container.qemuDiskSizeGb - one-time qemu-img create input, persisted only so admin-approval mode
-- has it available when provisioning is kicked off later from just a container id.
ALTER TABLE containers ADD COLUMN qemu_disk_size_gb INT;

-- SettingsService.qemuVncPortRangeStart()/End() - admin-configurable range QEMU VNC ports are
-- allocated from. Both null means "use the 5900-5999 default" (see SettingsService).
ALTER TABLE app_settings ADD COLUMN qemu_vnc_port_range_start INT;
ALTER TABLE app_settings ADD COLUMN qemu_vnc_port_range_end INT;
