package com.nspawnmgr.osauth;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Machine-to-machine counterpart to {@link LoginServlet}: checks a username/password against
 * whatever {@link OsAuthenticator} backend is configured and returns a bare {@code text/plain}
 * result — {@code SUCCESS}, {@code INVALID_CREDENTIALS}, or {@code NOT_AUTHORIZED} — with no
 * session, no cookie, no HTML. Called by nspawnmgr.war's own {@code PamAuthVerifyController} when
 * a container's {@code pam_nspawnmgr} check is configured to delegate to this backend (see that
 * class's own javadoc), always over loopback within the same Tomcat instance — restricted to
 * localhost callers since, unlike {@link LoginServlet}, there's no session cookie protecting
 * repeated guesses here.
 */
public class VerifyServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!isLoopback(req)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String username = req.getParameter("username");
        String password = req.getParameter("password");
        if (username == null || password == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "username and password are required");
            return;
        }

        AuthResult result = OsAuthenticator.configured(getServletContext()).authenticate(username, password.toCharArray());
        resp.setContentType("text/plain;charset=UTF-8");
        if (result instanceof AuthResult.Success) {
            resp.getWriter().print("SUCCESS");
        } else if (result instanceof AuthResult.NotAuthorized) {
            resp.getWriter().print("NOT_AUTHORIZED");
        } else {
            resp.getWriter().print("INVALID_CREDENTIALS");
        }
    }

    private boolean isLoopback(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        return "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
    }
}
