package com.nspawnmgr.web;

import com.nspawnmgr.domain.Role;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Arrays;

@Controller
public class AdminUserPageController {

    /** The self-hosted app machine's own opinionated, fixed name - see
     *  nspawnmgr-bootstrap-app-machine.sh and ContainerDiscoveryService's identical constant. */
    private static final String APP_MACHINE_NAME = "nspawnmgr";

    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;
    private final SettingsService settingsService;
    private final ContainerRepository containerRepository;

    public AdminUserPageController(UserService userService, CurrentUserProvider currentUserProvider,
                                    SettingsService settingsService, ContainerRepository containerRepository) {
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
        this.settingsService = settingsService;
        this.containerRepository = containerRepository;
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
        boolean smbAuth = "smb".equals(settingsService.authBackend());
        model.addAttribute("smbAuth", smbAuth);
        model.addAttribute("authSmbServer", settingsService.authSmbServer());
        model.addAttribute("authSmbRequiredShare", settingsService.authSmbRequiredShare());
        // PAM-backend deployments create new users by adding a Linux account to the self-hosted
        // "nspawnmgr" machine itself - the "+" button sends the admin straight to that machine's
        // own Container users section (see containers/detail.html's #container-users-section)
        // rather than leaving them to find it themselves. Only meaningful for PAM (SMB users are
        // managed on a Windows machine nspawnmgr has no page for) and only once that machine has
        // actually been discovered as a Container row (always true post-first-boot, but a
        // just-migrated/pre-discovery install could still be mid-bootstrap).
        if (!smbAuth) {
            containerRepository.findByName(APP_MACHINE_NAME)
                    .ifPresent(c -> model.addAttribute("nspawnmgrContainerId", c.getId()));
        }
        // Drives the role-change context menu (admin-users.js) - every role a user COULD have,
        // so it can list "Change to X" for whichever ones aren't the user's current role. A plain
        // comma list rather than the enum array itself - simplest thing a data-* attribute /
        // th:each in the template can consume without needing Role visible to Thymeleaf's own
        // expression context.
        model.addAttribute("allRoleNames", String.join(",", Arrays.stream(Role.values()).map(Enum::name).toList()));
        return "admin/users";
    }
}
