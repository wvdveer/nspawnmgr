package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.HostService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class AdminHostPageController {

    private final HostService hostService;
    private final CurrentUserProvider currentUserProvider;

    public AdminHostPageController(HostService hostService, CurrentUserProvider currentUserProvider) {
        this.hostService = hostService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/admin/hosts")
    public String list(Model model) {
        model.addAttribute("hosts", hostService.listAll());
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/hosts";
    }

    @GetMapping("/admin/hosts/new")
    public String newForm(Model model) {
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/host-form";
    }

    @GetMapping("/admin/hosts/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("host", hostService.getById(id));
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/host-form";
    }
}
