package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

public record ChangePrimaryAccountRequest(
        @NotBlank @Pattern(regexp = "^[a-z_][a-z0-9_-]{0,31}$", message = "{validation.username.mustBeValidLinuxUsername}") String accountName
) {
}
