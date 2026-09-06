package com.nspawnmgr.web;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.HostService;
import com.nspawnmgr.service.ShareService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class AdminHostPageController {

    private final HostService hostService;
    private final ShareService shareService;
    private final CurrentUserProvider currentUserProvider;

    public AdminHostPageController(HostService hostService, ShareService shareService,
                                    CurrentUserProvider currentUserProvider) {
        this.hostService = hostService;
        this.shareService = shareService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/admin/hosts/new")
    public String newForm(Model model) {
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/host-form";
    }

    @GetMapping("/admin/hosts/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Container host = hostService.getById(id);
        model.addAttribute("host", host);
        model.addAttribute("shares", shareService.listShares(host));
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/host-form";
    }
}
