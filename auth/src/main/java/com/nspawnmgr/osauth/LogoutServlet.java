package com.nspawnmgr.osauth;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

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

        resp.setContentType("text/html;charset=UTF-8");
        // req.getContextPath(), not a hardcoded "/login" - see LoginServlet's identical comment.
        resp.getWriter().print(HtmlPage.render("Logged out", HtmlPage.TONE_NEUTRAL, """
                <h1>Logged out</h1>
                <p><a href="%s/login">Log in again</a></p>
                """.formatted(req.getContextPath())));
    }
}
