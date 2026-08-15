package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotNull;

import com.nspawnmgr.domain.RdpSecurityMode;

public record UpdateRdpSecurityRequest(@NotNull RdpSecurityMode security) {
}
