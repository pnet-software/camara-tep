package gr.pnet.camara.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import gr.pnet.camara.common.web.RequestOutcome;
import tools.jackson.databind.json.JsonMapper;

class CamaraAuthErrorHandlerTest {

    private final CamaraAuthErrorHandler handler = new CamaraAuthErrorHandler(JsonMapper.builder().build());
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void missingTokenIsUnauthenticated() throws Exception {
        handler.commence(request, response, new InsufficientAuthenticationException("no token"));

        assertErrorInfo(401, "UNAUTHENTICATED");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer")
                .doesNotContain("error=");
        assertThat(RequestOutcome.code(request)).isEqualTo("UNAUTHENTICATED");
        assertThat(RequestOutcome.type(request)).isEqualTo("InsufficientAuthenticationException");
        assertThat(RequestOutcome.clientId(request)).isNull();
    }

    // Its own metadata, not the one Spring Security links by default: on a shared host that is another API's
    @Test
    void unauthenticatedLinksTheMetadataOfTheApiCalled() throws Exception {
        request.setRequestURI("/quality-on-demand/v1/sessions");

        handler.commence(request, response, new InsufficientAuthenticationException("no token"));

        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains(
                "resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/quality-on-demand\"");
    }

    @Test
    void invalidTokenIsUnauthenticatedAndSaysWhy() throws Exception {
        handler.commence(request, response, new InvalidBearerTokenException("Jwt expired"));

        assertErrorInfo(401, "UNAUTHENTICATED");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"invalid_token\"");
    }

    @Test
    void missingScopeIsPermissionDenied() throws Exception {
        request.setUserPrincipal(new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("acme-backend")
                .claim("azp", "acme-backend")
                .build()));

        handler.handle(request, response, new AccessDeniedException("Access Denied"));

        assertErrorInfo(403, "PERMISSION_DENIED");
        assertThat(RequestOutcome.code(request)).isEqualTo("PERMISSION_DENIED");
        assertThat(RequestOutcome.clientId(request)).isEqualTo("acme-backend");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("error=\"insufficient_scope\"");
    }

    private void assertErrorInfo(int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(response.getContentType())).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getContentAsString())
                .contains("\"status\":" + status)
                .contains("\"code\":\"" + code + "\"")
                .contains("\"message\":\"");
    }
}
