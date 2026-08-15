package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

public record UpdatePackageManagerRequest(@NotBlank String packageManager) {
}
