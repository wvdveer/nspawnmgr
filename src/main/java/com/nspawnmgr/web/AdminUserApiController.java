package com.nspawnmgr.web;

import com.nspawnmgr.domain.Role;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.UserMessages;
import com.nspawnmgr.service.UserService;
import com.nspawnmgr.web.dto.UpdateUserRoleRequest;
import com.nspawnmgr.web.dto.UserSummaryResponse;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AdminUserApiController {

    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;
    private final UserMessages messages;

    public AdminUserApiController(UserService userService, CurrentUserProvider currentUserProvider, UserMessages messages) {
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
        this.messages = messages;
    }

    @GetMapping("/api/admin/users")
    public List<UserSummaryResponse> list() {
        return userService.listAll().stream()
                .map(u -> new UserSummaryResponse(u.getId(), u.getUsername(), u.getEmail(), u.getRole()))
                .toList();
    }

    @PutMapping("/api/admin/users/{id}/role")
    public UserSummaryResponse setRole(@PathVariable Long id, @Valid @RequestBody UpdateUserRoleRequest request) {
        Role newRole = parseRole(request.role());
        User actingAdmin = currentUserProvider.get();
        User updated = userService.setRole(id, newRole, actingAdmin);
        return new UserSummaryResponse(updated.getId(), updated.getUsername(), updated.getEmail(), updated.getRole());
    }

    private Role parseRole(String value) {
        try {
            return Role.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(messages.get("error.web.invalidRole", value));
        }
    }
}
