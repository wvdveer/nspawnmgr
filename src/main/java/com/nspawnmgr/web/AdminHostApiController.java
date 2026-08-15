package com.nspawnmgr.web;

import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.AuditLogService;
import com.nspawnmgr.service.HostService;
import com.nspawnmgr.web.dto.CreateOrUpdateHostRequest;
import com.nspawnmgr.web.dto.HostResponse;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AdminHostApiController {

    private final HostService hostService;
    private final AuditLogService auditLogService;
    private final CurrentUserProvider currentUserProvider;

    public AdminHostApiController(HostService hostService, AuditLogService auditLogService,
                                   CurrentUserProvider currentUserProvider) {
        this.hostService = hostService;
        this.auditLogService = auditLogService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/api/admin/hosts")
    public List<HostResponse> list() {
        return hostService.listAll().stream().map(this::toResponse).toList();
    }

    @PostMapping("/api/admin/hosts")
    public HostResponse create(@Valid @RequestBody CreateOrUpdateHostRequest request) {
        Container host = hostService.create(request.name(), request.hostname(), request.ownerUsername(),
                request.sshEnabled(), request.sshPort(), request.rdpEnabled(), request.rdpPort(),
                request.vncEnabled(), request.vncPort());
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.CONTAINER,
                host.getId(), host.getName(), "host");
        return toResponse(host);
    }

    @PutMapping("/api/admin/hosts/{id}")
    public HostResponse update(@PathVariable Long id, @Valid @RequestBody CreateOrUpdateHostRequest request) {
        Container host = hostService.update(id, request.name(), request.hostname(), request.ownerUsername(),
                request.sshEnabled(), request.sshPort(), request.rdpEnabled(), request.rdpPort(),
                request.vncEnabled(), request.vncPort());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.CONTAINER,
                host.getId(), host.getName(), "host");
        return toResponse(host);
    }

    @DeleteMapping("/api/admin/hosts/{id}")
    public void delete(@PathVariable Long id) {
        Container host = hostService.getById(id);
        hostService.delete(id);
        auditLogService.log(currentUserProvider.get(), AuditAction.DELETED, AuditTargetType.CONTAINER,
                host.getId(), host.getName(), "host");
    }

    private HostResponse toResponse(Container host) {
        return new HostResponse(host.getId(), host.getName(), host.getHostname(), host.getOwner().getUsername(),
                host.getExternalSshPort() != null, host.getExternalSshPort(),
                host.isRdpEnabled(), host.getExternalRdpPort(),
                host.isVncEnabled(), host.getExternalVncPort());
    }
}
