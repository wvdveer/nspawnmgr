package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminUserPageController {

    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;
    private final SettingsService settingsService;

    public AdminUserPageController(UserService userService, CurrentUserProvider currentUserProvider,
                                    SettingsService settingsService) {
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
        this.settingsService = settingsService;
    }

    @GetMapping("/admin/users")
    public String list(Model model) {
        model.addAttribute("users", userService.listAll());
        model.addAttribute("currentUser", currentUserProvider.get());
        model.addAttribute("externalManaged", userService.isExternalRoleManaged());
        // Which "add a new user" instructions apply depends on how this deployment's auth is
        // wired: PAM (a Linux account inside the self-hosted nspawnmgr container itself) or SMB (a
        // Windows account granted access to the configured share/server) - see the SMB backend's
        // own live share-access gate (AuthConfig), not group membership.
        model.addAttribute("smbAuth", "smb".equals(settingsService.authBackend()));
        model.addAttribute("authSmbServer", settingsService.authSmbServer());
        model.addAttribute("authSmbRequiredShare", settingsService.authSmbRequiredShare());
        return "admin/users";
    }
}
