-- Container.qemuCpuModel/qemuCpuCount/qemuMemoryMb/qemuNicModel - configurable QEMU launch
-- hardware, chosen at VM creation (New QEMU page) and baked into the systemd unit. All nullable:
-- an existing QEMU VM (or a row from before this migration) gets NULL, which
-- nspawnmgr-qemu-write-unit.sh treats as "use the previous hardcoded defaults" (no -cpu/-smp flag,
-- 2048 MB, virtio-net-pci).
ALTER TABLE containers ADD COLUMN qemu_cpu_model VARCHAR(20);
ALTER TABLE containers ADD COLUMN qemu_cpu_count INT;
ALTER TABLE containers ADD COLUMN qemu_memory_mb INT;
ALTER TABLE containers ADD COLUMN qemu_nic_model VARCHAR(20);
