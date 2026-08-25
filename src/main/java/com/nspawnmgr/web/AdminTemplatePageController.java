package com.nspawnmgr.web;

import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.MinimalTemplateFlavor;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.PrivateUsersMode;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.domain.TemplateFeatureState;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.NetworkDiagnosticsService;
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
    private final NetworkDiagnosticsService networkDiagnosticsService;
    private final CurrentUserProvider currentUserProvider;

    public AdminTemplatePageController(TemplateService templateService, SettingsService settingsService,
                                        NetworkDiagnosticsService networkDiagnosticsService,
                                        CurrentUserProvider currentUserProvider) {
        this.templateService = templateService;
        this.settingsService = settingsService;
        this.networkDiagnosticsService = networkDiagnosticsService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/admin/templates")
    public String list(Model model) {
        List<Template> templates = templateService.listAll();
        model.addAttribute("templates", templates);
        model.addAttribute("sudoApprovalRequired", settingsService.sshApprovalRequired());
        model.addAttribute("minimalFlavors", MinimalTemplateFlavor.values());
        Set<String> existingNames = templates.stream().map(Template::getName).collect(Collectors.toSet());
        model.addAttribute("existingTemplateNames", existingNames);
        model.addAttribute("podmanInstalled", networkDiagnosticsService.isPodmanInstalled());
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/templates";
    }

    // Also supplies every field the form fragment in admin/template-form.html needs
    // (~{admin/template-form :: formCard}) - the Edit page used to be a separate route/view, now
    // merged directly onto this one under its own "Manage" panel.
    @GetMapping("/admin/templates/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("template", templateService.getById(id));
        model.addAttribute("sudoApprovalRequired", settingsService.sshApprovalRequired());
        model.addAttribute("packageManagers", PackageManager.forTemplates());
        model.addAttribute("backends", ContainerBackend.values());
        model.addAttribute("privateUsersModes", PrivateUsersMode.values());
        model.addAttribute("templateFeatureStates", TemplateFeatureState.values());
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/template-detail";
    }

    @GetMapping("/admin/templates/new")
    public String newForm(Model model) {
        model.addAttribute("packageManagers", PackageManager.forTemplates());
        model.addAttribute("backends", ContainerBackend.values());
        model.addAttribute("privateUsersModes", PrivateUsersMode.values());
        model.addAttribute("templateFeatureStates", TemplateFeatureState.values());
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/template-form";
    }
}
