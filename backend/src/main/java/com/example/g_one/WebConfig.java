package com.example.g_one;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/** Security headers on every response. WebSocket endpoints are registered by the areas that own them. */
@Configuration
public class WebConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    OncePerRequestFilter securityHeaders() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                // frame-ancestors 'self': only this site may frame its own pages.
                res.setHeader("Content-Security-Policy",
                        "default-src 'self'; connect-src 'self' ws: wss:; media-src 'self' blob:; "
                                + "img-src 'self' data:; frame-ancestors 'self'");
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("Referrer-Policy", "no-referrer");
                res.setHeader("Permissions-Policy", "microphone=(self)");
                if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
                chain.doFilter(req, res);
            }
        };
    }
}
