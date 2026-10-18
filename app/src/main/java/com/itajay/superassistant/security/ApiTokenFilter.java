package com.itajay.superassistant.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Optional shared-secret API protection.
 *
 * <p>Local single-user usage works with an empty token. When {@code APP_API_TOKEN}
 * is set, every API endpoint except the liveness/health probes requires
 * {@code Authorization: Bearer <token>} (or {@code X-API-Token}). This closes the
 * most obvious deployment hole: without it any caller on the network can start
 * runs, read traces, or approve the HITL prompts the service itself raises.</p>
 *
 * <p>It is a shared secret, not user authentication or per-tenant isolation.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    @Value("${app.api-token:}")
    private String apiToken = "";

    public ApiTokenFilter() {
    }

    ApiTokenFilter(String apiToken) {
        this.apiToken = apiToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (apiToken == null || apiToken.isBlank() || isPublic(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        String headerToken = request.getHeader("X-API-Token");
        boolean authorized = (authorization != null && authorization.equals(BEARER + apiToken))
                || apiToken.equals(headerToken);
        if (!authorized) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isPublic(String path) {
        return "/api/health".equals(path)
                || path.startsWith("/actuator/health");
    }
}
