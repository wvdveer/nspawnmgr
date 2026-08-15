package com.nspawnmgr.web;

import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.ContainerUserActionRequest;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.AuditLogService;
import com.nspawnmgr.service.ContainerUserService;
import com.nspawnmgr.web.dto.ApproveContainerUserActionRequest;
import com.nspawnmgr.web.dto.PendingContainerUserActionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin-only approval workflow for container-user-management requests (see ContainerUserService for when these are created). */
@RestController
public class AdminContainerUserRequestApiController {

    private final ContainerUserService containerUserService;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLogService auditLogService;

    public AdminContainerUserRequestApiController(ContainerUserService containerUserService, CurrentUserProvider currentUserProvider,
                                                    AuditLogService auditLogService) {
        this.containerUserService = containerUserService;
        this.currentUserProvider = currentUserProvider;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/api/admin/container-user-requests/pending")
    public List<PendingContainerUserActionResponse> pending() {
        return containerUserService.listPendingRequests().stream().map(this::toResponse).toList();
    }

    @PostMapping("/api/admin/container-user-requests/{id}/approve")
    public void approve(@PathVariable Long id, @RequestBody(required = false) ApproveContainerUserActionRequest request) {
        String sudoPassword = request != null ? request.sudoPassword() : null;
        char[] password = (sudoPassword != null && !sudoPassword.isBlank()) ? sudoPassword.toCharArray() : null;
        User admin = currentUserProvider.get();
        ContainerUserActionRequest resolved = containerUserService.approveRequest(id, admin, password);
        auditLogService.log(admin, AuditAction.APPROVED, AuditTargetType.CONTAINER,
                resolved.getContainer().getId(), resolved.getContainer().getName(),
                "container user request: " + resolved.getActionType() + " '" + resolved.getUsername() + "'");
    }

    @PostMapping("/api/admin/container-user-requests/{id}/deny")
    public void deny(@PathVariable Long id) {
        User admin = currentUserProvider.get();
        ContainerUserActionRequest resolved = containerUserService.denyRequest(id, admin);
        auditLogService.log(admin, AuditAction.DENIED, AuditTargetType.CONTAINER,
                resolved.getContainer().getId(), resolved.getContainer().getName(),
                "container user request: " + resolved.getActionType() + " '" + resolved.getUsername() + "'");
    }

    private PendingContainerUserActionResponse toResponse(ContainerUserActionRequest request) {
        return new PendingContainerUserActionResponse(request.getId(), request.getContainer().getId(),
                request.getContainer().getName(), request.getContainer().getState() == ContainerState.RUNNING,
                request.getRequestedBy().getUsername(), request.getActionType(), request.getUsername(), request.getCreatedAt());
    }
}
