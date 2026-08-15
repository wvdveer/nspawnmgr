package com.nspawnmgr.web;

import com.nspawnmgr.service.ContainerUserService;
import com.nspawnmgr.service.SettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminContainerUserRequestPageController {

    private final ContainerUserService containerUserService;
    private final SettingsService settingsService;

    public AdminContainerUserRequestPageController(ContainerUserService containerUserService, SettingsService settingsService) {
        this.containerUserService = containerUserService;
        this.settingsService = settingsService;
    }

    @GetMapping("/admin/container-user-requests/pending")
    public String pending(Model model) {
        model.addAttribute("pending", containerUserService.listPendingRequests());
        model.addAttribute("sshApprovalRequired", settingsService.sshApprovalRequired());
        return "admin/pending-user-requests";
    }
}
