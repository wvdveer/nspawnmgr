package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

public record CreateOrUpdateKeySequenceRequest(
        @NotBlank String label,
        boolean ctrl,
        boolean alt,
        boolean shift,
        boolean superKey,
        int mainKeysym
) {
}
