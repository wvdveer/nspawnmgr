package com.nspawnmgr.web;

import com.nspawnmgr.domain.KeySequence;
import com.nspawnmgr.service.KeySequenceService;
import com.nspawnmgr.web.dto.KeySequenceResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Plain-authenticated (not admin-only) read of the Keystroke Button Panel's key-sequence list -
 *  any user with an active session needs this to render their own container/host's panel, not
 *  just admins (who manage the list itself via {@link AdminKeySequenceApiController}). */
@RestController
public class KeySequenceApiController {

    private final KeySequenceService keySequenceService;

    public KeySequenceApiController(KeySequenceService keySequenceService) {
        this.keySequenceService = keySequenceService;
    }

    @GetMapping("/api/key-sequences")
    public List<KeySequenceResponse> list() {
        return keySequenceService.list().stream().map(this::toResponse).toList();
    }

    private KeySequenceResponse toResponse(KeySequence s) {
        return new KeySequenceResponse(s.getId(), s.getLabel(), s.isCtrl(), s.isAlt(), s.isShift(), s.isSuperKey(),
                s.getMainKeysym(), s.getSortOrder());
    }
}
