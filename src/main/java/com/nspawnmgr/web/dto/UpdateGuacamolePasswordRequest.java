package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public record UpdateGuacamolePasswordRequest(
        @NotBlank @Size(min = 8, max = 128) String password
) {
}
