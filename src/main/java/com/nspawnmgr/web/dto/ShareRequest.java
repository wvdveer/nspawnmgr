package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

public record ShareRequest(@NotBlank String username) {
}
