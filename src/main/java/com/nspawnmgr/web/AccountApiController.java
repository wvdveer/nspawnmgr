package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.ShareService;
import com.nspawnmgr.service.UserService;
import com.nspawnmgr.web.dto.UpdateGuacamolePasswordRequest;
import com.nspawnmgr.web.dto.UpdateLanguageRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/** Self-service account settings — always acts on the current authenticated user, never a target id. */
@RestController
public class AccountApiController {

    private final ShareService shareService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;

    public AccountApiController(ShareService shareService, UserService userService, CurrentUserProvider currentUserProvider) {
        this.shareService = shareService;
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/api/account/guacamole-password")
    public void updateGuacamolePassword(@Valid @RequestBody UpdateGuacamolePasswordRequest request) {
        shareService.setGuacamolePassword(currentUserProvider.get(), request.password());
    }

    @PostMapping("/api/account/language")
    public void updateLanguage(@RequestBody UpdateLanguageRequest request) {
        userService.updatePreferredLanguage(currentUserProvider.get(), request.language());
    }
}
