package com.nspawnmgr.cli;

import com.nspawnmgr.domain.ContainerBackend;

/**
 * SYSTEMD_NSPAWN: loop-mounts/unmounts an ISO file on the host at a container's fixed mount point
 * (NspawnSettingsRenderer.isoHostMountPoint) - purely host-side filesystem operations. Getting the
 * mount into the container itself is a static [Files] BindReadOnly= line in its .nspawn settings
 * (see NspawnSettingsRenderer), not anything this interface does directly - same "rewritten
 * immediately, takes effect on next (re)start" contract as custom port mappings, so mount/unmount
 * here don't require the container to be running, and the setting persists across restarts.
 *
 * <p>QEMU: no loop-mount at all - {@code -cdrom} reads the {@code .iso} file directly. If the VM is
 * currently RUNNING, mount/unmount here live-swap the media via HMP ({@link
 * ContainerCliExecutor#changeQemuCdrom}/{@link ContainerCliExecutor#ejectQemuCdrom}) so the change
 * takes effect immediately; either way, {@link
 * com.nspawnmgr.cli.ContainerFilesystemProvisioner#writeQemuUnit} separately persists the choice for
 * the VM's *next* boot (called by the same caller - see ContainerLifecycleService.rewriteSettings).
 */
public interface ContainerIsoMounter {

    void mount(String machineName, ContainerBackend backend, String isoHostSourcePath);

    void unmount(String machineName, ContainerBackend backend);
}
