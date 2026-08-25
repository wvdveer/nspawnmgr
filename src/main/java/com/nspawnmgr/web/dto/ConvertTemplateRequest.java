package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

/** Reused for both conversion directions (nspawn->podman, podman->nspawn) - see
 *  AdminTemplateApiController.convertToPodman/convertToNspawn. {@code sudoPassword} is only
 *  required in admin-approval mode (no stored sudo secret configured). */
public record ConvertTemplateRequest(@NotBlank String newName, String sudoPassword) {
}
