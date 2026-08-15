package com.nspawnmgr.web;

import com.nspawnmgr.service.HostService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class AdminHostPageController {

    private final HostService hostService;

    public AdminHostPageController(HostService hostService) {
        this.hostService = hostService;
    }

    @GetMapping("/admin/hosts")
    public String list(Model model) {
        model.addAttribute("hosts", hostService.listAll());
        return "admin/hosts";
    }

    @GetMapping("/admin/hosts/new")
    public String newForm() {
        return "admin/host-form";
    }

    @GetMapping("/admin/hosts/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("host", hostService.getById(id));
        return "admin/host-form";
    }
}
