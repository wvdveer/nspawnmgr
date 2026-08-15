package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.SettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminNetworkDiagnosticsPageController {

    private final CurrentUserProvider currentUserProvider;
    private final SettingsService settingsService;

    public AdminNetworkDiagnosticsPageController(CurrentUserProvider currentUserProvider, SettingsService settingsService) {
        this.currentUserProvider = currentUserProvider;
        this.settingsService = settingsService;
    }

    @GetMapping("/admin/network-diagnostics")
    public String networkDiagnostics(Model model) {
        model.addAttribute("currentUser", currentUserProvider.get());
        model.addAttribute("sshApprovalRequired", settingsService.sshApprovalRequired());
        return "admin/network-diagnostics";
    }
}
