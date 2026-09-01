package com.nspawnmgr.web;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ScriptRunResult;
import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerScript;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.DesktopManager;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.Role;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.ContainerShareRepository;
import com.nspawnmgr.repository.UserRepository;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.AuditLogService;
import com.nspawnmgr.service.ContainerAccessService;
import com.nspawnmgr.service.ContainerDiscoveryService;
import com.nspawnmgr.service.ContainerLifecycleService;
import com.nspawnmgr.service.ContainerPortMappingService;
import com.nspawnmgr.service.ContainerScriptService;
import com.nspawnmgr.service.ContainerSessionService;
import com.nspawnmgr.service.ContainerUserService;
import com.nspawnmgr.service.PackageCacheService;
import com.nspawnmgr.service.PamCredentialAuthService;
import com.nspawnmgr.service.ProvisioningService;
import com.nspawnmgr.service.ShareService;
import com.nspawnmgr.service.TemplateService;
import com.nspawnmgr.service.UserMessages;
import com.nspawnmgr.web.dto.AddContainerUserRequest;
import com.nspawnmgr.web.dto.AddOutboundAllowlistEntryRequest;
import com.nspawnmgr.web.dto.AddPortMappingRequest;
import com.nspawnmgr.web.dto.AddSshCredentialRequest;
import com.nspawnmgr.web.dto.ChangeContainerUserPasswordRequest;
import com.nspawnmgr.web.dto.ChangePrimaryAccountRequest;
import com.nspawnmgr.web.dto.ContainerStatusResponse;
import com.nspawnmgr.web.dto.ContainerUserActionResultResponse;
import com.nspawnmgr.web.dto.CreateContainerRequest;
import com.nspawnmgr.web.dto.CreateQemuVmRequest;
import com.nspawnmgr.web.dto.UpdateBootSettingsRequest;
import com.nspawnmgr.web.dto.CreateOrUpdateScriptRequest;
import com.nspawnmgr.web.dto.CreateTemplateFromMachineRequest;
import com.nspawnmgr.web.dto.UpdatePodCommandRequest;
import com.nspawnmgr.web.dto.CreatedContainerResponse;
import com.nspawnmgr.web.dto.DiscoveredContainerResponse;
import com.nspawnmgr.web.dto.OutboundAccessRequest;
import com.nspawnmgr.web.dto.OutputLineResponse;
import com.nspawnmgr.web.dto.PackageInstallResponse;
import com.nspawnmgr.web.dto.ScriptDetailResponse;
import com.nspawnmgr.web.dto.ScriptRunHandleResponse;
import com.nspawnmgr.web.dto.ScriptRunResponse;
import com.nspawnmgr.web.dto.ScriptRunStatusResponse;
import com.nspawnmgr.web.dto.ScriptSummaryResponse;
import com.nspawnmgr.web.dto.SessionUrlResponse;
import com.nspawnmgr.web.dto.ShareRequest;
import com.nspawnmgr.web.dto.TemplateSummaryResponse;
import com.nspawnmgr.web.dto.UpdateDescriptionRequest;
import com.nspawnmgr.web.dto.UpdatePackageManagerRequest;
import com.nspawnmgr.web.dto.UpdatePamAuthRequest;
import com.nspawnmgr.web.dto.UpdateRdpSecurityRequest;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
public class ContainerApiController {

    private final ContainerRepository containerRepository;
    private final ProvisioningService provisioningService;
    private final ContainerLifecycleService lifecycleService;
    private final ContainerDiscoveryService discoveryService;
    private final ContainerAccessService accessService;
    private final PackageCacheService packageCacheService;
    private final ShareService shareService;
    private final ContainerPortMappingService portMappingService;
    private final ContainerScriptService scriptService;
    private final ContainerSessionService sessionService;
    private final ContainerUserService containerUserService;
    private final TemplateService templateService;
    private final UserRepository userRepository;
    private final ContainerShareRepository containerShareRepository;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLogService auditLogService;
    private final PamCredentialAuthService pamCredentialAuthService;
    private final UserMessages messages;

