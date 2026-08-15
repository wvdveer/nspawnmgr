package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.SettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminSettingsPageController {

    private final SettingsService settingsService;
    private final CurrentUserProvider currentUserProvider;

    public AdminSettingsPageController(SettingsService settingsService, CurrentUserProvider currentUserProvider) {
        this.settingsService = settingsService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/admin/settings")
    public String settings(Model model) {
        model.addAttribute("settings", settingsService);
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/settings";
    }
}
