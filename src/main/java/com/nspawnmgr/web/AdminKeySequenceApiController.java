package com.nspawnmgr.web;

import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.KeySequence;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.AuditLogService;
import com.nspawnmgr.service.KeySequenceService;
import com.nspawnmgr.web.dto.CreateOrUpdateKeySequenceRequest;
import com.nspawnmgr.web.dto.KeySequenceResponse;
import com.nspawnmgr.web.dto.ReorderKeySequencesRequest;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin CRUD for the Keystroke Button Panel's global key-sequence list - see
 *  {@link com.nspawnmgr.web.KeySequenceApiController} for the plain-authenticated read-only
 *  endpoint every session page (not just admins) uses to render its own panel. */
@RestController
public class AdminKeySequenceApiController {

    private final KeySequenceService keySequenceService;
    private final AuditLogService auditLogService;
    private final CurrentUserProvider currentUserProvider;

    public AdminKeySequenceApiController(KeySequenceService keySequenceService, AuditLogService auditLogService,
                                          CurrentUserProvider currentUserProvider) {
        this.keySequenceService = keySequenceService;
        this.auditLogService = auditLogService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/api/admin/key-sequences")
    public List<KeySequenceResponse> list() {
        return keySequenceService.list().stream().map(this::toResponse).toList();
    }

    @PostMapping("/api/admin/key-sequences")
    public KeySequenceResponse create(@Valid @RequestBody CreateOrUpdateKeySequenceRequest request) {
        KeySequence sequence = keySequenceService.create(request.label(), request.ctrl(), request.alt(),
                request.shift(), request.superKey(), request.mainKeysym());
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.KEY_SEQUENCE,
                sequence.getId(), sequence.getLabel(), null);
        return toResponse(sequence);
    }

    @PutMapping("/api/admin/key-sequences/{id}")
    public KeySequenceResponse update(@PathVariable Long id, @Valid @RequestBody CreateOrUpdateKeySequenceRequest request) {
        KeySequence sequence = keySequenceService.update(id, request.label(), request.ctrl(), request.alt(),
                request.shift(), request.superKey(), request.mainKeysym());
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.KEY_SEQUENCE,
                sequence.getId(), sequence.getLabel(), null);
        return toResponse(sequence);
    }

    @PostMapping("/api/admin/key-sequences/reorder")
    public void reorder(@Valid @RequestBody ReorderKeySequencesRequest request) {
        keySequenceService.reorder(request.orderedIds());
    }

    @DeleteMapping("/api/admin/key-sequences/{id}")
    public void delete(@PathVariable Long id) {
        KeySequence sequence = keySequenceService.list().stream().filter(s -> s.getId().equals(id)).findFirst()
                .orElse(null);
        keySequenceService.delete(id);
        if (sequence != null) {
            auditLogService.log(currentUserProvider.get(), AuditAction.DELETED, AuditTargetType.KEY_SEQUENCE,
                    id, sequence.getLabel(), null);
        }
    }

    private KeySequenceResponse toResponse(KeySequence s) {
        return new KeySequenceResponse(s.getId(), s.getLabel(), s.isCtrl(), s.isAlt(), s.isShift(), s.isSuperKey(),
                s.getMainKeysym(), s.getSortOrder());
    }
}
