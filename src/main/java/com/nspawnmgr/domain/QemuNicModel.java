package com.nspawnmgr.domain;

/** QEMU NIC device model, chosen at VM creation (see Container#getQemuNicModel) - baked into the
 *  VM's systemd unit and only changeable by recreating the VM, same posture as {@link
 *  Container#getQemuDiskSizeGb}. A closed enum rather than free text: this value gets spliced
 *  directly into the unit file's {@code ExecStart=} line by nspawnmgr-qemu-write-unit.sh. */
public enum QemuNicModel {
    VIRTIO_NET_PCI, E1000, RTL8139, PCNET;

    /** The exact {@code -device}/{@code -net nic,model=} token QEMU expects. */
    public String qemuArg() {
        return switch (this) {
            case VIRTIO_NET_PCI -> "virtio-net-pci";
            case E1000 -> "e1000";
            case RTL8139 -> "rtl8139";
            case PCNET -> "pcnet";
        };
    }

    /** Label used on the New QEMU form's dropdown. */
    public String label() {
        return switch (this) {
            case VIRTIO_NET_PCI -> "virtio-net-pci (default, best performance, needs virtio drivers)";
            case E1000 -> "e1000 (Intel, broad compatibility)";
            case RTL8139 -> "rtl8139 (Realtek, older OS compatibility)";
            case PCNET -> "pcnet (AMD PCnet - required by FreeDOS and similar)";
        };
    }
}
