package com.example.g_one;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The site's home page now sits at {@code /}, but every room link ever shared is {@code /?room=<id>}. Those links must
 * keep opening the app, so a GET carrying {@code room} is forwarded (not redirected) to the app page: the browser keeps
 * the same URL, the page script reads the same query string, and no saved room token is orphaned.
 *
 * <p>A filter rather than a controller mapping: a mapping with {@code params = "room"} makes a plain {@code /} answer
 * 400 instead of falling through to the home page. Runs after the security-headers filter so the forwarded page still
 * gets them.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class RoomLinkFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if ("GET".equals(req.getMethod()) && ("/".equals(path) || "/index.html".equals(path)) && req.getParameter("room") != null) {
            req.getRequestDispatcher("/app.html").forward(req, res);
            return;
        }
        chain.doFilter(req, res);
    }
}