    public ContainerApiController(ContainerRepository containerRepository, ProvisioningService provisioningService,
                                   ContainerLifecycleService lifecycleService, ContainerDiscoveryService discoveryService,
                                   ContainerAccessService accessService, PackageCacheService packageCacheService,
                                   ShareService shareService,
                                   ContainerPortMappingService portMappingService,
                                   ContainerScriptService scriptService,
                                   ContainerSessionService sessionService, ContainerUserService containerUserService,
                                   TemplateService templateService, UserRepository userRepository,
                                   ContainerShareRepository containerShareRepository,
                                   CurrentUserProvider currentUserProvider, AuditLogService auditLogService,
                                   PamCredentialAuthService pamCredentialAuthService, UserMessages messages) {
        this.containerRepository = containerRepository;
        this.provisioningService = provisioningService;
        this.lifecycleService = lifecycleService;
        this.discoveryService = discoveryService;
        this.accessService = accessService;
        this.packageCacheService = packageCacheService;
        this.shareService = shareService;
        this.portMappingService = portMappingService;
        this.scriptService = scriptService;
        this.sessionService = sessionService;
        this.containerUserService = containerUserService;
        this.templateService = templateService;
        this.userRepository = userRepository;
        this.containerShareRepository = containerShareRepository;
        this.currentUserProvider = currentUserProvider;
        this.auditLogService = auditLogService;
        this.pamCredentialAuthService = pamCredentialAuthService;
        this.messages = messages;
    }

