package com.nspawnmgr.service;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.ContainerOutboundAccessManager;
import com.nspawnmgr.cli.DownloadedPackage;
import com.nspawnmgr.cli.ScriptRunResult;
import com.nspawnmgr.crypto.EncryptedValue;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.crypto.SshKeyPairGenerator;
import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerCredential;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.DesktopManager;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.QemuCpuModel;
import com.nspawnmgr.domain.QemuNicModel;
import com.nspawnmgr.domain.QemuPointerDevice;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.domain.TemplateFeatureState;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Orchestrates the full container create flow: clone, network, boot, provision, register in Guacamole, grant owner. */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    /** Standard Linux username rules - matches ContainerUserService's own copy of this pattern. */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_-]{0,31}$");

    private final ContainerRepository containerRepository;
    private final ContainerCredentialRepository containerCredentialRepository;
    private final ContainerCliExecutor cliExecutor;
    private final ContainerFilesystemProvisioner filesystemProvisioner;
    private final ContainerOutboundAccessManager outboundAccessManager;
    private final TemplateService templateService;
    private final GuacamoleAdminClient guacamoleAdminClient;
    private final ShareService shareService;
    private final SecretEncryptionService secretEncryptionService;
    private final SshKeyPairGenerator sshKeyPairGenerator;
    private final SettingsService settingsService;
    private final PackageCacheService packageCacheService;
    private final PamCredentialAuthService pamCredentialAuthService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ProvisioningService(ContainerRepository containerRepository,
                                ContainerCredentialRepository containerCredentialRepository,
                                ContainerCliExecutor cliExecutor,
                                ContainerFilesystemProvisioner filesystemProvisioner,
                                ContainerOutboundAccessManager outboundAccessManager,
                                TemplateService templateService,
                                GuacamoleAdminClient guacamoleAdminClient,
                                ShareService shareService,
                                SecretEncryptionService secretEncryptionService,
                                SshKeyPairGenerator sshKeyPairGenerator,
                                SettingsService settingsService,
                                PackageCacheService packageCacheService,
                                PamCredentialAuthService pamCredentialAuthService) {
        this.containerRepository = containerRepository;
        this.containerCredentialRepository = containerCredentialRepository;
        this.cliExecutor = cliExecutor;
        this.filesystemProvisioner = filesystemProvisioner;
        this.outboundAccessManager = outboundAccessManager;
        this.templateService = templateService;
        this.guacamoleAdminClient = guacamoleAdminClient;
        this.shareService = shareService;
        this.secretEncryptionService = secretEncryptionService;
        this.sshKeyPairGenerator = sshKeyPairGenerator;
        this.settingsService = settingsService;
        this.packageCacheService = packageCacheService;
        this.pamCredentialAuthService = pamCredentialAuthService;
    }

    @Transactional
    public Container createPending(String name, Template template, User owner, boolean rdpEnabled, boolean vncEnabled,
                                    DesktopManager desktopManager, String description, String podCommand) {
        Container container = new Container(name, owner, template, rdpEnabled && template.getRdpState() != TemplateFeatureState.NOT_CAPABLE, description);
        container.setVncEnabled(vncEnabled && template.getVncState() != TemplateFeatureState.NOT_CAPABLE);
        container.setDesktopManager(desktopManager);
        // Meaningless for SYSTEMD_NSPAWN - stored regardless (matches packageManager's own
        // "harmless when irrelevant" convention) rather than validating the backend here, since the
        // web form itself only ever offers this field on the New Pod page.
        container.setPodCommand(podCommand);
        return containerRepository.save(container);
    }

    /**
     * QEMU still can't reuse {@link #createPending}'s Template-taking signature (see
     * {@link Container#qemu}), so it keeps its own creation entry point - but unlike v1
     * (from-scratch + ISO only), {@code template} may now be a QEMU-backed {@link Template} to
     * clone from instead of getting a fresh empty disk (see {@link #provisionQemu}'s branch on
     * {@code container.getTemplate()}). {@code iso} is nullable - a VM can be created with no boot
     * media, though nothing useful happens until something mounts one. Exactly one of {@code
     * diskSizeGb}/{@code template} is expected non-null - validated by the caller
     * (ContainerApiController.createQemu), not here. {@code cpuModel}/{@code cpuCount}/{@code
     * memoryMb}/{@code nicModel}/{@code pointerDevice} are launch hardware, independent of disk
     * origin - null for any of them means "use the previous hardcoded default" (see
     * ContainerFilesystemProvisioner#writeQemuUnit).
     */
    @Transactional
    public Container createPendingQemu(String name, User owner, String description, Integer diskSizeGb, Template template, CachedPackage iso,
                                        QemuCpuModel cpuModel, Integer cpuCount, Integer memoryMb, QemuNicModel nicModel,
                                        QemuPointerDevice pointerDevice) {
        Container container = Container.qemu(name, owner, description);
        container.setQemuDiskSizeGb(diskSizeGb);
        container.setTemplate(template);
        container.setMountedIso(iso);
        container.setQemuCpuModel(cpuModel);
        container.setQemuCpuCount(cpuCount);
        container.setQemuMemoryMb(memoryMb);
        container.setQemuNicModel(nicModel);
        container.setQemuPointerDevice(pointerDevice);
        return containerRepository.save(container);
    }

    /** True when the configured sudo password is blank — container creation requires admin approval. */
    public boolean requiresApproval() {
        return settingsService.sshApprovalRequired();
    }

    /** Moves a just-created container from CREATING to PENDING_APPROVAL — approval mode only. */
    @Transactional
    public void markPendingApproval(Long containerId) {
        Container container = requireState(containerId, ContainerState.CREATING);
        container.setState(ContainerState.PENDING_APPROVAL);
        container.touch();
        containerRepository.save(container);
    }

    /**
     * Transitions a pending request back to CREATING so status polling reflects it's in progress.
     * The caller must separately invoke {@link #provisionAsync(Long, char[])} — kept as two calls
     * (matching how ContainerApiController.create() already separates createPending/provisionAsync)
     * since a self-invoked {@code @Async} call from within this class would bypass Spring's proxy
     * and run synchronously.
     */
    @Transactional
    public void approve(Long containerId) {
        Container container = requireState(containerId, ContainerState.PENDING_APPROVAL);
        container.setState(ContainerState.CREATING);
        container.touch();
        containerRepository.save(container);
    }

    /** Denies a pending request. Terminal — no SSH/sudo is ever attempted. */
    @Transactional
    public void deny(Long containerId) {
        Container container = requireState(containerId, ContainerState.PENDING_APPROVAL);
        container.setState(ContainerState.DENIED);
        container.touch();
        containerRepository.save(container);
    }

    private Container requireState(Long containerId, ContainerState expected) {
        Container container = containerRepository.findById(containerId)
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + containerId));
        if (container.getState() != expected) {
            throw new IllegalStateException("Container " + containerId + " is not " + expected
                    + " (current state: " + container.getState() + ")");
        }
        return container;
    }

    /** Fire-and-forget entry point so the create HTTP request can return immediately; UI polls status.
     *  Dispatches to {@link #provision}, {@link #provisionPod}, or {@link #provisionQemu} by the
     *  container's own backend - the one place this choice is made, so callers
     *  (ContainerApiController) don't need to know it exists. */
    @Async
    public void provisionAsync(Long containerId) {
        provisionAsync(containerId, null);
    }

    /** As {@link #provisionAsync(Long)}, using a per-request admin-supplied password (approval mode). */
    @Async
    public void provisionAsync(Long containerId, char[] sudoPasswordOverride) {
        Container container = containerRepository.findByIdWithTemplate(containerId)
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + containerId));
        switch (container.getBackend()) {
            case PODMAN -> provisionPod(containerId, sudoPasswordOverride);
            case QEMU -> provisionQemu(containerId, sudoPasswordOverride);
            case SYSTEMD_NSPAWN -> provision(containerId, sudoPasswordOverride);
        }
    }

    /** Runs the full provisioning flow for a container already persisted in CREATING state. */
    public void provision(Long containerId) {
        provision(containerId, null);
    }

    /**
     * As {@link #provision(Long)}. {@code sudoPasswordOverride}, if non-null, is used instead of
     * the configured stored sudo password for this run's creation-time-only privileged steps
     * (cloneTemplate, runInMachine) — the admin-approval per-request flow. Zeroed once this run
     * completes, success or failure; this only erases this array, not whatever String Spring MVC
     * originally deserialized the admin's form submission into (best-effort, not a guarantee).
     */
    public void provision(Long containerId, char[] sudoPasswordOverride) {
        Container container = containerRepository.findByIdWithTemplate(containerId)
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + containerId));
        try {
            filesystemProvisioner.cloneTemplate(container.getTemplate(), container.getName(), sudoPasswordOverride);
            filesystemProvisioner.writeNspawnSettings(container, List.of());
            cliExecutor.start(container.getName(), container.getBackend());
            outboundAccessManager.sync(container.getName(), container.isOutboundEnabled(), List.of());

            container.setPrimaryAccountName(resolveInitialAccountName(container));
            containerRepository.save(container);

            provisionSsh(container, sudoPasswordOverride);
            if (container.getDesktopManager() != DesktopManager.NONE) {
                // Installed once, shared between RDP/VNC - either needs the desktop environment
                // already present before it can start a session that launches into it.
                downloadAndRegister(container,
                        templateService.desktopPackagesToPreDownload(container.getTemplate(), container.getDesktopManager()),
                        sudoPasswordOverride, DESKTOP_DOWNLOAD_TIMEOUT);
                sh(container, templateService.resolveInstallDesktopCommand(container.getTemplate(), container.getDesktopManager()),
                        sudoPasswordOverride, DESKTOP_INSTALL_TIMEOUT);
            }
            if (container.isRdpEnabled()) {
                provisionRdp(container, sudoPasswordOverride);
            }
            if (container.isVncEnabled()) {
                provisionVnc(container, sudoPasswordOverride);
            }

            shareService.grantAccess(container, container.getOwner());

            // Not RUNNING yet — ContainerReadinessPollingService confirms SSH (and RDP, if enabled)
            // are actually reachable before making that call.
            container.setState(ContainerState.BOOTING);
            container.touch();
            containerRepository.save(container);
        } catch (Exception e) {
            // provision() runs via @Async (see provisionAsync/provisionAsync-with-password below) -
            // the throw e below only reaches Spring's generic async-uncaught-exception handler, not
            // a clearly-attributed application log line, so log explicitly here too.
            log.error("Provisioning failed for container '{}': {}", container.getName(), e.getMessage(), e);
            container.setState(ContainerState.ERROR);
            container.setErrorMessage(truncateErrorMessage(e.getMessage()));
            container.touch();
            containerRepository.save(container);
            throw e;
        } finally {
            if (sudoPasswordOverride != null) {
                Arrays.fill(sudoPasswordOverride, '\0');
            }
        }
    }

    /**
     * Podman equivalent of {@link #provision(Long, char[])} - deliberately much shorter, since a pod
     * gets none of the auto-provisioned SSH/RDP/VNC/desktop-manager steps nspawn containers do (see
     * ContainerAccessService's reachability-gated prompt-credentials flow, which is how a pod's owner
     * gets SSH/RDP/VNC access instead, entirely post-creation). {@code podman create} + {@code podman
     * start} are both synchronous, and there's no auto-provisioned credential to wait on, so this goes
     * straight to RUNNING rather than BOOTING - no readiness poll needed.
     */
    public void provisionPod(Long containerId, char[] sudoPasswordOverride) {
        Container container = containerRepository.findByIdWithTemplate(containerId)
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + containerId));
        try {
            // No outboundAccessManager.sync() here - that's the nspawn-specific veth/iptables
            // mechanism (see plan's "explicitly out of scope for pods" list); a podman container on
            // nspawnbr0 already has real network access via netavark, nothing to configure.
            filesystemProvisioner.createPodmanContainer(container.getTemplate().getSourcePath(), container.getName(),
                    container.getPodCommand(), sudoPasswordOverride);
            cliExecutor.start(container.getName(), container.getBackend());

            shareService.grantAccess(container, container.getOwner());
            // ContainerAccessService's reachability-gated enableSsh/Rdp/Vnc (a pod's whole access
            // story - see this method's own javadoc) reads container.getInternalAddress(), not this
            // call's own return value - must persist it, not just resolve it. Normally
            // ContainerReadinessPollingService is what populates this field once RUNNING; a pod skips
            // that poller entirely (see bootedState()'s twin in ContainerLifecycleService), so this is
            // the only place it happens for one.
            container.setInternalAddress(resolveInternalAddress(container));

            container.setState(ContainerState.RUNNING);
            container.touch();
            containerRepository.save(container);
        } catch (Exception e) {
            log.error("Pod provisioning failed for container '{}': {}", container.getName(), e.getMessage(), e);
            container.setState(ContainerState.ERROR);
            container.setErrorMessage(truncateErrorMessage(e.getMessage()));
            container.touch();
            containerRepository.save(container);
            throw e;
        } finally {
            if (sudoPasswordOverride != null) {
                Arrays.fill(sudoPasswordOverride, '\0');
            }
        }
    }

    /**
     * Changes a pod's {@code podCommand} (see {@link Container#getPodCommand}'s own javadoc) and
     * makes it take effect immediately by removing and recreating the underlying podman container -
     * podman bakes a container's own command in at {@code podman create} time, not something a plain
     * {@code podman start} can change. Only while the pod is STOPPED or ERROR: recreating a RUNNING
     * one out from under a connected user (Files/Scripts/Connect) would be actively destructive, not
     * just disruptive - the caller (pod-detail.html's own Save button) mirrors this with a disabled
     * state, same UX convention as "Create template from this machine" elsewhere in this app. ERROR
     * is included deliberately, not just STOPPED: confirmed live (yoga, 2026-08-16) that a pod whose
     * image has neither an Entrypoint nor a Cmd baked in (crun's own "cannot find `` in $PATH") lands
     * in ERROR straight from {@link #provisionPod} - excluding ERROR here would make that pod
     * permanently unrecoverable through the UI, with no way to ever supply the command it actually
     * needs. A podman container that only ever failed to start (never successfully ran) still exists
     * and removes cleanly either way.
     */
    @Transactional
    public void updatePodCommand(Long containerId, String command, char[] sudoPasswordOverride) {
        Container container = containerRepository.findByIdWithTemplate(containerId)
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + containerId));
        if (container.getBackend() != ContainerBackend.PODMAN) {
            throw new IllegalArgumentException("Container '" + container.getName() + "' is not a pod");
        }
        if (container.getState() != ContainerState.STOPPED && container.getState() != ContainerState.ERROR) {
            throw new IllegalStateException("Pod must be stopped (or in ERROR) before its command can be changed");
        }
        try {
            cliExecutor.remove(container.getName(), container.getBackend());
            filesystemProvisioner.createPodmanContainer(container.getTemplate().getSourcePath(), container.getName(),
                    command, sudoPasswordOverride);
            container.setPodCommand(command);
            // Recreated clean - clear a stale ERROR (and its message) rather than leaving a freshly
            // recreated, never-yet-started container stuck showing the OLD failure forever. Doesn't
            // auto-start it either way (same as the STOPPED path) - the admin's own next Start click
            // is what actually confirms the new command works, rather than silently hiding a second
            // possible failure behind this call.
            container.setState(ContainerState.STOPPED);
            container.setErrorMessage(null);
            container.touch();
            containerRepository.save(container);
        } finally {
            if (sudoPasswordOverride != null) {
                Arrays.fill(sudoPasswordOverride, '\0');
            }
        }
    }

    /** Placeholder {@link ContainerCredential#getAccountName()} for a QEMU VM's VNC credential -
     *  unlike every other credential type, QEMU's hypervisor-level VNC auth has no username concept
     *  at all (see Container#getQemuVncPort's own javadoc), but the column is NOT NULL. */
    private static final String QEMU_VNC_ACCOUNT_PLACEHOLDER = "(qemu-console)";

    /**
     * QEMU equivalent of {@link #provision(Long, char[])}/{@link #provisionPod(Long, char[])} -
     * from-scratch + ISO only (no Template at all - see {@link #createPendingQemu}). Goes straight
     * to RUNNING like a pod (the systemd unit launching qemu-system-x86_64 is synchronous), but
     * unlike a pod's own {@code internalAddress}, doesn't try to resolve one synchronously here -
     * see {@link QemuAddressPollingService}'s own javadoc for why "not ready yet, possibly for a
     * long time" is the normal case for a QEMU guest that may not even have an OS installed yet.
     */
    public void provisionQemu(Long containerId, char[] sudoPasswordOverride) {
        Container container = containerRepository.findByIdWithTemplate(containerId)
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + containerId));
        try {
            if (container.getTemplate() != null) {
                filesystemProvisioner.cloneQemuTemplate(container.getTemplate(), container.getName(), sudoPasswordOverride);
            } else {
                filesystemProvisioner.createQemuDisk(container.getName(), container.getQemuDiskSizeGb(), sudoPasswordOverride);
            }

            int vncPort = allocateQemuVncPort();
            container.setQemuVncPort(vncPort);

            String isoHostPath = container.getMountedIso() != null ? packageCacheService.hostPath(container.getMountedIso()) : null;
            filesystemProvisioner.writeQemuUnit(container, isoHostPath);

            cliExecutor.start(container.getName(), container.getBackend());

            String password = generatePassword();
            cliExecutor.setQemuVncPassword(container.getName(), password);
            saveCredential(container, CredentialType.VNC_PASSWORD, QEMU_VNC_ACCOUNT_PLACEHOLDER, password, null);
            String connectionId = guacamoleAdminClient.createVncConnection(container.getName() + "-vnc", QEMU_VNC_HOST, vncPort, password);
            container.setGuacVncConnectionId(connectionId);
            container.setVncEnabled(true);

            shareService.grantAccess(container, container.getOwner());

            container.setState(ContainerState.RUNNING);
            container.touch();
            containerRepository.save(container);
        } catch (Exception e) {
            log.error("QEMU provisioning failed for container '{}': {}", container.getName(), e.getMessage(), e);
            container.setState(ContainerState.ERROR);
            container.setErrorMessage(truncateErrorMessage(e.getMessage()));
            container.touch();
            containerRepository.save(container);
            throw e;
        } finally {
            if (sudoPasswordOverride != null) {
                Arrays.fill(sudoPasswordOverride, '\0');
            }
        }
    }

    /** nspawnbr0's own gateway address - every QEMU VM's VNC listener binds here (see
     *  Container#getQemuVncPort's own javadoc), differentiated by port rather than by address. */
    private static final String QEMU_VNC_HOST = "10.100.0.1";

    /** Lowest free port in the admin-configured range not already claimed by another QEMU VM's own
     *  {@code qemuVncPort} - called once per VM, at creation. */
    private int allocateQemuVncPort() {
        int start = settingsService.qemuVncPortRangeStart();
        int end = settingsService.qemuVncPortRangeEnd();
        List<Integer> taken = containerRepository.findByBackend(ContainerBackend.QEMU).stream()
                .map(Container::getQemuVncPort)
                .filter(port -> port != null)
                .toList();
        for (int candidate = start; candidate <= end; candidate++) {
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free QEMU VNC port available in the configured range ("
                + start + "-" + end + ") - every port is already allocated to another VM");
    }

    private void provisionSsh(Container container, char[] sudoPasswordOverride) {
        String accountName = resolveAccountName(container);
        SshKeyPairGenerator.GeneratedKeyPair keyPair = sshKeyPairGenerator.generate();

        sh(container, "id -u %s >/dev/null 2>&1 || useradd -m -s /bin/bash %s".formatted(accountName, accountName), sudoPasswordOverride);
        sh(container, "mkdir -p /home/%s/.ssh && chmod 700 /home/%s/.ssh".formatted(accountName, accountName), sudoPasswordOverride);
        sh(container, "echo '%s' >> /home/%s/.ssh/authorized_keys && chmod 600 /home/%s/.ssh/authorized_keys && chown -R %s:%s /home/%s/.ssh"
                .formatted(keyPair.publicKeyOpenSsh(), accountName, accountName, accountName, accountName, accountName), sudoPasswordOverride);
        // Skipped entirely when the template's own image already has openssh installed and enabled
        // (see Template.sshState's own comment) - every official "Set up X-minimal" template
        // does, and re-running this every single container creation was pure waste: a host-side
        // package download/pre-fetch plus an in-container install/enable command, both guaranteed
        // no-ops against an already-installed, already-enabled service.
        if (container.getTemplate().getSshState() != TemplateFeatureState.PREINSTALLED) {
            downloadAndRegister(container, templateService.sshPackagesToPreDownload(container.getTemplate()), sudoPasswordOverride);
            sh(container, templateService.resolveInstallSshCommand(container.getTemplate()), sudoPasswordOverride, PACKAGE_INSTALL_TIMEOUT);
            sh(container, "systemctl enable --now ssh 2>/dev/null || systemctl enable --now sshd", sudoPasswordOverride);
        }

        saveCredential(container, CredentialType.SSH_KEY, accountName, keyPair.privateKeyPem(), keyPair.publicKeyOpenSsh());

        String internalAddress = resolveInternalAddress(container);
        String connectionId = guacamoleAdminClient.createSshConnection(
                container.getName() + "-ssh", internalAddress, SSH_PORT, accountName, keyPair.privateKeyPem());
        container.setGuacSshConnectionId(connectionId);
    }

    /**
     * Provisions real, nspawnmgr-managed SSH access (account + generated keypair + Guacamole
     * connection) for a container this service never itself created - specifically the self-hosted
     * "nspawnmgr" app machine and nspawnmgr's own database machine (see
     * ContainerDiscoveryService.tryProvisionManagedSsh), both of which already have sshd baked in
     * and running (cloned from debian-minimal, {@code sshState=PREINSTALLED}) but were never
     * provisioned through the normal create flow. {@code container} must already have a non-null
     * {@code template} and be RUNNING - caller's responsibility, same as every other precondition
     * {@link #sh}'s own systemd-run call depends on. Persists the container afterward, matching
     * {@link #provision}'s own posture.
     *
     * <p>{@code REQUIRES_NEW}, not the default participate-in-caller's-transaction: its sole caller
     * ({@code ContainerDiscoveryService.tryProvisionManagedSsh}) catches and swallows any failure
     * here so one machine's SSH-provisioning hiccup can't fail the whole "Discover machines" action -
     * but a plain participating @Transactional would still mark that OUTER transaction rollback-only
     * the moment an exception crosses this method's own proxy boundary, regardless of whether the
     * caller catches it afterward, silently discarding every other container discover() just found.
     * Its own transaction rolling back independently avoids that entirely.
     *
     * <p>{@code container} may already have a {@code guacSshConnectionId} pointing at a
     * prompt-credentials connection from an earlier {@link ContainerAccessService#tryAutoEnable} run
     * (confirmed live: exactly what happens if {@code ContainerDiscoveryService} ever calls that
     * generic fallback for one of these two machines before this method has ever managed to succeed
     * for it) - captured before {@link #provisionSsh} overwrites the field with the new managed
     * connection's own id, then deleted only after that succeeds, so a mid-provisioning failure never
     * leaves this container with zero working SSH connections at all.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionSshForExistingContainer(Container container, char[] sudoPasswordOverride) {
        String staleConnectionId = container.getGuacSshConnectionId();
        provisionSsh(container, sudoPasswordOverride);
        if (staleConnectionId != null) {
            guacamoleAdminClient.deleteConnection(staleConnectionId);
        }
        container.touch();
        containerRepository.save(container);
    }

    /**
     * Manually records SSH access details for a container nspawnmgr never itself provisioned an
     * SSH_KEY credential for - e.g. a container stuck in BOOTING forever because provisioning was
     * interrupted before {@link #provisionSsh} ever ran, or one that already had sshd/an account set
     * up outside nspawnmgr entirely. Without a stored SSH_KEY credential,
     * ContainerReadinessPollingService has no way to authenticate and skips the container on every
     * poll tick (see its own javadoc), so it can never reach RUNNING - this is the only escape hatch.
     * Owner-only, like every other container mutation (see ContainerApiController.requireOwned).
     *
     * <p>Doesn't verify the key actually works before saving it - the next readiness poll tick does
     * that for real (a real SSH auth attempt) and will simply keep retrying if it's wrong, the same
     * "no real verification, the poller finds out" posture {@link #provisionSsh} itself has for the
     * key it generates.
     */
    @Transactional
    public void recordManualSshCredential(Container container, String accountName, String privateKeyPem) {
        if (containerCredentialRepository.findByContainerAndType(container, CredentialType.SSH_KEY).isPresent()) {
            throw new IllegalStateException("nspawnmgr already has SSH credentials recorded for " + container.getName());
        }
        saveCredential(container, CredentialType.SSH_KEY, accountName, privateKeyPem, null);

        String internalAddress = resolveInternalAddress(container);
        String connectionId = guacamoleAdminClient.createSshConnection(
                container.getName() + "-ssh", internalAddress, SSH_PORT, accountName, privateKeyPem);
        container.setGuacSshConnectionId(connectionId);
        container.touch();
        containerRepository.save(container);
    }

    private void provisionRdp(Container container, char[] sudoPasswordOverride) {
        String accountName = resolveAccountName(container);
        String password = generatePassword();

        sh(container, "id -u %s >/dev/null 2>&1 || useradd -m -s /bin/bash %s".formatted(accountName, accountName), sudoPasswordOverride);
        sh(container, "echo '%s:%s' | chpasswd".formatted(accountName, password), sudoPasswordOverride);
        downloadAndRegister(container, templateService.xrdpPackagesToPreDownload(container.getTemplate()), sudoPasswordOverride);
        sh(container, templateService.resolveInstallXrdpCommand(container.getTemplate()), sudoPasswordOverride, PACKAGE_INSTALL_TIMEOUT);
        sh(container, "systemctl enable --now xrdp", sudoPasswordOverride);
        if (container.getTemplate().getPackageManager() == PackageManager.DNF) {
            // Confirmed live (fed1, then fed-rdp-only, 2026-08-14): xrdp.ini ships with an empty
            // `autorun=`, which per its own comment means "use the first suitable section in the
            // file" for any login where credentials are supplied up front (exactly what
            // Guacamole's own real-credential RDP connection does - there's no interactive
            // session-type prompt for those, so this is the only session type that's ever
            // actually reachable through nspawnmgr's own "Connect" button). That first section is
            // [Xorg] - which failed every time with "X server could not be started" until
            // xorg-x11-server-Xorg + xorgxrdp were actually installed (see
            // TemplateService#DEFAULT_INSTALL_XRDP's own comment - they simply never were,
            // an oversight rather than something Fedora genuinely can't do). An earlier version of
            // this fix bridged RDP through TigerVNC's Xvnc session type instead, working around
            // the missing Xorg server rather than installing it - reverted once a real,
            // purpose-built Xorg (via xorgxrdp) was confirmed live to just work, with no
            // dependency on nspawnmgr's own separate VNC feature being enabled at all. Setting
            // autorun explicitly (rather than relying on [Xorg] merely being first in the file)
            // keeps this robust against the file's own section ordering ever changing.
            sh(container, "sed -i 's/^autorun=.*/autorun=Xorg/' /etc/xrdp/xrdp.ini", sudoPasswordOverride);
        }
        if (container.getDesktopManager() != DesktopManager.NONE) {
            // Confirmed live (fed1, 2026-08-14): even with autorun pointed at a working Xvnc and
            // the systemd-user PAM fix above in place (D-Bus session bus genuinely working now),
            // the window manager still exited in well under a second with zero output anywhere.
            // Root cause: xrdp's own generic /usr/libexec/xrdp/startwm.sh calls RHEL/Fedora's
            // /etc/X11/xinit/Xsession with no arguments, which - since ~/.xsession doesn't exist -
            // falls through its own switchdesk/Xclients.d auto-detection chain to
            // /etc/X11/xinit/Xclients, whose own further auto-detection apparently doesn't
            // reliably land on XFCE on this minimal image. Sidestepped the same way
            // provisionVnc's own xstartup already does for VNC (same desktopSessionCommand,
            // same "just exec the known-correct thing" philosophy): Xsession's own logic checks
            // `[ -x "$HOME/.xsession" ]` before any of that distro auto-detection even runs, so
            // writing this file bypasses the fragile chain entirely instead of trying to fix it.
            sh(container, "printf '#!/bin/sh\\nexec %s\\n' > /home/%s/.xsession && chmod +x /home/%s/.xsession && chown %s:%s /home/%s/.xsession"
                    .formatted(desktopSessionCommand(container.getDesktopManager()), accountName, accountName, accountName, accountName, accountName),
                    sudoPasswordOverride);
        }

        saveCredential(container, CredentialType.RDP_PASSWORD, accountName, password, null);

        // PackageManager.DNF is the only available proxy for "this is a Fedora-family container" -
        // Template has no finer-grained distro field (see PamCredentialAuthService's own javadoc for
        // why this matters: sidesteps a still-unexplained Fedora unix_chkpwd/etc/shadow EACCES bug).
        // May need loosening once RHEL/openSUSE also use DNF (see the v0.4.0 roadmap).
        if (container.getTemplate().getPackageManager() == PackageManager.DNF) {
            pamCredentialAuthService.enableDefaultForFedoraRdp(container);
        }

        String internalAddress = resolveInternalAddress(container);
        String connectionId = guacamoleAdminClient.createRdpConnection(
                container.getName() + "-rdp", internalAddress, RDP_PORT, accountName, password,
                container.getRdpSecurity().guacamoleValue());
        container.setGuacRdpConnectionId(connectionId);
    }

    /**
     * VNC needs no separate Linux account or login password - it reuses the account provisionSsh
     * already created, authenticating purely via a VNC-specific password (~/.vnc/passwd, set with
     * vncpasswd). Display :0 -> port 5900, avoiding the +1-per-display offset a higher display
     * number would introduce (there's no real X server already using :0 inside these headless
     * containers, unlike a real desktop machine).
     */
    private void provisionVnc(Container container, char[] sudoPasswordOverride) {
        String accountName = resolveAccountName(container);
        String password = generatePassword();

        downloadAndRegister(container, templateService.vncPackagesToPreDownload(container.getTemplate()), sudoPasswordOverride);
        sh(container, templateService.resolveInstallVncCommand(container.getTemplate()), sudoPasswordOverride, PACKAGE_INSTALL_TIMEOUT);
        if (container.getDesktopManager() != DesktopManager.NONE) {
            // A bare exec (TigerVNC's own natural behavior) - confirmed live (deb1, 2026-08-13)
            // that TigerVNC 1.12+ kills the whole Xvnc server the instant the xstartup script's
            // own process exits ("The X session cleanly exited! Killing Xtigervnc process ID
            // <pid>..."), which this bare exec triggers directly the moment the user logs out
            // (that process IS the xstartup script, replaced via exec). That's kept intentionally
            // rather than looped: a real logout should actually end the VNC session and return
            // the user to Guacamole's own screen, not silently redraw a fresh desktop on the same
            // still-open connection. What was actually missing is something to bring Xvnc back for
            // the *next* connection attempt - see ContainerSessionService#startVncSession /
            // ensureVncServerRunning below, which starts a fresh one on demand rather than trying
            // to keep one alive forever.
            String xstartupBody = templateService.resolveVncXstartupTemplate(container.getTemplate())
                    .formatted(desktopSessionCommand(container.getDesktopManager()));
            sh(container, "mkdir -p /home/%s/.vnc && printf '#!/bin/sh\\n%s\\n' > /home/%s/.vnc/xstartup && chmod +x /home/%s/.vnc/xstartup && chown -R %s:%s /home/%s/.vnc"
                    .formatted(accountName, xstartupBody, accountName, accountName, accountName, accountName, accountName),
                    sudoPasswordOverride);
            // Confirmed live (arch-kde, 2026-08-15): ~/.vnc/xstartup above is silently ignored by
            // TigerVNC's newer vncsession architecture ("~/.vnc is deprecated, please consult 'man
            // vncsession'..." in its own log) - it instead auto-detects a session from a real X11
            // /usr/share/xsessions/*.desktop file, or an explicit `session=<name>` in
            // /etc/tigervnc/vncserver-config-mandatory naming one. XFCE containers only ever
            // happened to work by coincidence (the xfce4-session package itself ships
            // /usr/share/xsessions/xfce.desktop, entirely independent of nspawnmgr's own xstartup
            // file) - KDE's own package here ships no X11 session file at all (only
            // wayland-sessions/plasma.desktop), so vncsession found nothing and failed with
            // "Could not find a desktop session to run". Writing our own X11 session file directly
            // (same "just exec the known-correct command" philosophy as provisionRdp's own
            // ~/.xsession fix) and pointing vncsession at it explicitly, rather than depending on
            // whatever the desktop package happens to ship, makes this work regardless of desktop
            // manager or TigerVNC version - the ~/.vnc/xstartup write above stays too, for the
            // older, non-vncsession TigerVNC packaging still confirmed live on Debian.
            //
            // Confirmed live (deb-xfce, 2026-08-16): writing vncserver-config-mandatory
            // unconditionally broke that same older packaging outright - its own /usr/bin/vncserver
            // is a Perl script that ALSO reads this exact filename (a legitimate, pre-vncsession
            // TigerVNC feature), but expects real Perl assignments there, not the vncsession-era
            // bare `key=value` line this writes - "vncserver: Error parsing config file
            // /etc/tigervnc/vncserver-config-mandatory: Can't modify constant item in scalar
            // assignment ... at EOF" on every single VNC provisioning attempt, a hard failure (exit
            // 255), not a silent ignore. The xsessions/.desktop file write is harmless either way
            // (nothing outside vncsession ever reads it) so only the vncserver-config-mandatory
            // write itself needs gating - same `[ -f vncserver@.service ]` signal already used to
            // pick the right branch in vncServerStartCommand/vncAccountSwitchCommand below.
            String desktopCommand = desktopSessionCommand(container.getDesktopManager());
            sh(container, ("cat > /usr/share/xsessions/nspawnmgr.desktop <<'NSPAWNMGR_XSESSION'\n" +
                    "[Desktop Entry]\nName=nspawnmgr\nExec=%s\nType=Application\nDesktopNames=nspawnmgr\n" +
                    "NSPAWNMGR_XSESSION\n" +
                    "if [ -f /usr/lib/systemd/system/vncserver@.service ] || [ -f /lib/systemd/system/vncserver@.service ]; then " +
                    "mkdir -p /etc/tigervnc && printf 'session=nspawnmgr\\n' > /etc/tigervnc/vncserver-config-mandatory; fi")
                    .formatted(desktopCommand), sudoPasswordOverride);
        }
        // Password is embedded in the command text here rather than fed over a separate stdin
        // channel - same posture provisionRdp's own chpasswd line above already accepts for its
        // Linux account password.
        sh(container, "mkdir -p /home/%s/.vnc && echo '%s' | su - %s -c 'vncpasswd -f > ~/.vnc/passwd' && chown %s:%s /home/%s/.vnc/passwd && chmod 600 /home/%s/.vnc/passwd"
                .formatted(accountName, password, accountName, accountName, accountName, accountName, accountName), sudoPasswordOverride);
        // TigerVNC 1.13+ (confirmed live on both arch-minimal and fedora-minimal) moved off the
        // classic "run vncserver directly from a login shell" model onto a systemd-integrated one
        // (`man vncsession`): its own /usr/bin/vncserver CLI wrapper now either rejects extra flags
        // outright (a bare "-localhost no" errors "usage: vncserver <display>" and exits
        // immediately) or, given just a display, never daemonizes at all - it runs the whole Xfce
        // session in the FOREGROUND forever. Confirmed live: that hung our own su -c invocation
        // until this method's own executor SIGTERM'd it after PACKAGE_INSTALL_TIMEOUT/SH_DEFAULT_TIMEOUT
        // elapsed (exit 143, "Terminated"), and even then left an orphaned Xvnc bound to :0 that
        // collided with the next provisioning attempt.
        //
        // The package now ships its own systemd template unit (/usr/lib/systemd/system/
        // vncserver@.service, Type=forking, ExecStart=/usr/bin/vncsession-start %i) built exactly
        // for this - it handles daemonizing, the PAM/session-bus setup our raw `su -c` invocation
        // was missing (confirmed live: "Failed to connect to user scope bus... DBUS_SESSION_BUS_
        // ADDRESS and XDG_RUNTIME_DIR not defined"), and reads /etc/tigervnc/vncserver.users (syntax
        // "<display>=<username>") to know which account to run as - not a CLI argument. This is the
        // same posture provisionSsh/provisionRdp already take (systemctl enable --now sshd/xrdp)
        // rather than hand-rolling process supervision. Confirmed live: Xvnc ends up listening on
        // 0.0.0.0:5900/[::]:5900 with no "-localhost no" equivalent needed - the new architecture's
        // own default is already all-interfaces.
        //
        // Falls back to the old raw pkill+vncserver invocation when the unit isn't present (older
        // TigerVNC packaging, e.g. APT's tigervnc-standalone-server - confirmed live on
        // debian-minimal/deb1: no vncserver@.service ships with 1.12.0+dfsg-8, so this branch is
        // the one that actually runs there). That form used to hang every single time (exit 143,
        // "Terminated", no Xvnc trace at all) for a completely different reason than the
        // 1.13+-foreground-forever theory above: `pkill -f "Xvnc :0 "` matches against processes'
        // FULL command lines, and the entire `su -c '...'` argument string passed to this shell -
        // including the pkill invocation's own pattern text - IS the invoking shell's own command
        // line. So pkill matched and killed the very shell that was in the middle of running it,
        // before it ever reached the `; vncserver ...` part that follows - confirmed live by
        // isolating pkill as the sole cause (removing it alone fixed the hang; sleep alone did not
        // reproduce it). Anchoring the pattern makes it match only the real Xvnc server process,
        // never the wrapping shell (whose argv starts with "bash"/"-su"/etc even though it contains
        // the same substring later on) - confirmed live, fixes the hang.
        //
        // The pattern in vncServerStartCommand is `(^|/)<processNamePattern> :0 `, not the
        // originally-assumed `^Xvnc :0 ` - two independent corrections, both confirmed live (deb1,
        // 2026-08-13), on top of that same original fix:
        // (1) the process name itself isn't reliably "Xvnc" - Debian's tigervnc-standalone-server
        //     execs a binary literally named `Xtigervnc` - see Template.vncProcessNamePattern for
        //     why this is now a per-template override rather than hardcoded, default `X[a-z]*vnc`
        //     to match both.
        // (2) anchoring to the bare *start* of the command line doesn't work either, because the
        //     real binary is normally invoked by its absolute path (e.g. "/usr/bin/Xtigervnc :0
        //     ..."), which doesn't start with "X" - `(^|/)` matches either the true start or right
        //     after a path separator, covering both a bare name and an absolute path without
        //     reopening the self-kill risk (the wrapping su/sh command's own argv never contains a
        //     literal "/X...vnc :0 " substring).
        // Both gaps meant the original fix's own pkill silently matched nothing at all on this
        // system this whole time - never killed anything, never caused the self-kill hang either,
        // so its own live verification never caught it (`ss`/`ps` still showed the "already
        // dead" old process happily listening).
        sh(container, vncServerStartCommand(accountName, templateService.resolveVncProcessNamePattern(container.getTemplate())),
                sudoPasswordOverride);

        saveCredential(container, CredentialType.VNC_PASSWORD, accountName, password, null);

        String internalAddress = resolveInternalAddress(container);
        String connectionId = guacamoleAdminClient.createVncConnection(
                container.getName() + "-vnc", internalAddress, VNC_PORT, password);
        container.setGuacVncConnectionId(connectionId);
    }

    /**
     * Re-points this container's already-provisioned SSH/RDP/VNC credentials at a different Linux
     * account already present inside it (see ContainerUserService.listUsers — anything shown there
     * qualifies, including one added later via "Container users") — the owner's escape hatch from
     * whichever account {@link #resolveInitialAccountName} picked at creation time. Only whichever
     * of SSH/RDP/VNC are actually enabled (have a Guacamole connection id) are touched. The previous
     * account itself is left alone — not deleted, and its old authorized_keys entry isn't revoked —
     * this only changes which account nspawnmgr's own generated credentials target going forward.
     */
    @Transactional
    public void changePrimaryAccount(Container container, String newAccountName, char[] sudoPasswordOverride) {
        Container attached = containerRepository.findByIdWithTemplate(container.getId())
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + container.getId()));
        if (attached.getState() != ContainerState.RUNNING) {
            throw new IllegalStateException("Container must be running to change its primary account");
        }
        if (!USERNAME_PATTERN.matcher(newAccountName).matches() || !accountExistsInContainer(attached, newAccountName)) {
            throw new IllegalArgumentException("'" + newAccountName + "' is not an existing account inside " + attached.getName());
        }

        if (attached.getGuacSshConnectionId() != null) {
            rewireSsh(attached, newAccountName, sudoPasswordOverride);
        }
        if (attached.isRdpEnabled() && attached.getGuacRdpConnectionId() != null) {
            rewireRdp(attached, newAccountName, sudoPasswordOverride);
        }
        if (attached.isVncEnabled() && attached.getGuacVncConnectionId() != null) {
            rewireVnc(attached, newAccountName, sudoPasswordOverride);
        }

        attached.setPrimaryAccountName(newAccountName);
        attached.touch();
        containerRepository.save(attached);
    }

    /** The credential-granting tail of {@link #provisionSsh}, minus the account-creation/package-install
     *  steps already done — those apply once per container, not per account switch. */
    private void rewireSsh(Container container, String accountName, char[] sudoPasswordOverride) {
        SshKeyPairGenerator.GeneratedKeyPair keyPair = sshKeyPairGenerator.generate();
        sh(container, "mkdir -p /home/%s/.ssh && chmod 700 /home/%s/.ssh".formatted(accountName, accountName), sudoPasswordOverride);
        sh(container, "echo '%s' >> /home/%s/.ssh/authorized_keys && chmod 600 /home/%s/.ssh/authorized_keys && chown -R %s:%s /home/%s/.ssh"
                .formatted(keyPair.publicKeyOpenSsh(), accountName, accountName, accountName, accountName, accountName), sudoPasswordOverride);

        saveCredential(container, CredentialType.SSH_KEY, accountName, keyPair.privateKeyPem(), keyPair.publicKeyOpenSsh());

        String internalAddress = resolveInternalAddress(container);
        guacamoleAdminClient.updateSshConnection(container.getGuacSshConnectionId(), container.getName() + "-ssh",
                internalAddress, SSH_PORT, accountName, keyPair.privateKeyPem());
    }

    /** The credential-granting tail of {@link #provisionRdp}, minus account creation/xrdp install. */
    private void rewireRdp(Container container, String accountName, char[] sudoPasswordOverride) {
        String password = generatePassword();
        sh(container, "echo '%s:%s' | chpasswd".formatted(accountName, password), sudoPasswordOverride);
        if (container.getDesktopManager() != DesktopManager.NONE) {
            sh(container, "printf '#!/bin/sh\\nexec %s\\n' > /home/%s/.xsession && chmod +x /home/%s/.xsession && chown %s:%s /home/%s/.xsession"
                    .formatted(desktopSessionCommand(container.getDesktopManager()), accountName, accountName, accountName, accountName, accountName),
                    sudoPasswordOverride);
        }

        saveCredential(container, CredentialType.RDP_PASSWORD, accountName, password, null);

        String internalAddress = resolveInternalAddress(container);
        guacamoleAdminClient.updateRdpConnection(container.getGuacRdpConnectionId(), container.getName() + "-rdp",
                internalAddress, RDP_PORT, accountName, password, container.getRdpSecurity().guacamoleValue());
    }

    /** The credential-granting tail of {@link #provisionVnc}, minus package install; also forces the
     *  running VNC server (if any) to restart under the new account rather than waiting for the next
     *  on-demand {@link #ensureVncServerRunning} call to notice, since its own port-based "already
     *  running" check would otherwise treat the old account's still-live session as this account's. */
    private void rewireVnc(Container container, String accountName, char[] sudoPasswordOverride) {
        String password = generatePassword();
        if (container.getDesktopManager() != DesktopManager.NONE) {
            String xstartupBody = templateService.resolveVncXstartupTemplate(container.getTemplate())
                    .formatted(desktopSessionCommand(container.getDesktopManager()));
            sh(container, "mkdir -p /home/%s/.vnc && printf '#!/bin/sh\\n%s\\n' > /home/%s/.vnc/xstartup && chmod +x /home/%s/.vnc/xstartup && chown -R %s:%s /home/%s/.vnc"
                    .formatted(accountName, xstartupBody, accountName, accountName, accountName, accountName, accountName),
                    sudoPasswordOverride);
            // See provisionVnc's own comment on the vncserver-config-mandatory gating below - the
            // older, non-vncsession TigerVNC's own /usr/bin/vncserver (a Perl script) also reads
            // this exact filename but expects real Perl syntax there, not a bare key=value line.
            String desktopCommand = desktopSessionCommand(container.getDesktopManager());
            sh(container, ("cat > /usr/share/xsessions/nspawnmgr.desktop <<'NSPAWNMGR_XSESSION'\n" +
                    "[Desktop Entry]\nName=nspawnmgr\nExec=%s\nType=Application\nDesktopNames=nspawnmgr\n" +
                    "NSPAWNMGR_XSESSION\n" +
                    "if [ -f /usr/lib/systemd/system/vncserver@.service ] || [ -f /lib/systemd/system/vncserver@.service ]; then " +
                    "mkdir -p /etc/tigervnc && printf 'session=nspawnmgr\\n' > /etc/tigervnc/vncserver-config-mandatory; fi")
                    .formatted(desktopCommand), sudoPasswordOverride);
        }
        sh(container, "mkdir -p /home/%s/.vnc && echo '%s' | su - %s -c 'vncpasswd -f > ~/.vnc/passwd' && chown %s:%s /home/%s/.vnc/passwd && chmod 600 /home/%s/.vnc/passwd"
                .formatted(accountName, password, accountName, accountName, accountName, accountName, accountName), sudoPasswordOverride);
        sh(container, vncAccountSwitchCommand(accountName, templateService.resolveVncProcessNamePattern(container.getTemplate())),
                sudoPasswordOverride);

        saveCredential(container, CredentialType.VNC_PASSWORD, accountName, password, null);

        String internalAddress = resolveInternalAddress(container);
        guacamoleAdminClient.updateVncConnection(container.getGuacVncConnectionId(), container.getName() + "-vnc",
                internalAddress, VNC_PORT, password);
    }

    /** Unlike {@link #vncServerStartCommand}, unconditionally stops whatever's currently running on
     *  :0 (regardless of which account it belongs to) before starting a fresh one under {@code
     *  accountName} - the whole point here is switching who owns it, not leaving an already-running
     *  session alone. */
    private static String vncAccountSwitchCommand(String accountName, String processNamePattern) {
        return ("if [ -f /usr/lib/systemd/system/vncserver@.service ] || [ -f /lib/systemd/system/vncserver@.service ]; then " +
                "systemctl stop vncserver@:0.service 2>/dev/null; " +
                "sed -i '/^:0=/d' /etc/tigervnc/vncserver.users 2>/dev/null; " +
                "echo ':0=%1$s' >> /etc/tigervnc/vncserver.users; " +
                "systemctl enable vncserver@:0.service && systemctl restart vncserver@:0.service; " +
                "else pkill -f \"(^|/)%2$s :0 \" 2>/dev/null; sleep 1; su - %1$s -c 'vncserver :0 -localhost no >/dev/null 2>&1 || vncserver :0'; fi")
                .formatted(accountName, processNamePattern);
    }

    /** {@code container.getPrimaryAccountName()} if set, otherwise the global fallback default -
     *  see Container.primaryAccountName's own javadoc for why null must fall back rather than error. */
    private String resolveAccountName(Container container) {
        String accountName = container.getPrimaryAccountName();
        return accountName != null ? accountName : settingsService.provisioningAdminAccountName();
    }

    /**
     * Picks the Linux account this container's SSH/RDP/VNC credentials will target for its whole
     * lifetime (until an owner explicitly calls {@link #changePrimaryAccount}): the container's own
     * owner's username, sanitized into a valid Linux username, unless that's unusable (empty after
     * sanitizing) or it already exists inside this freshly-cloned image (a real conflict - most
     * likely a reserved system account like "games" or "mail" than a genuine human collision, but
     * either way not safe to just take over) - in which case the global
     * provisioning.adminAccountName default is used instead, exactly like every container from
     * before this per-owner default existed.
     */
    private String resolveInitialAccountName(Container container) {
        String fallback = settingsService.provisioningAdminAccountName();
        String candidate = sanitizeUsername(container.getOwner().getUsername());
        if (candidate == null || candidate.equals(fallback)) {
            return fallback;
        }
        if (accountExistsInContainer(container, candidate)) {
            log.info("Owner-derived account name '{}' already exists inside container '{}' - falling back to '{}'",
                    candidate, container.getName(), fallback);
            return fallback;
        }
        return candidate;
    }

    /** @return a valid Linux username derived from {@code rawUsername}, or null if none of it survives
     *  sanitizing (e.g. empty, or entirely non-Latin characters) - callers fall back to a fixed default.
     *  Package-private test seam, same posture as {@link #truncateErrorMessage}. */
    static String sanitizeUsername(String rawUsername) {
        if (rawUsername == null) {
            return null;
        }
        String candidate = rawUsername.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (!candidate.isEmpty() && Character.isDigit(candidate.charAt(0))) {
            candidate = "_" + candidate;
        }
        if (candidate.length() > 32) {
            candidate = candidate.substring(0, 32);
        }
        return USERNAME_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    /** Live {@code id -u} check, tolerant of the same "container not booted enough yet" transient
     *  race {@link #sh} already retries through - unlike {@link #sh}, a clean non-zero exit (account
     *  genuinely doesn't exist) is a normal, immediate "false", not a failure. */
    private boolean accountExistsInContainer(Container container, String accountName) {
        for (int attempt = 1; attempt <= SH_MAX_ATTEMPTS; attempt++) {
            CommandResult result = cliExecutor.runInMachine(container.getName(), container.getBackend(), List.of("id", "-u", accountName), SH_DEFAULT_TIMEOUT, null);
            if (result.success()) {
                return true;
            }
            boolean containerNotReadyYet = result.stderr() != null && result.stderr().contains("Failed to connect to bus");
            if (!containerNotReadyYet || attempt == SH_MAX_ATTEMPTS) {
                return false;
            }
            try {
                Thread.sleep(SH_RETRY_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerCliException("Interrupted while waiting for container '%s' to become ready".formatted(container.getName()), e);
            }
        }
        return false;
    }

    private static String vncServerStartCommand(String accountName, String processNamePattern) {
        return ("if [ -f /usr/lib/systemd/system/vncserver@.service ] || [ -f /lib/systemd/system/vncserver@.service ]; then " +
                "grep -q '^:0=' /etc/tigervnc/vncserver.users 2>/dev/null || echo ':0=%1$s' >> /etc/tigervnc/vncserver.users; " +
                "systemctl enable vncserver@:0.service && systemctl restart vncserver@:0.service; " +
                "else su - %1$s -c 'pkill -f \"(^|/)%2$s :0 \" 2>/dev/null; sleep 1; vncserver :0 -localhost no >/dev/null 2>&1 || vncserver :0'; fi")
                .formatted(accountName, processNamePattern);
    }

    private static final Duration ENSURE_VNC_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Starts a fresh VNC server for {@code container} only if none is currently running - called
     * right before handing back a VNC session URL (see ContainerSessionService#startVncSession),
     * not on a fixed schedule or via systemd auto-restart. This is what makes logging out of the
     * desktop (which now genuinely ends the VNC session - see provisionVnc's own comment) not a
     * dead end: the *next* connection attempt is what brings Xvnc back, with a clean new session,
     * rather than something racing to restart it the instant the old one exits.
     *
     * <p>"Already running" is checked by whether port 5900 is listening, not by matching a process
     * name - confirmed live (deb1, 2026-08-13) that the actual VNC server binary name isn't
     * reliably "Xvnc" (Debian's tigervnc-standalone-server execs `Xtigervnc`), so a name-pattern
     * check is fragile in exactly the way vncServerStartCommand's own pkill pattern already turned
     * out to be; the port it's supposed to be serving is a much more reliable signal.
     */
    public void ensureVncServerRunning(Container container) {
        // container.getTemplate() may be a lazy proxy tied to whatever session originally loaded it
        // (open-in-view is off, and this is reached from ContainerSessionService's own transaction,
        // not the one that loaded the container in the controller layer) - re-fetch with template
        // eagerly joined, same fix ContainerPortMappingService.rewriteSettings/TemplateService
        // .createFromMachine already needed for the identical reason.
        Container withTemplate = containerRepository.findByIdWithTemplate(container.getId())
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + container.getId()));
        String accountName = resolveAccountName(container);
        String processNamePattern = templateService.resolveVncProcessNamePattern(withTemplate.getTemplate());
        String script = "ss -tln 2>/dev/null | grep -q ':" + VNC_PORT + "[[:space:]]' || { "
                + vncServerStartCommand(accountName, processNamePattern) + " ; }";
        ScriptRunResult result = cliExecutor.runScript(container.getName(), container.getBackend(), script, ENSURE_VNC_TIMEOUT);
        if (!result.success()) {
            log.warn("Failed to ensure VNC server running for container {} (exit {})", container.getName(), result.exitCode());
        }
    }

    private static final Duration ENSURE_LINGER_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Enables systemd "lingering" for the provisioning account, only if not already enabled -
     * called right before handing back an RDP session URL (see
     * ContainerSessionService#startRdpSession), the same on-demand posture as
     * {@link #ensureVncServerRunning} rather than a one-time provisioning step (an owner-requested
     * design choice, not just a convenience - keeps this from silently reverting to broken if
     * something host-side ever un-lingers the account, and keeps provisioning itself simpler).
     *
     * <p>Root cause this works around, confirmed live (deb1 then fed1, 2026-08-13): a fresh
     * container has no {@code /run/user/<uid>} (XDG_RUNTIME_DIR) at all until a PAM session for
     * that account has actually completed - modern XFCE hard-requires it to already exist to start
     * its own D-Bus session bus, so the very first desktop session on a cold container can race
     * pam_systemd's own creation of it and lose (window manager exits in under a second, sesman
     * logs "This could indicate a window manager config problem"). VNC accidentally sidesteps this
     * because Xtigervnc itself (not the desktop inside it) is the long-lived process keeping that
     * PAM session - and therefore {@code /run/user/<uid>} - alive, so once a VNC connection has
     * ever succeeded once, RDP's later attempts stop racing. RDP has no equivalent: xrdp-sesman
     * tears down the *entire* X session the instant the window manager exits (its own designed
     * "session ends when the WM exits" behavior), so every cold RDP attempt restarts the exact same
     * race with nothing to break the cycle. `loginctl enable-linger` sidesteps this properly -
     * {@code /run/user/<uid>} then persists independent of any login session at all, confirmed live
     * to survive even with no VNC/RDP process running whatsoever.
     */
    public void ensureLingerEnabled(Container container) {
        String accountName = resolveAccountName(container);
        String script = "loginctl show-user %1$s --property=Linger 2>/dev/null | grep -q '^Linger=yes' || loginctl enable-linger %1$s"
                .formatted(accountName);
        ScriptRunResult result = cliExecutor.runScript(container.getName(), container.getBackend(), script, ENSURE_LINGER_TIMEOUT);
        if (!result.success()) {
            log.warn("Failed to ensure linger enabled for container {} (exit {})", container.getName(), result.exitCode());
        }
    }

    /**
     * Thin wrapper around filesystemProvisioner.downloadPackagesIntoContainer that also registers
     * each returned top-level package with PackageCacheService, so it shows up in /admin/packages -
     * confirmed live, that page had no visibility at all into anything this auto-fetch path had
     * already downloaded onto the host. All four provisioning steps (SSH/RDP/VNC/desktop-manager)
     * go through this rather than calling filesystemProvisioner directly, so none of them silently
     * skips registration.
     */
    private void downloadAndRegister(Container container, List<String> packages, char[] sudoPasswordOverride) {
        downloadAndRegister(container, packages, sudoPasswordOverride, DEFAULT_DOWNLOAD_TIMEOUT);
    }

    /** {@code timeout}: see {@link #DESKTOP_DOWNLOAD_TIMEOUT}'s own comment - the SSH/RDP/VNC
     *  callers' single small package fits comfortably in the default. */
    private void downloadAndRegister(Container container, List<String> packages, char[] sudoPasswordOverride, Duration timeout) {
        // Confirmed live: this used to be hardcoded to PackageManager.APT, on the assumption that
        // *PackagesToPreDownload's own preDownloadEligible gate only ever returned a non-empty list
        // for APT templates - true when this was written, but no longer true once that gate was
        // widened to DNF/PACMAN too (see TemplateService). Left hardcoded, a Fedora container's SSH
        // install tried to run the *APT* download script against its own rootfs and failed outright
        // ("Unable to acquire the dpkg frontend lock" - apt tooling operating on a DNF container's
        // filesystem, which has no dpkg state at all). Must always be the container's own template
        // package manager, matching whatever *PackagesToPreDownload actually resolved the names for.
        PackageManager packageManager = container.getTemplate().getPackageManager();
        List<DownloadedPackage> downloaded = filesystemProvisioner.downloadPackagesIntoContainer(
                packageManager, container.getName(), packages, sudoPasswordOverride, timeout);
        for (DownloadedPackage p : downloaded) {
            packageCacheService.registerAutoFetchedIfAbsent(packageManager, p.filename(), p.sizeBytes(), container.getOwner());
        }
    }

    private static String desktopSessionCommand(DesktopManager desktopManager) {
        return switch (desktopManager) {
            case GNOME -> "gnome-session";
            case KDE_STANDARD -> "startplasma-x11";
            case XFCE4 -> "startxfce4";
            case NONE -> throw new IllegalStateException("desktopSessionCommand should never be called for NONE");
        };
    }

    private static final int SSH_PORT = 22;
    private static final int RDP_PORT = 3389;
    private static final int VNC_PORT = 5900;
    private static final int INTERNAL_ADDRESS_MAX_ATTEMPTS = 10;
    private static final Duration INTERNAL_ADDRESS_RETRY_DELAY = Duration.ofSeconds(1);

    /**
     * The container's own host0 IP typically isn't assigned by the time provisionSsh/provisionRdp
     * run — {@link #sh}'s own "Failed to connect to bus" retry only covers systemd's own readiness
     * to accept --machine=-targeted exec calls, a separate, usually-shorter timeline than DHCP
     * actually completing on host0. Bounded rather than open-ended: if this never resolves, log a
     * warning and proceed with an empty hostname — ContainerReadinessPollingService's own
     * per-boot sync corrects the Guacamole connection's hostname within one poll tick regardless,
     * once the container actually does get an address.
     */
    private String resolveInternalAddress(Container container) {
        for (int attempt = 1; attempt <= INTERNAL_ADDRESS_MAX_ATTEMPTS; attempt++) {
            String address = cliExecutor.getInternalAddress(container.getName(), container.getBackend());
            if (!address.isEmpty()) {
                return address;
            }
            if (attempt == INTERNAL_ADDRESS_MAX_ATTEMPTS) {
                break;
            }
            try {
                Thread.sleep(INTERNAL_ADDRESS_RETRY_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerCliException("Interrupted while waiting for container '%s' to get an internal address".formatted(container.getName()), e);
            }
        }
        log.warn("Could not resolve an internal address for container '{}' after {} attempts; Guacamole connection will be created with an empty hostname and corrected on the next readiness poll",
                container.getName(), INTERNAL_ADDRESS_MAX_ATTEMPTS);
        return "";
    }

    /** Upsert, not a blind insert - {@link #rewireSsh}/{@link #rewireRdp}/{@link #rewireVnc} call
     *  this for a (container, type) that already has a row from the original provisioning run, and
     *  container_credentials has a unique constraint on exactly that pair. */
    private void saveCredential(Container container, CredentialType type, String accountName, String secret, String publicKey) {
        EncryptedValue encrypted = secretEncryptionService.encrypt(secret);
        ContainerCredential credential = containerCredentialRepository.findByContainerAndType(container, type)
                .orElseGet(ContainerCredential::new);
        credential.setContainer(container);
        credential.setType(type);
        credential.setAccountName(accountName);
        credential.setSecretCiphertext(encrypted.ciphertextBase64());
        credential.setIv(encrypted.ivBase64());
        credential.setPublicKey(publicKey);
        containerCredentialRepository.save(credential);
    }

    private static final int SH_MAX_ATTEMPTS = 30;
    private static final Duration SH_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration SH_DEFAULT_TIMEOUT = Duration.ofMinutes(3);
    // A desktop metapackage (xfce4, gnome, kde-standard) installs ~450+ packages - dpkg's own
    // per-package trigger processing (icon caches, ldconfig, gsettings schema compilation, etc.)
    // over that many packages genuinely takes longer than the default 3-minute budget on real
    // hardware. Confirmed live: a provisioning run reported ERROR with "Command timed out" on
    // exactly this install, but the underlying apt-get process was still running to completion in
    // the background regardless - re-running the identical command moments later (via this
    // container's own Scripts feature) reported "xfce4 is already the newest version" and finished
    // in seconds, proving the original install had genuinely succeeded, nspawnmgr just gave up on
    // it too early.
    //
    // Also applied to the SSH/RDP/VNC single-package install steps, not just desktop-manager ones -
    // confirmed live, "dnf install -y xrdp" alone (a single, unremarkable package) blew the plain
    // 3-minute default under dnf5 on Fedora, the same "Command timed out" symptom for the same
    // underlying reason (dnf5's own per-invocation overhead, not the package count).
    private static final Duration PACKAGE_INSTALL_TIMEOUT = Duration.ofMinutes(20);
    private static final Duration DEFAULT_DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    // Confirmed live (deb-gnome, 2026-08-14): the pre-fetch step above only ever downloads the
    // named metapackage itself (a few KB - "task-gnome-desktop_3.73_all.deb" showed up as the
    // sole auto-fetched file), not its transitive dependency closure - there's no APT equivalent
    // of "download this and everything it needs" available to the host-side pre-fetch pipeline.
    // The real weight (GNOME's own dependency closure - well over a thousand packages, 1-2GB+ on
    // Debian) only gets resolved, downloaded, and unpacked/configured during the in-container
    // `apt-get install` itself, which shared PACKAGE_INSTALL_TIMEOUT with the SSH/RDP/VNC steps'
    // single, small packages - not enough, especially with another large desktop install (KDE)
    // running concurrently on the same host and competing for bandwidth/CPU/disk I/O. This is the
    // constant an earlier comment on DESKTOP_DOWNLOAD_TIMEOUT below already referred to by name
    // without it actually existing - added properly now rather than continuing to reuse
    // PACKAGE_INSTALL_TIMEOUT's much smaller budget for a fundamentally bigger job.
    private static final Duration DESKTOP_INSTALL_TIMEOUT = Duration.ofMinutes(45);
    // A desktop metapackage's host-side *download* step (nspawnmgr-download-packages.sh's
    // apt-get update + install --download-only + simulate + per-package apt-get download) fetches
    // the same huge dependency closure DESKTOP_INSTALL_TIMEOUT's own comment describes - a much
    // bigger download than the single small package SSH/RDP/VNC pre-fetch normally handles, and
    // unlike the in-container install step this one is genuinely network-bound, not just
    // CPU/dpkg-trigger-bound. Confirmed live: task-gnome-desktop's own download step alone timed
    // out against the old 5-minute default. Reuses PACKAGE_INSTALL_TIMEOUT's own value rather than
    // a separately-tuned number - no measured data exists yet to justify picking a different one.
    private static final Duration DESKTOP_DOWNLOAD_TIMEOUT = PACKAGE_INSTALL_TIMEOUT;

    /**
     * Runs a command inside the container over SSH+sudo, throwing on a non-zero exit code -
     * runInMachine's own CommandResult was previously discarded here entirely, so every step of
     * provisionSsh/provisionRdp (useradd, authorized_keys, systemctl enable --now ssh, ...) could
     * silently fail with provision() never finding out, reaching BOOTING as if everything had
     * actually worked. Confirmed live: this exact gap is why a container could sit fully booted,
     * with sshd genuinely running, yet have no admin account or authorized_keys at all inside it.
     *
     * <p>Retries specifically on "Failed to connect to bus" - systemd-run --machine='s own
     * signature for "this container hasn't finished enough of its own boot yet to accept
     * --machine=-targeted exec calls", a real, transient race confirmed live right after
     * cliExecutor.start() returns (machinectl start returns as soon as the start request is
     * issued, not once the container is actually ready for this). Any other failure throws
     * immediately rather than masking a genuine command bug behind a 30s retry loop.
     */
    private void sh(Container container, String script, char[] sudoPasswordOverride) {
        sh(container, script, sudoPasswordOverride, SH_DEFAULT_TIMEOUT);
    }

    private void sh(Container container, String script, char[] sudoPasswordOverride, Duration timeout) {
        for (int attempt = 1; attempt <= SH_MAX_ATTEMPTS; attempt++) {
            CommandResult result = cliExecutor.runInMachine(container.getName(), container.getBackend(), List.of("sh", "-c", script), timeout, sudoPasswordOverride);
            if (result.success()) {
                return;
            }
            boolean containerNotReadyYet = result.stderr() != null && result.stderr().contains("Failed to connect to bus");
            if (!containerNotReadyYet || attempt == SH_MAX_ATTEMPTS) {
                // Confirmed live (createDebianMinimalTemplate hit the same gap): a command's real
                // failure reason - e.g. apt-get's own error text - often lands on stdout, not
                // stderr, so stderr alone can be a completely uninformative "exit 100" with
                // nothing to act on.
                throw new ContainerCliException("Command failed inside container '%s' (exit %d): %s -- stdout: %s -- stderr: %s"
                        .formatted(container.getName(), result.exitCode(), script, result.stdout(), result.stderr()));
            }
            try {
                Thread.sleep(SH_RETRY_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerCliException("Interrupted while waiting for container '%s' to become ready".formatted(container.getName()), e);
            }
        }
    }

    // Matches Container.errorMessage's @Column(length = 2000) - a failed command's message can
    // include its full stdout/stderr (see sh()'s own error formatting below), easily exceeding
    // that. Confirmed live: an untruncated message overflowing the column threw a SECOND exception
    // (MysqlDataTruncation) from this very catch block's own save, which was never itself caught -
    // the container's state update never committed, leaving it stuck in CREATING forever instead
    // of reaching ERROR with the real failure visible.
    private static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    // Kept short: just enough to show which command/exit code failed (sh()'s own message prefix),
    // never enough to meaningfully compete with the tail below for budget.
    private static final int TRUNCATED_HEAD_LENGTH = 300;

    /**
     * Confirmed live: a straight head-only truncation silently dropped exactly the useful part of
     * a large install's error - container "b3"'s xfce4 install failure was 2000 chars of nothing
     * but apt's own "Reading package lists... The following additional packages will be
     * installed: <hundreds of names>" preamble, with the actual failure reason (apt/dpkg's own
     * "E: ..." line, always right before the command exits) cut off entirely before it ever
     * appeared. Keeps a short head (which command ran, at what exit code - see sh()'s own message
     * format) and spends the rest of the budget on the tail, where that diagnostic almost always
     * lands.
     */
    static String truncateErrorMessage(String message) {
        if (message == null || message.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return message;
        }
        String elision = " ... (truncated) ... ";
        int tailLength = ERROR_MESSAGE_MAX_LENGTH - TRUNCATED_HEAD_LENGTH - elision.length();
        return message.substring(0, TRUNCATED_HEAD_LENGTH) + elision + message.substring(message.length() - tailLength);
    }

    private String generatePassword() {
        int length = settingsService.provisioningRdpPasswordLength();
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                .substring(0, length);
    }
}
