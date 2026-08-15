package com.nspawnmgr.web.dto;

import com.nspawnmgr.domain.Role;

public record UserSummaryResponse(Long id, String username, String email, Role role) {
}
