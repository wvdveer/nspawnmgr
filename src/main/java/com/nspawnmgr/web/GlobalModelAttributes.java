package com.nspawnmgr.web;

import com.nspawnmgr.service.SettingsService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Attributes every page-rendering controller's model needs, without threading them through each
 * controller's own method individually. First use: {@code sshApprovalRequired}, needed by
 * fragments/app-shell.html's Requests nav item (see RequestsPageController) on every single page,
 * not just the Requests page itself.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class GlobalModelAttributes {

    private final SettingsService settingsService;

    public GlobalModelAttributes(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @ModelAttribute("sshApprovalRequired")
    public boolean sshApprovalRequired() {
        return settingsService.sshApprovalRequired();
    }
}
