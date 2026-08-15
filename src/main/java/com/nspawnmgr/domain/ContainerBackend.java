package com.nspawnmgr.domain;

/**
 * Which container/VM technology a {@link Template} boots under. Only SYSTEMD_NSPAWN exists today;
 * podman and QEMU are planned as additional backends in a future release, at which point
 * ContainerCliExecutor/ContainerFilesystemProvisioner will need per-backend implementations —
 * every template today is implicitly a systemd-nspawn template.
 */
public enum ContainerBackend {
    SYSTEMD_NSPAWN;

    /**
     * Subdirectory under TEMPLATES_DIR holding this backend's templates (e.g.
     * TEMPLATES_DIR/nspawn/&lt;name&gt;.tar.gz) — podman/qemu get their own sibling subdirectories
     * once those backends exist. Deliberately no {@code default} case: adding a backend must
     * extend this too.
     */
    public String templateSubdirectory() {
        return switch (this) {
            case SYSTEMD_NSPAWN -> "nspawn";
        };
    }
}
