package com.nspawnmgr.web;

import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.PackageCacheService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminPackagePageController {

    private final PackageCacheService packageCacheService;
    private final CurrentUserProvider currentUserProvider;

    public AdminPackagePageController(PackageCacheService packageCacheService, CurrentUserProvider currentUserProvider) {
        this.packageCacheService = packageCacheService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/admin/packages")
    public String list(Model model) {
        model.addAttribute("packages", packageCacheService.listAll());
        model.addAttribute("dependencyPreFetchManagers", PackageManager.forDependencyPreFetch());
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/packages";
    }

    @GetMapping("/admin/packages/new")
    public String newForm(Model model) {
        model.addAttribute("packageManagers", PackageManager.values());
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/package-form";
    }
}
