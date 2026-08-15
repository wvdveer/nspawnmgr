package com.nspawnmgr.cli;

/**
 * Loop-mounts/unmounts an ISO file on the host at a container's fixed mount point
 * (NspawnSettingsRenderer.isoHostMountPoint) - purely host-side filesystem operations. Getting the
 * mount into the container itself is a static [Files] BindReadOnly= line in its .nspawn settings
 * (see NspawnSettingsRenderer), not anything this interface does directly - same "rewritten
 * immediately, takes effect on next (re)start" contract as custom port mappings, so mount/unmount
 * here don't require the container to be running, and the setting persists across restarts.
 */
public interface ContainerIsoMounter {

    void mount(String machineName, String isoHostSourcePath);

    void unmount(String machineName);
}
