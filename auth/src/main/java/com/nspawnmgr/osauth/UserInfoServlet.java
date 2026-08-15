package com.nspawnmgr.osauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/** The actual USER_ID_URL target: returns identity JSON for the caller's session cookie, or {} if not logged in. */
public class UserInfoServlet extends HttpServlet {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String cookieName = AuthConfig.cookieName(getServletContext());
        String token = readCookie(req, cookieName);
        Identity identity = SessionStore.instance().lookup(token);

        resp.setContentType("application/json;charset=UTF-8");
        if (identity == null) {
            resp.getWriter().write("{}");
            return;
        }
        resp.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "id", identity.id(),
                "username", identity.username(),
                "email", identity.email(),
                "fullName", identity.fullName())));
    }

    private String readCookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : req.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
