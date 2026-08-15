package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.ContainerIsoMounter;
import com.nspawnmgr.cli.ContainerOutboundAccessManager;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerOutboundAllowlistEntry;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.PortMappingProtocol;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerOutboundAllowlistRepository;
import com.nspawnmgr.repository.ContainerPortMappingRepository;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ContainerLifecycleService {

    private final ContainerRepository containerRepository;
    private final ContainerCredentialRepository containerCredentialRepository;
    private final ContainerOutboundAllowlistRepository outboundAllowlistRepository;
    private final ContainerPortMappingRepository portMappingRepository;
    private final UserRepository userRepository;
    private final ContainerCliExecutor cliExecutor;
    private final ContainerFilesystemProvisioner filesystemProvisioner;
    private final ContainerOutboundAccessManager outboundAccessManager;
    private final ContainerIsoMounter isoMounter;
    private final PackageCacheService packageCacheService;
    private final GuacamoleAdminClient guacamoleAdminClient;
    private final ShareService shareService;

    public ContainerLifecycleService(ContainerRepository containerRepository,
                                      ContainerCredentialRepository containerCredentialRepository,
                                      ContainerOutboundAllowlistRepository outboundAllowlistRepository,
                                      ContainerPortMappingRepository portMappingRepository,
                                      UserRepository userRepository,
                                      ContainerCliExecutor cliExecutor,
                                      ContainerFilesystemProvisioner filesystemProvisioner,
                                      ContainerOutboundAccessManager outboundAccessManager,
                                      ContainerIsoMounter isoMounter,
                                      PackageCacheService packageCacheService,
                                      GuacamoleAdminClient guacamoleAdminClient,
                                      ShareService shareService) {
        this.containerRepository = containerRepository;
        this.containerCredentialRepository = containerCredentialRepository;
        this.outboundAllowlistRepository = outboundAllowlistRepository;
        this.portMappingRepository = portMappingRepository;
        this.userRepository = userRepository;
        this.cliExecutor = cliExecutor;
        this.filesystemProvisioner = filesystemProvisioner;
        this.outboundAccessManager = outboundAccessManager;
        this.isoMounter = isoMounter;
        this.packageCacheService = packageCacheService;
        this.guacamoleAdminClient = guacamoleAdminClient;
        this.shareService = shareService;
    }

    @Transactional
    public void start(Container container) {
        requireManaged(container);
        cliExecutor.start(container.getName());
        resyncOutboundAccess(container);
        // Not RUNNING yet — ContainerReadinessPollingService confirms SSH (and RDP, if enabled) are
        // actually reachable before making that call.
        container.setState(ContainerState.BOOTING);
        container.touch();
        containerRepository.save(container);
    }

    @Transactional
    public void stopGraceful(Container container) {
        requireManaged(container);
        cliExecutor.stopGraceful(container.getName());
        container.setState(ContainerState.STOPPED);
        container.touch();
        containerRepository.save(container);
    }

    @Transactional
    public void stopForce(Container container) {
        requireManaged(container);
        cliExecutor.stopForce(container.getName());
        container.setState(ContainerState.STOPPED);
        container.touch();
        containerRepository.save(container);
    }

    /** Clean in-place restart (machinectl reboot) - only valid from RUNNING. Unlike {@link #start},
     *  doesn't resync outbound-access rules: a reboot doesn't tear down and recreate the
     *  container's veth interface, so the existing firewall rules stay valid as-is. */
    @Transactional
    public void restart(Container container) {
        requireManaged(container);
        if (container.getState() != ContainerState.RUNNING) {
            throw new IllegalStateException("Container must be RUNNING to restart");
        }
        cliExecutor.restart(container.getName());
        // Not RUNNING yet — ContainerReadinessPollingService confirms SSH (and RDP, if enabled) are
        // actually reachable again before making that call, same handoff as start().
        container.setState(ContainerState.BOOTING);
        container.touch();
        containerRepository.save(container);
    }

    public MachineStatus status(Container container) {
        return cliExecutor.status(container.getName());
    }

    /** Freezes every process in the container's cgroup in place (systemctl freeze) - only valid
     *  from RUNNING, since pausing anything else has no sensible meaning. */
    @Transactional
    public void pause(Container container) {
        requireManaged(container);
        if (container.getState() != ContainerState.RUNNING) {
            throw new IllegalStateException("Container must be RUNNING to pause");
        }
        cliExecutor.pause(container.getName());
        container.setState(ContainerState.PAUSED);
        container.touch();
        containerRepository.save(container);
    }

    /** Reverses {@link #pause} (systemctl thaw) - only valid from PAUSED. */
    @Transactional
    public void resume(Container container) {
        requireManaged(container);
        if (container.getState() != ContainerState.PAUSED) {
            throw new IllegalStateException("Container must be PAUSED to resume");
        }
        cliExecutor.resume(container.getName());
        container.setState(ContainerState.RUNNING);
        container.touch();
        containerRepository.save(container);
    }

    @Transactional
    public void setDescription(Container container, String description) {
        container.setDescription(description);
        container.touch();
        containerRepository.save(container);
    }

    /**
     * Sets the admin-supplied package-manager fallback for a MANAGED container with no template
     * (see Container.packageManager's own javadoc) - a templated container's package manager always
     * comes from its template instead, so this rejects one that has one, rather than silently
     * setting a value {@link Container#effectivePackageManager()} would then just ignore.
     */
    @Transactional
    public void setPackageManager(Container container, PackageManager packageManager) {
        requireManaged(container);
        if (container.getTemplate() != null) {
            throw new IllegalStateException(container.getName() + " has a template - its package manager comes from that instead.");
        }
        container.setPackageManager(packageManager);
        container.touch();
        containerRepository.save(container);
    }

    /**
     * Persists the outbound-access flag; if the container is currently running, applies the
     * firewall change immediately (unlike custom port mappings, this doesn't need a restart).
     */
    @Transactional
    public void setOutboundEnabled(Container container, boolean enabled) {
        requireManaged(container);
        container.setOutboundEnabled(enabled);
        container.touch();
        containerRepository.save(container);
        resyncOutboundAccess(container);
    }

    /**
     * Sets a MANAGED container's host-boot-time settings - see {@link ContainerCliExecutor
     * #getBootSettings}'s own javadoc for why these live entirely on the host (via systemctl),
     * never in nspawnmgr's own database. {@code requiresContainerName} is only meaningful when
     * {@code autoStart} is true - forced to null otherwise, rather than leaving nspawnmgr's own UI
     * showing a "requires X" setting for a machine that won't even auto-start to begin with.
     * Validated against a real, currently-known container name (and rejects requiring itself)
     * before being pushed to the host, so a typo can't silently wire up a broken systemd
     * dependency.
     */
    @Transactional
    public void setBootSettings(Container container, boolean autoStart, String requiresContainerName) {
        requireManaged(container);
        String requested = autoStart ? requiresContainerName : null;
        String requires = (requested == null || requested.isBlank()) ? null : requested;
        if (requires != null) {
            if (requires.equals(container.getName())) {
                throw new IllegalArgumentException("A machine can't require itself");
            }
            containerRepository.findByName(requires)
                    .orElseThrow(() -> new IllegalArgumentException("No such machine: '" + requires + "'"));
        }
        cliExecutor.setAutoStart(container.getName(), autoStart);
        cliExecutor.setRequiresMachine(container.getName(), requires);
    }

    /**
     * Adds a specific IP+port+protocol an owner wants their otherwise-blocked container to still
     * reach (e.g. 127.0.0.1 to talk to another co-located container/service). No-op while
     * outboundEnabled is true - everything is already reachable in that case - but still
     * persisted so it takes effect the moment outbound access is disabled.
     */
    @Transactional
    public ContainerOutboundAllowlistEntry addOutboundAllowlistEntry(Container container, String destinationHost,
                                                                      int destinationPort, PortMappingProtocol protocol) {
        requireManaged(container);
        ContainerOutboundAllowlistEntry entry = outboundAllowlistRepository.save(
                new ContainerOutboundAllowlistEntry(container, destinationHost, destinationPort, protocol));
        resyncOutboundAccess(container);
        return entry;
    }

    @Transactional
    public void removeOutboundAllowlistEntry(Container container, Long entryId) {
        requireManaged(container);
        ContainerOutboundAllowlistEntry entry = outboundAllowlistRepository.findById(entryId)
                .filter(e -> e.getContainer().getId().equals(container.getId()))
                .orElseThrow(() -> new IllegalArgumentException("No such outbound allowlist entry: " + entryId));
        outboundAllowlistRepository.delete(entry);
        resyncOutboundAccess(container);
    }

    public List<ContainerOutboundAllowlistEntry> listOutboundAllowlist(Container container) {
        return outboundAllowlistRepository.findByContainer(container);
    }

    private void resyncOutboundAccess(Container container) {
        if (container.getState() == ContainerState.RUNNING) {
            outboundAccessManager.sync(container.getName(), container.isOutboundEnabled(),
                    outboundAllowlistRepository.findByContainer(container));
        }
    }

    /**
     * Sets which ISO (if any) is configured for this container - a persistent, declarative setting
     * exactly like custom port mappings: rewrites the .nspawn file immediately (a static [Files]
     * BindReadOnly= line - see NspawnSettingsRenderer) but only takes effect the next time the
     * container is (re)started, and stays configured across restarts until explicitly changed or
     * ejected. Doesn't require the container to be running to set. Mounting a different ISO while
     * one's already configured auto-ejects the old one first - swapping discs is one action, not a
     * separate eject-then-mount step.
     */
    @Transactional
    public void mountIso(Container container, CachedPackage iso) {
        requireManaged(container);
        if (container.getMountedIso() != null) {
            isoMounter.unmount(container.getName());
        }
        isoMounter.mount(container.getName(), packageCacheService.hostPath(iso));
        container.setMountedIso(iso);
        container.touch();
        containerRepository.save(container);
        rewriteSettings(container);
    }

    /** No-op (not an error) if nothing is currently configured - matches idempotent-eject expectations. */
    @Transactional
    public void ejectIso(Container container) {
        requireManaged(container);
        if (container.getMountedIso() == null) {
            return;
        }
        isoMounter.unmount(container.getName());
        container.setMountedIso(null);
        container.touch();
        containerRepository.save(container);
        rewriteSettings(container);
    }

    private void rewriteSettings(Container container) {
        // container.getTemplate() may be a lazy proxy tied to whatever session originally loaded it
        // (open-in-view is off) - NspawnSettingsRenderer reads it now (PrivateUsers mode), so a plain
        // lazy proxy from outside this method's own transaction throws LazyInitializationException.
        // Same fix ProvisioningService.provision/TemplateService.createFromMachine already needed for
        // the identical reason.
        Container withTemplate = containerRepository.findByIdWithTemplate(container.getId())
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + container.getId()));
        filesystemProvisioner.writeNspawnSettings(withTemplate, portMappingRepository.findByContainer(container));
    }

    /**
     * Order matters: every step that can still fail (a Guacamole API hiccup, a DB constraint) runs
     * *before* the host-side removal below, not after - confirmed live (arch-xfce, 2026-08-14):
     * with the old ordering, {@code cliExecutor.remove()}/{@code deleteMachineFiles()} ran first,
     * and a later failure (e.g. Guacamole unreachable) rolled back this whole
     * {@code @Transactional} method, including the final {@code containerRepository.delete()} -
     * but a DB rollback can't undo an already-executed {@code machinectl remove} or an already-
     * deleted rootfs. The container's own machine was genuinely gone from the host while its
     * nspawnmgr row (state reverted to whatever it was before, not DELETING) stayed behind
     * looking like nothing had happened. Doing the DB/API cleanup first means a failure there
     * leaves the host machine untouched and the row intact - a consistent "delete didn't happen"
     * state - and the only way to reach the irreversible host-side steps is once nothing else in
     * this method can throw before the final {@code containerRepository.delete()}.
     */
    @Transactional
    public void delete(Container container) {
        requireManaged(container);
        container.setState(ContainerState.DELETING);
        containerRepository.save(container);

        shareService.revokeAllForContainer(container);
        if (container.getGuacSshConnectionId() != null) {
            guacamoleAdminClient.deleteConnection(container.getGuacSshConnectionId());
        }
        if (container.getGuacRdpConnectionId() != null) {
            guacamoleAdminClient.deleteConnection(container.getGuacRdpConnectionId());
        }
        if (container.getGuacVncConnectionId() != null) {
            guacamoleAdminClient.deleteConnection(container.getGuacVncConnectionId());
        }
        containerCredentialRepository.deleteByContainer(container);

        // Confirmed live (arch-xfce, 2026-08-14): a prior delete attempt already got this far and
        // ran cliExecutor.remove() successfully before failing later (see this method's own
        // top-level comment) - the machine was already gone from the host by the time delete()
        // was retried, and machinectl remove on an already-gone machine fails (not the transient
        // "Device or resource busy" RealContainerCliExecutor.remove() already retries), which
        // would otherwise throw here and strand the DB row all over again. NOT_FOUND means nothing
        // left to remove - skip straight to the (already-idempotent, rm -rf-based)
        // deleteMachineFiles() call.
        MachineStatus status = cliExecutor.status(container.getName());
        if (status == MachineStatus.RUNNING) {
            cliExecutor.stopForce(container.getName());
        }
        if (status != MachineStatus.NOT_FOUND) {
            cliExecutor.remove(container.getName());
        }
        filesystemProvisioner.deleteMachineFiles(container.getName());

        containerRepository.delete(container);
    }

    /**
     * Both {@code container} and {@code newOwner} arrive from the controller layer, loaded in an
     * already-closed transaction — detached relative to this method's own. Re-resolving via
     * getReferenceById avoids the "detached entity passed to persist" pitfall
     * shareService.grantAccess's own new ContainerShare row would otherwise hit (same class of bug
     * already fixed in ContainerUserService/AuditLogService this session).
     */
    @Transactional
    public void takeOwnership(Container container, User newOwner) {
        requireManaged(container);
        Container attachedContainer = containerRepository.getReferenceById(container.getId());
        User attachedOwner = userRepository.getReferenceById(newOwner.getId());
        attachedContainer.setOwner(attachedOwner);
        attachedContainer.touch();
        containerRepository.save(attachedContainer);
        // Guarantees the new owner actually has usable SSH/RDP access, not just the Container.owner
        // field flipped — same call ProvisioningService.provision() makes for a freshly-created
        // container's own owner. No-ops if they already have a share (e.g. taking ownership of a
        // container already shared with them).
        shareService.grantAccess(attachedContainer, attachedOwner);
    }

    private void requireManaged(Container container) {
        if (container.getKind() != ContainerKind.MANAGED) {
            throw new IllegalStateException(
                    "'" + container.getName() + "' is an admin-configured external host; nspawnmgr doesn't control its lifecycle");
        }
    }
}
