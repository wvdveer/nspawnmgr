package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AccountPageController {

    private final CurrentUserProvider currentUserProvider;

    public AccountPageController(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/account")
    public String account(Model model) {
        model.addAttribute("currentUser", currentUserProvider.get());
        return "account";
    }
}
