package com.nspawnmgr.domain;

/** QEMU {@code -cpu} model, chosen at VM creation (see Container#getQemuCpuModel) - baked into the
 *  VM's systemd unit and only changeable by recreating the VM, same posture as {@link
 *  Container#getQemuDiskSizeGb}. A closed enum rather than free text: this value gets spliced
 *  directly into the unit file's {@code ExecStart=} line by nspawnmgr-qemu-write-unit.sh. */
public enum QemuCpuModel {
    QEMU64, HOST, MAX, KVM64;

    /** The exact {@code -cpu} token QEMU expects. */
    public String qemuArg() {
        return name().toLowerCase();
    }

    /** Label used on the New QEMU form's dropdown. */
    public String label() {
        return switch (this) {
            case QEMU64 -> "qemu64 (default)";
            case HOST -> "host (best performance, requires KVM)";
            case MAX -> "max";
            case KVM64 -> "kvm64";
        };
    }
}
