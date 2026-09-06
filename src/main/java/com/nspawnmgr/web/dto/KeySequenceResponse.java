package com.nspawnmgr.web.dto;

public record KeySequenceResponse(
        Long id,
        String label,
        boolean ctrl,
        boolean alt,
        boolean shift,
        boolean superKey,
        int mainKeysym,
        int sortOrder
) {
}
