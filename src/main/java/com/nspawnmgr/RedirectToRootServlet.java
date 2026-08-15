package com.nspawnmgr;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Registered instead of the real Spring application by {@link NspawnmgrApplication#onStartup} when
 * the database isn't configured/reachable yet. Redirects every request back to "/" — ROOT.war
 * itself decides whether to show the setup wizard or, once its "Finish setup" button has touched
 * this context's own XML (triggering a Tomcat redeploy), let {@code onStartup()} run again and take
 * the normal path instead.
 */
final class RedirectToRootServlet extends HttpServlet {

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.sendRedirect("/");
    }
}
