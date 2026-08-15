package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

public record ChangeContainerUserPasswordRequest(@NotBlank String password) {
}
