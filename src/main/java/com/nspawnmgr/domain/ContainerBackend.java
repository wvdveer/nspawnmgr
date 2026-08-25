package com.nspawnmgr.domain;

/**
 * Which container/VM technology a {@link Template} boots under. A QEMU VM can be created either
 * from an empty disk + an ISO (see Container#getMountedIso), or cloned from an existing
 * QEMU-backed Template's own qcow2 disk - both paths live in
 * ProvisioningService#provisionQemu/#createPendingQemu.
 */
public enum ContainerBackend {
    SYSTEMD_NSPAWN, PODMAN, QEMU;

    /**
     * Subdirectory under TEMPLATES_DIR holding this backend's templates (e.g.
     * TEMPLATES_DIR/nspawn/&lt;name&gt;.tar.gz, TEMPLATES_DIR/qemu/&lt;name&gt;.qcow2). Deliberately
     * no {@code default} case: adding a backend must extend this too.
     */
    public String templateSubdirectory() {
        return switch (this) {
            case SYSTEMD_NSPAWN -> "nspawn";
            case PODMAN -> "podman";
            case QEMU -> "qemu";
        };
    }
}
