package com.nspawnmgr.web;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.service.HostService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Browse/connect page for hosts - every host is visible to every user, same transparency model
 *  containers/list.html already uses for containers. */
@Controller
public class HostPageController {

    private final HostService hostService;

    public HostPageController(HostService hostService) {
        this.hostService = hostService;
    }

    @GetMapping("/hosts")
    public String list(Model model) {
        model.addAttribute("hosts", hostService.listAll());
        return "hosts/list";
    }

    /**
     * Own URL space (/hosts/{id}/session/...) rather than reusing
     * ContainerPageController's /containers/{id}/session/{protocol} — confirmed live, a host is
     * internally just a Container row (kind=EXTERNAL), which meant its session URL read
     * ".../containers/4/session/ssh" even though a host isn't a container from the admin's point of
     * view. Renders the exact same template as the container version (the page itself — an iframe
     * plus session.js — has nothing container-specific about it); only the URL the browser shows
     * differs.
     */
    @GetMapping("/hosts/{id}/session/{protocol}")
    public String session(@PathVariable Long id, @PathVariable String protocol, Model model) {
        Container container = hostService.getById(id);
        model.addAttribute("container", container);
        model.addAttribute("protocol", protocol);
        return "containers/session";
    }
}
