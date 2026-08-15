package com.nspawnmgr.security;

import com.nspawnmgr.domain.User;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public User get() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof NspawnmgrUserPrincipal principal)) {
            throw new IllegalStateException("No authenticated nspawnmgr user in security context");
        }
        return principal.user();
    }
}
