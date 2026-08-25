package com.nspawnmgr.domain;

/** QEMU pointer device, chosen at VM creation (see Container#getQemuPointerDevice) - baked into the
 *  VM's systemd unit and only changeable by recreating the VM, same posture as {@link
 *  Container#getQemuNicModel}. A closed enum rather than free text: this value gets spliced
 *  directly into the unit file's {@code ExecStart=} line by nspawnmgr-qemu-write-unit.sh.
 *
 *  <p>USB_TABLET reports absolute coordinates, which keeps the guest cursor in sync with VNC's own
 *  absolute host cursor position - PS2's relative-motion reporting drifts out of alignment over
 *  time. But DOS-family guests have no USB driver stack of their own and rely entirely on the
 *  legacy PS/2 controller for mouse input, so PS2 stays the default to avoid regressing them. */
public enum QemuPointerDevice {
    PS2, USB_TABLET;

    /** The exact extra QEMU flags to splice into ExecStart, or "" for PS2 (no change from the
     *  machine's own default PS/2 controller). */
    public String qemuArg() {
        return switch (this) {
            case PS2 -> "";
            case USB_TABLET -> "-usb -device usb-tablet";
        };
    }

    /** Label used on the New QEMU form's dropdown. */
    public String label() {
        return switch (this) {
            case PS2 -> "PS/2 mouse (default, required for FreeDOS and other text-mode OSes)";
            case USB_TABLET -> "USB tablet (fixes VNC cursor drift for GUI guests, e.g. KolibriOS)";
        };
    }
}