    @PostMapping("/api/containers")
    public ResponseEntity<CreatedContainerResponse> create(@Valid @RequestBody CreateContainerRequest request) {
        User owner = currentUserProvider.get();
        Template template = templateService.getById(request.templateId());
        Container container = provisioningService.createPending(
                request.name(), template, owner, request.rdpEnabled(), request.vncEnabled(),
                parseDesktopManager(request.desktopManager()), request.description(), request.command());
        if (provisioningService.requiresApproval()) {
            provisioningService.markPendingApproval(container.getId());
        } else {
            provisioningService.provisionAsync(container.getId());
        }
        auditLogService.log(owner, AuditAction.CREATED, AuditTargetType.CONTAINER, container.getId(), container.getName(),
                "template=" + template.getName());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new CreatedContainerResponse(container.getId()));
    }

    /** QEMU's own creation flow - see CreateQemuVmRequest's own javadoc for why this can't reuse
     *  {@link #create}. isoPackageId is resolved to a CachedPackage up front (fail fast on a bad id,
     *  rather than discovering that mid-provisioning), same posture for templateId (also validated
     *  to actually be QEMU-backed - cloning a nspawn/podman template's tar as if it were a qcow2
     *  disk would fail confusingly deep inside provisioning otherwise). Exactly one of diskSizeGb/
     *  templateId is required - CreateQemuVmRequest itself can't express that as bean validation. */
    @PostMapping("/api/containers/qemu")
    public ResponseEntity<CreatedContainerResponse> createQemu(@Valid @RequestBody CreateQemuVmRequest request) {
        User owner = currentUserProvider.get();
        if ((request.diskSizeGb() == null) == (request.templateId() == null)) {
            throw new IllegalArgumentException(messages.get("error.web.specifyExactlyOne"));
        }
        Template template = null;
        if (request.templateId() != null) {
            template = templateService.getById(request.templateId());
            if (template.getBackend() != ContainerBackend.QEMU) {
                throw new IllegalArgumentException(messages.get("error.web.templateNotQemuBacked", template.getName()));
            }
        }
        CachedPackage iso = request.isoPackageId() != null ? packageCacheService.getById(request.isoPackageId()) : null;
        Container container = provisioningService.createPendingQemu(
                request.name(), owner, request.description(), request.diskSizeGb(), template, iso,
                request.cpuModel(), request.cpuCount(), request.memoryMb(), request.nicModel(), request.pointerDevice());
        if (provisioningService.requiresApproval()) {
            provisioningService.markPendingApproval(container.getId());
        } else {
            provisioningService.provisionAsync(container.getId());
        }
        auditLogService.log(owner, AuditAction.CREATED, AuditTargetType.CONTAINER, container.getId(), container.getName(),
                "backend=QEMU");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new CreatedContainerResponse(container.getId()));
    }

    /** Admin-only: finds machinectl images not yet tracked in the DB and registers them as MANAGED
     *  containers owned by whoever ran discovery — see ContainerDiscoveryService for scope/limits. */
    @PostMapping("/api/containers/discover")
    public List<DiscoveredContainerResponse> discover() {
        User admin = currentUserProvider.get();
        if (admin.getRole() != Role.ADMIN) {
            throw new AccessDeniedException(messages.get("error.web.onlyAdminDiscoverMachines"));
        }
        List<Container> discovered = discoveryService.discover(admin);
        discovered.forEach(c -> auditLogService.log(admin, AuditAction.CREATED, AuditTargetType.CONTAINER,
                c.getId(), c.getName(), "discovered on host (not created by nspawnmgr)"));
        return discovered.stream()
                .map(c -> new DiscoveredContainerResponse(c.getId(), c.getName(), c.getState().name()))
                .toList();
    }

    @GetMapping("/api/containers/{id}/status")
    public ContainerStatusResponse status(@PathVariable Long id) {
        Container container = requireVisible(id);
        return new ContainerStatusResponse(container.getState(), container.getErrorMessage());
    }

    @PostMapping("/api/containers/{id}/start")
    public void start(@PathVariable Long id) {
        Container container = requireOwned(id);
        lifecycleService.start(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.STARTED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), null);
    }

    @PostMapping("/api/containers/{id}/stop")
    public void stop(@PathVariable Long id) {
        Container container = requireOwned(id);
        lifecycleService.stopGraceful(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.STOPPED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), null);
    }

    @PostMapping("/api/containers/{id}/restart")
    public void restart(@PathVariable Long id) {
        Container container = requireOwned(id);
        lifecycleService.restart(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.RESTARTED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), null);
    }

    @PostMapping("/api/containers/{id}/force-stop")
    public void forceStop(@PathVariable Long id) {
        Container container = requireOwned(id);
        lifecycleService.stopForce(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.FORCE_STOPPED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), null);
    }

    @PostMapping("/api/containers/{id}/pause")
    public void pause(@PathVariable Long id) {
        Container container = requireOwned(id);
        lifecycleService.pause(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.PAUSED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), null);
    }

    @PostMapping("/api/containers/{id}/resume")
    public void resume(@PathVariable Long id) {
        Container container = requireOwned(id);
        lifecycleService.resume(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.RESUMED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), null);
    }

    /**
     * Owner-only: packs this container's current rootfs into a brand-new, independent Template row
     * (see TemplateService#createFromMachine) - only valid while STOPPED, since packing a live
     * rootfs risks an inconsistent archive as files change mid-tar. Same sudo-password requirement
     * as any other template-producing operation (see TemplateService's own javadoc); like {@link
     * #installPackage}, this always uses the stored sudo secret rather than accepting a per-request
     * override - admin-approval mode isn't supported for this endpoint yet.
     */
    @PostMapping("/api/containers/{id}/create-template")
    public void createTemplateFromMachine(@PathVariable Long id, @RequestBody CreateTemplateFromMachineRequest request) {
        Container container = requireOwned(id);
        if (container.getState() != ContainerState.STOPPED) {
            throw new IllegalStateException(messages.get("error.web.mustBeStoppedToCreateTemplate"));
        }
        Template template = templateService.createFromMachine(request.templateName(), request.description(), container, null);
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.TEMPLATE,
                template.getId(), template.getName(), "from container " + container.getName());
    }

    /** As {@link #createTemplateFromMachine}, no admin-approval sudo-password override support yet
     *  either - same posture, same reasoning. */
    @PutMapping("/api/containers/{id}/pod-command")
    public void updatePodCommand(@PathVariable Long id, @Valid @RequestBody UpdatePodCommandRequest request) {
        Container container = requireOwned(id);
        provisioningService.updatePodCommand(id, request.command(), null);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "pod command changed");
    }

    @DeleteMapping("/api/containers/{id}")
    public void delete(@PathVariable Long id) {
        Container container = requireOwned(id);
        Long containerId = container.getId();
        String containerName = container.getName();
        lifecycleService.delete(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.DELETED, AuditTargetType.CONTAINER,
                containerId, containerName, null);
    }

    @PostMapping("/api/containers/{id}/take-ownership")
    public void takeOwnership(@PathVariable Long id) {
        Container container = requireAdmin(id);
        User admin = currentUserProvider.get();
        String previousOwnerUsername = container.getOwner().getUsername();
        lifecycleService.takeOwnership(container, admin);
        auditLogService.log(admin, AuditAction.UPDATED, AuditTargetType.CONTAINER, container.getId(), container.getName(),
                "ownership transferred from '" + previousOwnerUsername + "' to '" + admin.getUsername() + "'");
    }

    @PutMapping("/api/containers/{id}/description")
    public void setDescription(@PathVariable Long id, @Valid @RequestBody UpdateDescriptionRequest request) {
        Container container = requireOwned(id);
        lifecycleService.setDescription(container, request.description());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "description changed");
    }

    /** Owner-only: sets the package-manager fallback for a MANAGED container with no template - see
     *  ContainerLifecycleService.setPackageManager. */
    @PutMapping("/api/containers/{id}/package-manager")
    public void setPackageManager(@PathVariable Long id, @Valid @RequestBody UpdatePackageManagerRequest request) {
        Container container = requireOwned(id);
        PackageManager packageManager = parsePackageManager(request.packageManager());
        lifecycleService.setPackageManager(container, packageManager);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "package manager set to " + packageManager);
    }

    /** Owner-only: sets whether this MANAGED container auto-starts when the HOST itself boots, and
     *  which other machine (if any) it requires already started first - see
     *  ContainerLifecycleService.setBootSettings. */
    @PutMapping("/api/containers/{id}/boot-settings")
    public void setBootSettings(@PathVariable Long id, @Valid @RequestBody UpdateBootSettingsRequest request) {
        Container container = requireOwned(id);
        lifecycleService.setBootSettings(container, request.autoStart(), request.requiresContainerName());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "boot settings changed: autoStart=" + request.autoStart()
                        + " requires=" + request.requiresContainerName());
    }

    @PostMapping("/api/containers/{id}/shares")
    public void addShare(@PathVariable Long id, @Valid @RequestBody ShareRequest request) {
        Container container = requireOwned(id);
        User target = userRepository.findByUsernameIgnoreCase(request.username())
                .orElseThrow(() -> new IllegalArgumentException(messages.get("error.common.noSuchUser", request.username())));
        shareService.grantAccess(container, target);
        auditLogService.log(currentUserProvider.get(), AuditAction.SHARED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "with " + target.getUsername());
    }

    @DeleteMapping("/api/containers/{id}/shares/{userId}")
    public void removeShare(@PathVariable Long id, @PathVariable Long userId) {
        Container container = requireOwned(id);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(messages.get("error.common.noSuchUser", userId)));
        shareService.revokeAccess(container, target);
        auditLogService.log(currentUserProvider.get(), AuditAction.UNSHARED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "from " + target.getUsername());
    }

    @PostMapping("/api/containers/{id}/port-mappings")
    public void addPortMapping(@PathVariable Long id, @Valid @RequestBody AddPortMappingRequest request) {
        Container container = requireOwned(id);
        portMappingService.addMapping(container, request.hostPort(), request.containerPort(), request.protocol());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "port mapping added: " + request.hostPort() + "->"
                        + request.containerPort() + "/" + request.protocol());
    }

    @DeleteMapping("/api/containers/{id}/port-mappings/{mappingId}")
    public void removePortMapping(@PathVariable Long id, @PathVariable Long mappingId) {
        Container container = requireOwned(id);
        portMappingService.removeMapping(container, mappingId);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "port mapping removed");
    }

    @GetMapping("/api/containers/{id}/scripts")
    public List<ScriptSummaryResponse> listScripts(@PathVariable Long id) {
        Container container = requireOwnedOrShared(id);
        return scriptService.list(container).stream()
                .map(s -> new ScriptSummaryResponse(s.getId(), s.getName()))
                .toList();
    }

    @GetMapping("/api/containers/{id}/scripts/{scriptId}")
    public ScriptDetailResponse getScript(@PathVariable Long id, @PathVariable Long scriptId) {
        Container container = requireOwnedOrShared(id);
        ContainerScript script = scriptService.get(container, scriptId);
        return new ScriptDetailResponse(script.getId(), script.getName(), script.getScriptBody());
    }

    @PostMapping("/api/containers/{id}/scripts")
    public ScriptDetailResponse createScript(@PathVariable Long id, @Valid @RequestBody CreateOrUpdateScriptRequest request) {
        Container container = requireOwnedOrShared(id);
        ContainerScript script = scriptService.create(container, request.name(), request.scriptBody());
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "script '" + script.getName() + "' created");
        return new ScriptDetailResponse(script.getId(), script.getName(), script.getScriptBody());
    }

    @PutMapping("/api/containers/{id}/scripts/{scriptId}")
    public ScriptDetailResponse updateScript(@PathVariable Long id, @PathVariable Long scriptId,
                                              @Valid @RequestBody CreateOrUpdateScriptRequest request) {
        Container container = requireOwnedOrShared(id);
        ContainerScript script = scriptService.update(container, scriptId, request.name(), request.scriptBody());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "script '" + script.getName() + "' updated");
        return new ScriptDetailResponse(script.getId(), script.getName(), script.getScriptBody());
    }

    @DeleteMapping("/api/containers/{id}/scripts/{scriptId}")
    public void deleteScript(@PathVariable Long id, @PathVariable Long scriptId) {
        Container container = requireOwnedOrShared(id);
        ContainerScript script = scriptService.get(container, scriptId);
        String scriptName = script.getName();
        scriptService.delete(container, scriptId);
        auditLogService.log(currentUserProvider.get(), AuditAction.DELETED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "script '" + scriptName + "' deleted");
    }

    /** Saves the current body under an existing script, then runs it — the page's single "Execute" action. */
    @PostMapping("/api/containers/{id}/scripts/{scriptId}/run")
    public ScriptRunResponse updateAndRunScript(@PathVariable Long id, @PathVariable Long scriptId,
                                                 @Valid @RequestBody CreateOrUpdateScriptRequest request) {
        Container container = requireOwnedOrShared(id);
        ContainerScript script = scriptService.update(container, scriptId, request.name(), request.scriptBody());
        return runAndAudit(container, script);
    }

    /** Creates a brand-new (never-yet-saved) script, then runs it — Execute from a fresh "new script" page. */
    @PostMapping("/api/containers/{id}/scripts/run")
    public ScriptRunResponse createAndRunScript(@PathVariable Long id, @Valid @RequestBody CreateOrUpdateScriptRequest request) {
        Container container = requireOwnedOrShared(id);
        ContainerScript script = scriptService.create(container, request.name(), request.scriptBody());
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "script '" + script.getName() + "' created");
        return runAndAudit(container, script);
    }

    private ScriptRunResponse runAndAudit(Container container, ContainerScript script) {
        ScriptRunResult result = scriptService.run(container, script.getScriptBody());
        auditLogService.log(currentUserProvider.get(), AuditAction.RAN, AuditTargetType.CONTAINER,
                container.getId(), container.getName(),
                "script '" + script.getName() + "' run (exit " + result.exitCode() + ")");
        List<OutputLineResponse> lines = result.lines().stream()
                .map(l -> new OutputLineResponse(l.timestamp(), l.source().name(), l.text()))
                .toList();
        return new ScriptRunResponse(script.getId(), result.exitCode(), lines);
    }

    /** Abortable counterpart to {@link #updateAndRunScript} — starts the run and returns immediately. */
    @PostMapping("/api/containers/{id}/scripts/{scriptId}/run-async")
    public ResponseEntity<ScriptRunHandleResponse> updateAndRunScriptAsync(@PathVariable Long id, @PathVariable Long scriptId,
                                                 @Valid @RequestBody CreateOrUpdateScriptRequest request) {
        Container container = requireOwnedOrShared(id);
        ContainerScript script = scriptService.update(container, scriptId, request.name(), request.scriptBody());
        return startAsync(container, script);
    }

    /** Abortable counterpart to {@link #createAndRunScript} — starts the run and returns immediately. */
    @PostMapping("/api/containers/{id}/scripts/run-async")
    public ResponseEntity<ScriptRunHandleResponse> createAndRunScriptAsync(@PathVariable Long id,
                                                 @Valid @RequestBody CreateOrUpdateScriptRequest request) {
        Container container = requireOwnedOrShared(id);
        ContainerScript script = scriptService.create(container, request.name(), request.scriptBody());
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "script '" + script.getName() + "' created");
        return startAsync(container, script);
    }

    private ResponseEntity<ScriptRunHandleResponse> startAsync(Container container, ContainerScript script) {
        String runId = scriptService.startRun(container, script.getScriptBody(), currentUserProvider.get());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ScriptRunHandleResponse(runId, script.getId()));
    }

    @GetMapping("/api/containers/{id}/scripts/runs/{runId}")
    public ScriptRunStatusResponse scriptRunStatus(@PathVariable Long id, @PathVariable String runId) {
        Container container = requireOwnedOrShared(id);
        ContainerScriptService.ActiveRun activeRun = scriptService.getStatus(container, runId);
        ScriptRunResult result = activeRun.result();
        List<OutputLineResponse> lines = result == null ? List.of() : result.lines().stream()
                .map(l -> new OutputLineResponse(l.timestamp(), l.source().name(), l.text()))
                .toList();
        Integer exitCode = result == null ? null : result.exitCode();
        return new ScriptRunStatusResponse(runId, activeRun.state().name(), exitCode, lines);
    }

    @PostMapping("/api/containers/{id}/scripts/runs/{runId}/abort")
    public ResponseEntity<Void> abortScriptRun(@PathVariable Long id, @PathVariable String runId) {
        Container container = requireOwnedOrShared(id);
        scriptService.abort(container, runId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/containers/{id}/outbound")
    public void setOutboundAccess(@PathVariable Long id, @Valid @RequestBody OutboundAccessRequest request) {
        Container container = requireOwned(id);
        lifecycleService.setOutboundEnabled(container, request.enabled());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "outbound access " + (request.enabled() ? "enabled" : "disabled"));
    }

    @PostMapping("/api/containers/{id}/outbound/allowlist")
    public void addOutboundAllowlistEntry(@PathVariable Long id, @Valid @RequestBody AddOutboundAllowlistEntryRequest request) {
        Container container = requireOwned(id);
        lifecycleService.addOutboundAllowlistEntry(container, request.destinationHost(), request.destinationPort(), request.protocol());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "outbound allowlist added: " + request.destinationHost() + ":"
                        + request.destinationPort() + "/" + request.protocol());
    }

    @DeleteMapping("/api/containers/{id}/outbound/allowlist/{entryId}")
    public void removeOutboundAllowlistEntry(@PathVariable Long id, @PathVariable Long entryId) {
        Container container = requireOwned(id);
        lifecycleService.removeOutboundAllowlistEntry(container, entryId);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "outbound allowlist entry removed");
    }

    /** Owner-only: manually records SSH access details nspawnmgr never itself provisioned - the
     *  escape hatch for a container stuck in BOOTING forever because it has no SSH_KEY credential
     *  for ContainerReadinessPollingService to authenticate with (see ProvisioningService's own
     *  javadoc). Rejects if nspawnmgr already has SSH credentials recorded for this container. */
    @PostMapping("/api/containers/{id}/credentials/ssh")
    public void addSshCredential(@PathVariable Long id, @Valid @RequestBody AddSshCredentialRequest request) {
        Container container = requireOwned(id);
        provisioningService.recordManualSshCredential(container, request.accountName(), request.privateKeyPem());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "SSH credentials recorded manually");
    }

    /** Owner-only: wires a prompt-credentials Guacamole SSH connection, only if nspawnmgr has no
     *  generated SSH credential for this container and its port 22 is actually reachable. */
    @PostMapping("/api/containers/{id}/access/ssh")
    public void enableSshAccess(@PathVariable Long id) {
        Container container = requireOwned(id);
        accessService.enableSsh(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "SSH access enabled (prompt-credentials)");
    }

    @DeleteMapping("/api/containers/{id}/access/ssh")
    public void disableSshAccess(@PathVariable Long id) {
        Container container = requireOwned(id);
        accessService.disableSsh(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "SSH access disabled");
    }

    /** Owner-only: wires a prompt-credentials Guacamole RDP connection, only if nspawnmgr has no
     *  generated RDP credential for this container and its port 3389 is actually reachable. */
    @PostMapping("/api/containers/{id}/access/rdp")
    public void enableRdpAccess(@PathVariable Long id) {
        Container container = requireOwned(id);
        accessService.enableRdp(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "RDP access enabled (prompt-credentials)");
    }

    @DeleteMapping("/api/containers/{id}/access/rdp")
    public void disableRdpAccess(@PathVariable Long id) {
        Container container = requireOwned(id);
        accessService.disableRdp(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "RDP access disabled");
    }

    @PutMapping("/api/containers/{id}/rdp-security")
    public void setRdpSecurity(@PathVariable Long id, @Valid @RequestBody UpdateRdpSecurityRequest request) {
        Container container = requireOwned(id);
        accessService.setRdpSecurity(container, request.security());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "RDP security set to " + request.security());
    }

    /** Owner-only: configures pam_nspawnmgr, the replacement local-login check that bypasses this
     *  container's own /etc/shadow — see PamCredentialAuthService's own javadoc. */
    @PutMapping("/api/containers/{id}/pam-auth")
    public void setPamAuth(@PathVariable Long id, @Valid @RequestBody UpdatePamAuthRequest request) {
        Container container = requireOwned(id);
        pamCredentialAuthService.updateSettings(container, request.source(), request.services());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "PAM auth set to source=" + request.source()
                        + " services=" + request.services());
    }

    /** Owner-only: wires a prompt-credentials Guacamole VNC connection, only if nspawnmgr has no
     *  generated VNC credential for this container and its port 5900 is actually reachable. */
    @PostMapping("/api/containers/{id}/access/vnc")
    public void enableVncAccess(@PathVariable Long id) {
        Container container = requireOwned(id);
        accessService.enableVnc(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "VNC access enabled (prompt-credentials)");
    }

    @DeleteMapping("/api/containers/{id}/access/vnc")
    public void disableVncAccess(@PathVariable Long id) {
        Container container = requireOwned(id);
        accessService.disableVnc(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "VNC access disabled");
    }

    /** Owner-only: installs a cached package (see PackageCacheService) onto this container - rejects
     *  a package-manager mismatch, and returns the install command's captured output so a missing
     *  dependency is visible rather than silently swallowed. */
    @PostMapping("/api/containers/{id}/packages/{cachedPackageId}/install")
    public PackageInstallResponse installPackage(@PathVariable Long id, @PathVariable Long cachedPackageId) {
        Container container = requireOwned(id);
        CommandResult result = packageCacheService.installOnContainer(container, cachedPackageId, null);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(),
                "package " + cachedPackageId + " installed (exit " + result.exitCode() + ")");
        return new PackageInstallResponse(result.exitCode(), result.stdout(), result.stderr());
    }

    /** Owner-only: configures an uploaded ISO (see PackageCacheService) to be mounted at this
     *  container's fixed /mnt/cdrom - a persistent setting, same as port mappings, that takes
     *  effect on the container's next (re)start rather than live; auto-ejects whatever's already
     *  configured first, see ContainerLifecycleService.mountIso. */
    @PostMapping("/api/containers/{id}/iso/{isoImageId}/mount")
    public void mountIso(@PathVariable Long id, @PathVariable Long isoImageId) {
        Container container = requireOwned(id);
        CachedPackage iso = packageCacheService.getById(isoImageId);
        lifecycleService.mountIso(container, iso);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "ISO mounted: " + iso.getOriginalFilename());
    }

    /** Owner-only: ejects whatever ISO is currently mounted, if any - a no-op (not an error) if
     *  nothing is mounted. */
    @DeleteMapping("/api/containers/{id}/iso")
    public void ejectIso(@PathVariable Long id) {
        Container container = requireOwned(id);
        lifecycleService.ejectIso(container);
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "ISO ejected");
    }

    @PostMapping("/api/containers/{id}/session/ssh")
    public SessionUrlResponse sessionSsh(@PathVariable Long id, HttpServletRequest request) {
        Container container = requireOwnedOrSharedForConnect(id);
        User user = currentUserProvider.get();
        String url = sessionService.startSshSession(container, user, browserOrigin(request));
        auditLogService.log(user, AuditAction.SESSION_STARTED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "protocol=ssh");
        return new SessionUrlResponse(url);
    }

    @PostMapping("/api/containers/{id}/session/rdp")
    public SessionUrlResponse sessionRdp(@PathVariable Long id, HttpServletRequest request) {
        Container container = requireOwnedOrSharedForConnect(id);
        User user = currentUserProvider.get();
        String url = sessionService.startRdpSession(container, user, browserOrigin(request));
        auditLogService.log(user, AuditAction.SESSION_STARTED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "protocol=rdp");
        return new SessionUrlResponse(url);
    }

    @PostMapping("/api/containers/{id}/session/vnc")
    public SessionUrlResponse sessionVnc(@PathVariable Long id, HttpServletRequest request) {
        Container container = requireOwnedOrSharedForConnect(id);
        User user = currentUserProvider.get();
        String url = sessionService.startVncSession(container, user, browserOrigin(request));
        auditLogService.log(user, AuditAction.SESSION_STARTED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), "protocol=vnc");
        return new SessionUrlResponse(url);
    }

    @GetMapping("/api/containers/{id}/users")
    public List<String> listUsers(@PathVariable Long id) {
        return containerUserService.listUsers(requireVisible(id));
    }

    @PostMapping("/api/containers/{id}/users")
    public ContainerUserActionResultResponse addUser(@PathVariable Long id, @Valid @RequestBody AddContainerUserRequest request) {
        Container container = requireOwned(id);
        User actor = currentUserProvider.get();
        boolean pending = containerUserService.addUser(container, actor, request.username(), request.password());
        auditLogService.log(actor, AuditAction.UPDATED, AuditTargetType.CONTAINER, container.getId(), container.getName(),
                "container user '" + request.username() + "' " + (pending ? "requested (pending approval)" : "added"));
        return new ContainerUserActionResultResponse(pending);
    }

    @PutMapping("/api/containers/{id}/users/{username}/password")
    public ContainerUserActionResultResponse changeUserPassword(@PathVariable Long id, @PathVariable String username,
                                                                  @Valid @RequestBody ChangeContainerUserPasswordRequest request) {
        Container container = requireOwned(id);
        User actor = currentUserProvider.get();
        boolean pending = containerUserService.changePassword(container, actor, username, request.password());
        auditLogService.log(actor, AuditAction.UPDATED, AuditTargetType.CONTAINER, container.getId(), container.getName(),
                "password changed for container user '" + username + "'" + (pending ? " (pending approval)" : ""));
        return new ContainerUserActionResultResponse(pending);
    }

    /** Owner-only: re-points this container's generated SSH/RDP/VNC credentials at any account
     *  already shown in "Container users" — see ContainerUserService.changePrimaryAccount. */
    @PutMapping("/api/containers/{id}/primary-account")
    public ContainerUserActionResultResponse changePrimaryAccount(@PathVariable Long id, @Valid @RequestBody ChangePrimaryAccountRequest request) {
        Container container = requireOwned(id);
        User actor = currentUserProvider.get();
        boolean pending = containerUserService.changePrimaryAccount(container, actor, request.accountName());
        auditLogService.log(actor, AuditAction.UPDATED, AuditTargetType.CONTAINER, container.getId(), container.getName(),
                "primary account changed to '" + request.accountName() + "'" + (pending ? " (pending approval)" : ""));
        return new ContainerUserActionResultResponse(pending);
    }

    @GetMapping("/api/templates")
    public List<TemplateSummaryResponse> listTemplates() {
        return templateService.listActive().stream()
                .map(t -> new TemplateSummaryResponse(t.getId(), t.getName(), t.getRdpState()))
                .toList();
    }

    @GetMapping("/api/users/search")
    public List<String> searchUsers(@RequestParam String q) {
        return userRepository.findByUsernameContainingIgnoreCase(q).stream()
                .map(User::getUsername)
                .filter(Objects::nonNull)
                .toList();
    }

    private Container requireOwned(Long id) {
        Container container = findOrThrow(id);
        User user = currentUserProvider.get();
        if (!container.getOwner().getId().equals(user.getId())) {
            throw new AccessDeniedException(messages.get("error.web.onlyOwnerMayPerform"));
        }
        return container;
    }

    private Container requireVisible(Long id) {
        return findOrThrow(id);
    }

    /**
     * scheme://host:port this request actually arrived on - see GuacamoleSessionService's own
     * comment for why the browser-facing session URL is built from this instead of the configured
     * guacamoleBaseUrl setting.
     */
    private static String browserOrigin(HttpServletRequest request) {
        return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
    }

    /**
     * Owner, or a user the container has been shared with (see ContainerShare / "Shared with") —
     * used for container scripts, where "approved user" means whoever the owner already trusts
     * enough to share the container with, per docs/administrator-guide.md's trust-boundary section.
     */
    private Container requireOwnedOrShared(Long id) {
        Container container = findOrThrow(id);
        User user = currentUserProvider.get();
        if (container.getOwner().getId().equals(user.getId())) {
            return container;
        }
        if (containerShareRepository.existsByContainerAndUser(container, user)) {
            return container;
        }
        throw new AccessDeniedException(messages.get("error.web.onlyOwnerOrSharedMayPerform"));
    }

    /**
     * Same gate as {@link #requireOwnedOrShared}, worded for the connect flow specifically -
     * previously the session-start endpoints below used {@link #requireVisible} (every user could
     * mint a real Guacamole session for every container, silently auto-granting access on
     * connect - see ShareService.grantAccess), so an unshared user got a bare 500 from
     * GuacamoleSessionService rather than any indication the container simply wasn't shared with
     * them. session.js's own error handling already surfaces a plain-text 4xx body verbatim
     * ("Failed to start session: " + response.text()), so this message reaches the user directly.
     */
    private Container requireOwnedOrSharedForConnect(Long id) {
        Container container = findOrThrow(id);
        User user = currentUserProvider.get();
        if (container.getOwner().getId().equals(user.getId())) {
            return container;
        }
        if (containerShareRepository.existsByContainerAndUser(container, user)) {
            return container;
        }
        throw new AccessDeniedException(messages.get("error.web.notSharedWithYou"));
    }

    // Eager-fetch owner variant of findOrThrow - takeOwnership reads container.getOwner()
    // (for the audit log's "transferred from X" message) after this method returns, which a plain
    // findOrThrow's lazy owner proxy can't survive: this app runs with open-in-view off, so by the
    // time that line runs the Hibernate session that would have resolved the proxy is already
    // closed - LazyInitializationException("no Session"), confirmed live (real 500 on a real
    // take-ownership click, not caught by any of ApiExceptionHandler's typed handlers).
    private Container requireAdmin(Long id) {
        Container container = containerRepository.findByIdWithTemplate(id)
                .orElseThrow(() -> new IllegalArgumentException(messages.get("error.common.noSuchContainer", id)));
        User user = currentUserProvider.get();
        if (user.getRole() != Role.ADMIN) {
            throw new AccessDeniedException(messages.get("error.web.onlyAdminTakeOwnership"));
        }
        return container;
    }

    private DesktopManager parseDesktopManager(String value) {
        if (value == null || value.isBlank()) {
            return DesktopManager.NONE;
        }
        try {
            return DesktopManager.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(messages.get("error.web.invalidDesktopManager", value));
        }
    }

    private PackageManager parsePackageManager(String value) {
        try {
            return PackageManager.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(messages.get("error.web.invalidPackageManager", value));
        }
    }

    private Container findOrThrow(Long id) {
        return containerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(messages.get("error.common.noSuchContainer", id)));
    }
}
