package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotNull;

import com.nspawnmgr.domain.PamAuthSource;

import java.util.Set;

public record UpdatePamAuthRequest(@NotNull PamAuthSource source, @NotNull Set<String> services) {
}
