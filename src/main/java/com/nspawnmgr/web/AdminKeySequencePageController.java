package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.KeySequenceService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminKeySequencePageController {

    private final KeySequenceService keySequenceService;
    private final CurrentUserProvider currentUserProvider;

    public AdminKeySequencePageController(KeySequenceService keySequenceService, CurrentUserProvider currentUserProvider) {
        this.keySequenceService = keySequenceService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/admin/key-sequences")
    public String list(Model model) {
        model.addAttribute("sequences", keySequenceService.list());
        model.addAttribute("mainKeyOptions", KeySequenceService.MAIN_KEY_OPTIONS);
        model.addAttribute("currentUser", currentUserProvider.get());
        return "admin/key-sequences";
    }
}
