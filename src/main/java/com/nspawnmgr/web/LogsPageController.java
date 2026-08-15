package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Viewing is for every logged-in user — see LogsApiController's own javadoc for why this isn't under /admin. */
@Controller
public class LogsPageController {

    private final CurrentUserProvider currentUserProvider;

    public LogsPageController(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/logs")
    public String tail(Model model) {
        model.addAttribute("currentUser", currentUserProvider.get());
        return "logs";
    }

    @GetMapping("/logs/full")
    public String full(Model model) {
        model.addAttribute("currentUser", currentUserProvider.get());
        return "logs-full";
    }
}
