package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

public record UpdateUserRoleRequest(@NotBlank String role) {
}
