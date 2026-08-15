package com.nspawnmgr.web;

import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.MinimalTemplateFlavor;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.PrivateUsersMode;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.TemplateService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class AdminTemplatePageController {

    private final TemplateService templateService;
    private final SettingsService settingsService;

    public AdminTemplatePageController(TemplateService templateService, SettingsService settingsService) {
        this.templateService = templateService;
        this.settingsService = settingsService;
    }

    @GetMapping("/admin/templates")
    public String list(Model model) {
        List<Template> templates = templateService.listAll();
        model.addAttribute("templates", templates);
        model.addAttribute("sudoApprovalRequired", settingsService.sshApprovalRequired());
        model.addAttribute("minimalFlavors", MinimalTemplateFlavor.values());
        Set<String> existingNames = templates.stream().map(Template::getName).collect(Collectors.toSet());
        model.addAttribute("existingTemplateNames", existingNames);
        return "admin/templates";
    }

    @GetMapping("/admin/templates/new")
    public String newForm(Model model) {
        model.addAttribute("packageManagers", PackageManager.forTemplates());
        model.addAttribute("backends", ContainerBackend.values());
        model.addAttribute("privateUsersModes", PrivateUsersMode.values());
        return "admin/template-form";
    }

    @GetMapping("/admin/templates/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        model.addAttribute("template", templateService.getById(id));
        model.addAttribute("packageManagers", PackageManager.forTemplates());
        model.addAttribute("backends", ContainerBackend.values());
        model.addAttribute("privateUsersModes", PrivateUsersMode.values());
        return "admin/template-form";
    }
}
