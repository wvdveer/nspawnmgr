package com.nspawnmgr.fakeguac;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Spring's static-resource welcome-page resolution only covers the context root, not arbitrary
 * subdirectories — but GuacamoleSessionService builds URLs like ".../guacamole/#/client/..." whose
 * HTTP path (before the # fragment) is this webapp's own root ("" or "/"), since Tomcat's context
 * path ("/guacamole", from being deployed as guacamole.war) already supplies that segment
 * externally. Forward both explicitly to the static stand-in page.
 */
@Controller
public class GuacamoleStandInPageController {

    @GetMapping({"", "/"})
    public String guacamoleStandIn() {
        return "forward:/index.html";
    }
}
