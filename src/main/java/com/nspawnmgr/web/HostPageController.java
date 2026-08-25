package com.nspawnmgr.web;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.service.HostService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Connect-session route for hosts - the browse/list page this used to also serve was superseded
 *  by the Machines page (UI redesign Phase 2 merged hosts into that same card grid) and removed;
 *  this class now exists only for the session URL below. */
@Controller
public class HostPageController {

    private final HostService hostService;

    public HostPageController(HostService hostService) {
        this.hostService = hostService;
    }

    /**
     * Own URL space (/hosts/{name}/session/...) rather than reusing
     * ContainerPageController's /containers/{name}/session/{protocol} — confirmed live, a host is
     * internally just a Container row (kind=EXTERNAL), which meant its session URL read
     * ".../containers/yoga/session/ssh" even though a host isn't a container from the admin's point
     * of view. Renders the exact same template as the container version (the page itself — an
     * iframe plus session.js — has nothing container-specific about it); only the URL the browser
     * shows differs.
     *
     * <p>Addressed by name, not numeric ID - this URL gets shared/pasted around directly (see
     * ContainerPageController's own twin route for the same reasoning).
     */
    @GetMapping("/hosts/{name}/session/{protocol}")
    public String session(@PathVariable String name, @PathVariable String protocol, Model model) {
        Container container = hostService.getByName(name);
        model.addAttribute("container", container);
        model.addAttribute("protocol", protocol);
        return "containers/session";
    }
}
