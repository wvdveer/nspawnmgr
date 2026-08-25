package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.ContainerIsoMounter;
import com.nspawnmgr.cli.ContainerOutboundAccessManager;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerOutboundAllowlistEntry;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.PortMappingProtocol;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerOutboundAllowlistRepository;
import com.nspawnmgr.repository.ContainerPortMappingRepository;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Service
public class ContainerLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ContainerLifecycleService.class);

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
    private final SecretEncryptionService secretEncryptionService;

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
                                      ShareService shareService,
                                      SecretEncryptionService secretEncryptionService) {
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
        this.secretEncryptionService = secretEncryptionService;
    }

    @Transactional
    public void start(Container container) {
        requireManaged(container);
        cliExecutor.start(container.getName(), container.getBackend());
        resyncOutboundAccess(container);
        if (container.getBackend() == ContainerBackend.PODMAN) {
            container.setInternalAddress(resolvePodInternalAddress(container));
        }
        if (container.getBackend() == ContainerBackend.QEMU) {
            reapplyQemuVncPassword(container);
        }
        container.setState(bootedState(container));
        container.touch();
        containerRepository.save(container);
    }

    @Transactional
    public void stopGraceful(Container container) {
        requireManaged(container);
        cliExecutor.stopGraceful(container.getName(), container.getBackend());
        container.setState(ContainerState.STOPPED);
        container.touch();
        containerRepository.save(container);
    }

    @Transactional
    public void stopForce(Container container) {
        requireManaged(container);
        cliExecutor.stopForce(container.getName(), container.getBackend());
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
        cliExecutor.restart(container.getName(), container.getBackend());
        if (container.getBackend() == ContainerBackend.PODMAN) {
            container.setInternalAddress(resolvePodInternalAddress(container));
        }
        if (container.getBackend() == ContainerBackend.QEMU) {
            reapplyQemuVncPassword(container);
        }
        container.setState(bootedState(container));
        container.touch();
        containerRepository.save(container);
    }

    /**
     * State a container lands in right after {@link #start}/{@link #restart} issue the underlying
     * start/restart command. SYSTEMD_NSPAWN: BOOTING — ContainerReadinessPollingService confirms SSH
     * (and RDP, if enabled) are actually reachable before promoting it to RUNNING, since machinectl
     * start/reboot return as soon as the request is issued, not once sshd is actually up. PODMAN:
     * straight to RUNNING — {@code podman start}/{@code restart} are synchronous, and a pod never
     * gets an SSH_KEY credential (see ContainerAccessService's reachability-gated access instead), so
     * the readiness poller would otherwise leave it stuck in BOOTING forever (it skips any BOOTING
     * container with no SSH_KEY credential — see its own javadoc).
     */
    private ContainerState bootedState(Container container) {
        // QEMU: same reasoning as PODMAN - the systemd unit launching qemu-system-x86_64 is
        // synchronous, and a VM never gets an auto-provisioned SSH_KEY credential (VNC is the
        // always-on access method instead - see ProvisioningService#provisionQemu), so the
        // readiness poller would leave it stuck in BOOTING forever the same way a pod would.
        return container.getBackend() == ContainerBackend.SYSTEMD_NSPAWN ? ContainerState.BOOTING : ContainerState.RUNNING;
    }

    /**
     * QEMU doesn't persist its VNC password across process restarts (a fresh qemu-system-x86_64
     * process has no password set at all until told one via HMP) - re-applies whatever's stored in
     * this VM's own VNC_PASSWORD credential every time it (re)starts. Best-effort: a failure here
     * shouldn't undo the VM having actually started, same posture as this app's other post-start
     * Guacamole/access sync steps (e.g. ContainerReadinessPollingService.reapplyPamAuthSettings).
     */
    private void reapplyQemuVncPassword(Container container) {
        containerCredentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD).ifPresentOrElse(
                credential -> {
                    try {
                        String password = secretEncryptionService.decrypt(credential.getSecretCiphertext(), credential.getIv());
                        cliExecutor.setQemuVncPassword(container.getName(), password);
                    } catch (Exception e) {
                        log.warn("Failed to re-apply VNC password for QEMU VM {}: {}", container.getName(), e.getMessage(), e);
                    }
                },
                () -> log.warn("QEMU VM {} has no VNC_PASSWORD credential to re-apply - skipping", container.getName()));
    }

    private static final int POD_INTERNAL_ADDRESS_MAX_ATTEMPTS = 10;
    private static final Duration POD_INTERNAL_ADDRESS_RETRY_DELAY = Duration.ofSeconds(1);

    /**
     * As {@code ProvisioningService.resolveInternalAddress}, for a pod being (re)started outside the
     * initial creation flow - {@link #start}/{@link #restart} skip
     * ContainerReadinessPollingService entirely for PODMAN (see {@link #bootedState}), so this is the
     * only place that ever refreshes {@code container.internalAddress} on a subsequent start/restart;
     * without it, {@code ContainerAccessService.enableSsh/Rdp/Vnc} would keep failing with "No
     * internal address known yet" after the very first restart flips the pod to a new DHCP lease.
     * Bounded rather than open-ended, same posture as its twin: logs and gives up with "" rather than
     * blocking this request forever.
     */
    private String resolvePodInternalAddress(Container container) {
        for (int attempt = 1; attempt <= POD_INTERNAL_ADDRESS_MAX_ATTEMPTS; attempt++) {
            String address = cliExecutor.getInternalAddress(container.getName(), container.getBackend());
            if (!address.isEmpty()) {
                return address;
            }
            if (attempt == POD_INTERNAL_ADDRESS_MAX_ATTEMPTS) {
                break;
            }
            try {
                Thread.sleep(POD_INTERNAL_ADDRESS_RETRY_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerCliException(
                        "Interrupted while waiting for pod '%s' to get an internal address".formatted(container.getName()), e);
            }
        }
        return "";
    }

    public MachineStatus status(Container container) {
        return cliExecutor.status(container.getName(), container.getBackend());
    }

    /** Freezes every process in the container's cgroup in place (systemctl freeze) - only valid
     *  from RUNNING, since pausing anything else has no sensible meaning. */
    @Transactional
    public void pause(Container container) {
        requireManaged(container);
        if (container.getState() != ContainerState.RUNNING) {
            throw new IllegalStateException("Container must be RUNNING to pause");
        }
        cliExecutor.pause(container.getName(), container.getBackend());
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
        cliExecutor.resume(container.getName(), container.getBackend());
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
            isoMounter.unmount(container.getName(), container.getBackend());
        }
        isoMounter.mount(container.getName(), container.getBackend(), packageCacheService.hostPath(iso));
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
        isoMounter.unmount(container.getName(), container.getBackend());
        container.setMountedIso(null);
        container.touch();
        containerRepository.save(container);
        rewriteSettings(container);
    }

    private void rewriteSettings(Container container) {
        if (container.getBackend() == ContainerBackend.QEMU) {
            String isoPath = container.getMountedIso() != null ? packageCacheService.hostPath(container.getMountedIso()) : null;
            filesystemProvisioner.writeQemuUnit(container, isoPath);
            return;
        }
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
        MachineStatus status = cliExecutor.status(container.getName(), container.getBackend());
        if (status == MachineStatus.RUNNING) {
            cliExecutor.stopForce(container.getName(), container.getBackend());
        }
        if (status != MachineStatus.NOT_FOUND) {
            cliExecutor.remove(container.getName(), container.getBackend());
        }
        // PODMAN: `podman rm` above already tears down its own storage - there's no separate
        // nspawnMachinesDir()/<name> tree or .nspawn unit file to clean up the way SYSTEMD_NSPAWN
        // needs (see ContainerFilesystemProvisioner.deleteMachineFiles's own javadoc).
        if (container.getBackend() == ContainerBackend.SYSTEMD_NSPAWN) {
            filesystemProvisioner.deleteMachineFiles(container.getName());
        }

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
        // No requireManaged() here, unlike every other method in this class - taking ownership is
        // a pure ownership/access change (who's granted the container's existing SSH/RDP/VNC
        // connections), not a lifecycle operation, so it's equally valid for an EXTERNAL host as
        // for a MANAGED container.
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
