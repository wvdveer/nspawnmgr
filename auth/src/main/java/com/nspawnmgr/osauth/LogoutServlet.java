package com.nspawnmgr.osauth;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class LogoutServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String cookieName = AuthConfig.cookieName(getServletContext());
        if (req.getCookies() != null) {
            for (Cookie cookie : req.getCookies()) {
                if (cookieName.equals(cookie.getName())) {
                    SessionStore.instance().invalidate(cookie.getValue());
                }
            }
        }
        Cookie expired = new Cookie(cookieName, "");
        expired.setPath("/");
        expired.setMaxAge(0);
        resp.addCookie(expired);

        // Echoes the same returnTo the caller (nspawnmgr's own AccountPageController) was given
        // into the "Log in again" link below, into LoginServlet's own returnTo handling - without
        // this, that link used to drop the user on this app's generic "logged in, close this
        // page" confirmation instead of ever sending them back to nspawnmgr.
        String returnTo = req.getParameter("returnTo");
        // req.getContextPath(), not a hardcoded "/login" - see LoginServlet's identical comment.
        String loginLink = req.getContextPath() + "/login" + (returnTo != null && !returnTo.isBlank()
                ? "?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8)
                : "");

        resp.setContentType("text/html;charset=UTF-8");
        resp.getWriter().print(HtmlPage.render("Logged out", HtmlPage.TONE_NEUTRAL, """
                <h1>Logged out</h1>
                <p><a href="%s">Log in again</a></p>
                """.formatted(escape(loginLink))));
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
