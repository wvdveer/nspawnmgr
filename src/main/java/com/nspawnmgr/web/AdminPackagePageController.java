package com.nspawnmgr.web;

import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.service.PackageCacheService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminPackagePageController {

    private final PackageCacheService packageCacheService;

    public AdminPackagePageController(PackageCacheService packageCacheService) {
        this.packageCacheService = packageCacheService;
    }

    @GetMapping("/admin/packages")
    public String list(Model model) {
        model.addAttribute("packages", packageCacheService.listAll());
        model.addAttribute("packageManagers", PackageManager.values());
        model.addAttribute("dependencyPreFetchManagers", PackageManager.forDependencyPreFetch());
        return "admin/packages";
    }
}
