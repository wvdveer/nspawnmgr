package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

public record CreateOrUpdateScriptRequest(@NotBlank String name, @NotBlank String scriptBody) {
}
