package com.itajay.superassistant.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Establishes the trace id for every HTTP request.
 *
 * <p>A caller may supply {@code X-Trace-Id} (useful when the frontend correlates a
 * report with backend logs); otherwise one is generated. The id is echoed in the
 * response header and placed in the MDC, so every log line produced while handling
 * the request — including the async chat run, which opens its own scope with the
 * thread/run ids — carries it.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceContext.normalize(request.getHeader(HEADER));
        response.setHeader(HEADER, traceId);
        try (TraceContext.Scope ignored = TraceContext.open(traceId, null, null)) {
            filterChain.doFilter(request, response);
        }
    }
}
