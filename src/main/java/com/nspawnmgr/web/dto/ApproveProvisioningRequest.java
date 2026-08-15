package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

public record ApproveProvisioningRequest(@NotBlank String sudoPassword) {
}
