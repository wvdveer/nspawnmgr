package com.nspawnmgr.osauth;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** GET renders a plain login form; POST authenticates (PAM or SMB, per AuthConfig) and sets the session cookie. */
public class LoginServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String returnTo = req.getParameter("returnTo");
        resp.setContentType("text/html;charset=UTF-8");
        resp.getWriter().print(HtmlPage.render("nspawnmgr auth", HtmlPage.TONE_NEUTRAL, """
                <h1>Log in</h1>
                <p>Use your operating system account.</p>
                <form method="post">
                    <input type="hidden" name="returnTo" value="%s"/>
                    <label>Username <input type="text" name="username" autofocus/></label>
                    <label>Password <input type="password" name="password"/></label>
                    <button type="submit">Log in</button>
                </form>
                """.formatted(returnTo == null ? "" : escape(returnTo))));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        String username = req.getParameter("username");
        String password = req.getParameter("password");
        if (username == null || password == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "username and password are required");
            return;
        }

        String returnTo = req.getParameter("returnTo");
        // Context-path-relative, not a hardcoded "/login": this webapp may be deployed at the
        // server root (e.g. mvn jetty:run for local iteration) or under a path like /auth
        // (co-located in the same Tomcat as nspawnmgr/Guacamole) - req.getContextPath() is "" in
        // the former case and "/auth" in the latter, so this resolves correctly either way.
        String loginLink = req.getContextPath() + "/login" + (returnTo != null && !returnTo.isBlank()
                ? "?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8)
                : "");

        OsAuthenticator osAuthenticator = OsAuthenticator.configured(getServletContext());
        AuthResult result = osAuthenticator.authenticate(username, password.toCharArray());
        if (result instanceof AuthResult.InvalidCredentials) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().print(HtmlPage.render("Login failed", HtmlPage.TONE_ERROR, """
                    <h1>Login failed</h1>
                    <p>Incorrect username or password.</p>
                    <p><a href="%s">Try again</a></p>
                    """.formatted(escape(loginLink))));
            return;
        }
        if (result instanceof AuthResult.NotAuthorized notAuthorized) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().print(HtmlPage.render("Not authorized", HtmlPage.TONE_WARN, """
                    <h1>Not authorized</h1>
                    <p>Your account is valid, but you must have %s to use nspawnmgr.</p>
                    <p><a href="%s">Try again</a></p>
                    """.formatted(escape(notAuthorized.requirement()), escape(loginLink))));
            return;
        }
        String fullName = ((AuthResult.Success) result).fullName();

        Identity identity = new Identity(username, username, "", fullName);
        String token = SessionStore.instance().createSession(identity);

        Cookie cookie = new Cookie(AuthConfig.cookieName(getServletContext()), token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        resp.addCookie(cookie);

        if (returnTo != null && !returnTo.isBlank()) {
            resp.sendRedirect(returnTo);
            return;
        }
        resp.setContentType("text/html;charset=UTF-8");
        resp.getWriter().print(HtmlPage.render("Logged in", HtmlPage.TONE_SUCCESS, """
                <h1>Logged in as %s</h1>
                <p>You can close this page and return to nspawnmgr.</p>
                """.formatted(escape(username))));
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
