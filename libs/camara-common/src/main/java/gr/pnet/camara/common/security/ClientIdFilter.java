package gr.pnet.camara.common.security;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import gr.pnet.camara.common.web.RequestOutcome;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Puts the API client the access token was issued to in the MDC, so every log line of the request names it, and
 * records it for the access log. Runs once Spring Security has authenticated the request, while its context is still
 * set: the security chain clears it on the way out, before the access log is written.
 */
@Component
// Spring Security's chain is registered at -100 (spring.security.filter.order); this runs inside it
@Order(-99)
public class ClientIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "clientId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Static, not the AccessTokenSubject bean: filters are in MVC test slices, plain components aren't
        String clientId = AccessTokenSubject.clientId(SecurityContextHolder.getContext().getAuthentication())
                .orElse(null);
        if (clientId == null) {
            chain.doFilter(request, response);
            return;
        }
        RequestOutcome.clientId(request, clientId);
        MDC.put(MDC_KEY, clientId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
