package gr.pnet.camara.common.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.error.ErrorInfo;
import gr.pnet.camara.common.web.RequestOutcome;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders the 401 and 403 that Spring Security raises as CAMARA {@code ErrorInfo} bodies. These happen in
 * the filter chain, before any controller, so {@code CamaraExceptionHandler} never sees them.
 */
@Component
public class CamaraAuthErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    // Set the RFC 6750 WWW-Authenticate header and status; the body is ours
    private final AuthenticationEntryPoint bearerEntryPoint = bearerEntryPoint();
    private final AccessDeniedHandler bearerAccessDenied = new BearerTokenAccessDeniedHandler();

    private final JsonMapper jsonMapper;

    public CamaraAuthErrorHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    // The 401 links to the metadata of the API that was called, with its scopes: on a host serving several APIs,
    // Spring Security's default link, the same for all of them, could only reach one
    private static AuthenticationEntryPoint bearerEntryPoint() {
        BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
        entryPoint.setResourceMetadataParameterResolver(ResourceMetadata::url);
        return entryPoint;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException, ServletException {
        bearerEntryPoint.commence(request, response, authException);
        write(jsonMapper, request, response, CamaraApiException.unauthenticated(), authException);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException, ServletException {
        // The token was valid, it just lacks the scope: say whose it was
        RequestOutcome.clientId(request, AccessTokenSubject.clientId(request.getUserPrincipal()).orElse(null));
        bearerAccessDenied.handle(request, response, accessDeniedException);
        write(jsonMapper, request, response, CamaraApiException.permissionDenied(), accessDeniedException);
    }

    // Shared with IssuerUnavailableFilter, the other place that answers from inside the filter chain
    static void write(JsonMapper jsonMapper, HttpServletRequest request, HttpServletResponse response,
            CamaraApiException error, Throwable cause) throws IOException {
        RequestOutcome.error(request, error.getCode(), cause);
        response.setStatus(error.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(),
                new ErrorInfo(error.getStatus().value(), error.getCode(), error.getMessage()));
    }
}
