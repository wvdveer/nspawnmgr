package com.nspawnmgr.cli;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerPortMapping;
import com.nspawnmgr.domain.PrivateUsersMode;

import java.util.List;

/**
 * Builds `.nspawn` file content shared by the real (SSH) and fake (local file) filesystem
 * provisioners, so the two never drift on how SSH/RDP/custom port forwards are rendered.
 */
public final class NspawnSettingsRenderer {

    private NspawnSettingsRenderer() {
    }

    /** All containers share one bridge (see packaging's 70-nspawnmgr-bridge.netdev/.network) rather
     *  than each getting an isolated point-to-point veth - lets dnsmasq bind one fixed interface
     *  instead of glob-matching N per-container links, and is what lets other backends (podman/QEMU)
     *  resolve nspawn containers by name on the same L2 segment. */
    private static final String BRIDGE_NAME = "nspawnbr0";

    /** Fixed, non-admin-configurable convention - same posture as the bridge name/subnet above. */
    private static final String ISO_MOUNT_POINT_CONTAINER_PATH = "/mnt/cdrom";

    public static String render(Container container, List<ContainerPortMapping> customMappings) {
        // PrivateUsers=: left unset (null on the template), a container just follows whatever
        // systemd-nspawn's own default is on the host (--private-users=pick on current systemd).
        // Confirmed live on yoga/fed1 this can break PAM helpers that shell out to unix_chkpwd:
        // `pick`'s idmapped root-fs mount shifts container root to an arbitrary, non-zero host UID,
        // and unix_chkpwd's /etc/shadow read then fails with a plain EACCES even though it runs as
        // genuine root with CAP_DAC_OVERRIDE/CAP_DAC_READ_SEARCH present - the kernel's
        // capability-vs-inode-ownership check doesn't survive the idmap translation for that case.
        // A template can opt into PrivateUsersMode.IDENTITY (see Template.privateUsersMode) to avoid
        // that specific shift while still keeping a real per-container user namespace - fedora-minimal
        // does this since it's the confirmed-affected case; other templates stay on the host default
        // unless/until they need it too.
        StringBuilder content = new StringBuilder("[Network]\nBridge=" + BRIDGE_NAME + "\n");
        PrivateUsersMode privateUsersMode = container.getTemplate().getPrivateUsersMode();
        if (privateUsersMode != null) {
            content.insert(0, "[Exec]\nPrivateUsers=" + privateUsersMode.nspawnValue() + "\n");
        }
        for (ContainerPortMapping mapping : customMappings) {
            content.append("Port=").append(mapping.getProtocol().name().toLowerCase())
                    .append(':').append(mapping.getHostPort())
                    .append(':').append(mapping.getContainerPort())
                    .append('\n');
        }
        if (container.getMountedIso() != null) {
            // A static [Files] BindReadOnly= - not a live `machinectl bind` - so this is exactly
            // the same "rewritten immediately, takes effect on next (re)start" contract as the
            // Port= lines above, and the mounted-ISO setting genuinely persists across restarts
            // (nothing here ever un-configures it - only an explicit eject does).
            content.append("[Files]\nBindReadOnly=").append(isoHostMountPoint(container))
                    .append(':').append(ISO_MOUNT_POINT_CONTAINER_PATH).append('\n');
        }
        return content.toString();
    }

    /** Host-side directory the configured ISO is loop-mounted at (see ContainerIsoMounter) - one
     *  fixed, deterministic path per container, so this class and the mounter agree on it without
     *  either needing to persist it anywhere. */
    public static String isoHostMountPoint(Container container) {
        return "/var/lib/nspawnmgr/iso-mounts/" + container.getName();
    }
}
