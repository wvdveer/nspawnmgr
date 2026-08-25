package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

/** {@code sudoPassword} is only required in admin-approval mode (no stored sudo secret configured). */
public record CreatePodmanPullRequest(@NotBlank String pullReference, String sudoPassword) {
}
