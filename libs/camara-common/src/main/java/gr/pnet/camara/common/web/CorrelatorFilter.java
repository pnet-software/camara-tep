package gr.pnet.camara.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Echoes the CAMARA {@code x-correlator} header on every response, error responses included,
 * and exposes it to logging and to outbound TEP calls through the MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelatorFilter extends OncePerRequestFilter {

    public static final String HEADER = "x-correlator";
    public static final String MDC_KEY = "correlator";

    // Same pattern as the XCorrelator schema in the CAMARA spec
    private static final Pattern VALID = Pattern.compile("^[a-zA-Z0-9-_:;./<>{}]{0,256}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlator = request.getHeader(HEADER);
        boolean valid = correlator != null && VALID.matcher(correlator).matches();
        if (valid) {
            // Set before the chain runs so it survives a committed error response;
            // invalid values are rejected with 400 by the controller's validation and not echoed
            response.setHeader(HEADER, correlator);
        }
        MDC.put(MDC_KEY, valid ? correlator : UUID.randomUUID().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
