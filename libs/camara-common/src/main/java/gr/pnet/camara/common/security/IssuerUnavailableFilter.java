package gr.pnet.camara.common.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.support.Masking;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers 503 {@code UNAVAILABLE} when the token issuer can't be used: its configuration can't be loaded
 * (wrong {@code AUTH_ISSUER_URI}, Keycloak down) or its signing keys can't be fetched. Spring Security
 * rethrows both out of its filter chain, so without this the client gets a bare 500 that isn't an ErrorInfo.
 */
@Component
// After CorrelatorFilter, so the correlator is echoed and logged, and inside AccessLogFilter, so its 503 is in the
// access log; before Spring Security's chain
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class IssuerUnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IssuerUnavailableFilter.class);

    private final JsonMapper jsonMapper;

    public IssuerUnavailableFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException e) {
            Throwable failure = issuerFailure(e);
            if (failure == null || response.isCommitted()) {
                throw e;
            }
            // The decoder's own message is generic; its cause names the issuer and what went wrong
            String detail = failure instanceof JwtDecoderInitializationException && failure.getCause() != null
                    ? failure.getCause().getMessage()
                    : failure.getMessage();
            log.warn("UNAVAILABLE: token issuer unusable: {}", Masking.maskAll(detail));
            CamaraAuthErrorHandler.write(jsonMapper, request, response, CamaraApiException.unavailable(failure),
                    failure);
        }
    }

    private static Throwable issuerFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof JwtDecoderInitializationException || t instanceof AuthenticationServiceException) {
                return t;
            }
        }
        return null;
    }
}
