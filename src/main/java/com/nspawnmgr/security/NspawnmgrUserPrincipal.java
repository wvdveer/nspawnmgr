package com.nspawnmgr.security;

import com.nspawnmgr.domain.User;

public record NspawnmgrUserPrincipal(User user) {

    public Long userId() {
        return user.getId();
    }
}
