package com.nspawnmgr.web;

import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.AuditLogService;
import com.nspawnmgr.service.ProvisioningService;
import com.nspawnmgr.web.dto.ApproveProvisioningRequest;
import com.nspawnmgr.web.dto.PendingContainerResponse;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only approval workflow for container-creation requests in approval mode (no stored sudo secret configured). */
@RestController
public class AdminProvisioningApiController {

    private final ContainerRepository containerRepository;
    private final ProvisioningService provisioningService;
    private final AuditLogService auditLogService;
    private final CurrentUserProvider currentUserProvider;

    public AdminProvisioningApiController(ContainerRepository containerRepository, ProvisioningService provisioningService,
                                           AuditLogService auditLogService, CurrentUserProvider currentUserProvider) {
        this.containerRepository = containerRepository;
        this.provisioningService = provisioningService;
        this.auditLogService = auditLogService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/api/admin/containers/pending")
    public List<PendingContainerResponse> pending() {
        return containerRepository.findByStateWithOwnerAndTemplate(ContainerState.PENDING_APPROVAL).stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping("/api/admin/containers/{id}/approve")
    public void approve(@PathVariable Long id, @Valid @RequestBody ApproveProvisioningRequest request) {
        char[] password = request.sudoPassword().toCharArray();
        Container container = containerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + id));
        provisioningService.approve(id);
        provisioningService.provisionAsync(id, password);
        auditLogService.log(currentUserProvider.get(), AuditAction.APPROVED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), null);
    }

    @PostMapping("/api/admin/containers/{id}/deny")
    public void deny(@PathVariable Long id) {
        Container container = containerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + id));
        provisioningService.deny(id);
        auditLogService.log(currentUserProvider.get(), AuditAction.DENIED, AuditTargetType.CONTAINER,
                container.getId(), container.getName(), null);
    }

    private PendingContainerResponse toResponse(Container container) {
        return new PendingContainerResponse(container.getId(), container.getName(),
                container.getOwner().getUsername(), container.getTemplate().getName(), container.getCreatedAt());
    }
}
