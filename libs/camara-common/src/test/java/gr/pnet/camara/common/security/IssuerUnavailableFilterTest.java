package gr.pnet.camara.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import gr.pnet.camara.common.web.RequestOutcome;
import tools.jackson.databind.json.JsonMapper;

class IssuerUnavailableFilterTest {

    private final IssuerUnavailableFilter filter = new IssuerUnavailableFilter(JsonMapper.builder().build());
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void issuerThatCantBeLoadedIsUnavailable() throws Exception {
        filter.doFilter(request, response, failingWith(new JwtDecoderInitializationException(
                "Failed to lazily resolve the supplied JwtDecoder instance",
                new IllegalArgumentException("Unable to resolve the Configuration with the provided Issuer"))));

        assertUnavailable();
    }

    @Test
    void keysThatCantBeFetchedAreUnavailable() throws Exception {
        filter.doFilter(request, response, failingWith(new AuthenticationServiceException(
                "An error occurred while attempting to decode the Jwt: Couldn't retrieve remote JWK set")));

        assertUnavailable();
    }

    @Test
    void failureWrappedInServletExceptionIsUnavailable() throws Exception {
        FilterChain chain = (req, res) -> {
            throw new ServletException(new JwtDecoderInitializationException("Failed", null));
        };

        filter.doFilter(request, response, chain);

        assertUnavailable();
    }

    @Test
    void otherFailuresPassThrough() {
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> filter.doFilter(request, response, failingWith(boom))).isSameAs(boom);
    }

    @Test
    void committedResponseIsLeftAlone() {
        response.setCommitted(true);
        AuthenticationServiceException failure = new AuthenticationServiceException("keys");

        assertThatThrownBy(() -> filter.doFilter(request, response, failingWith(failure))).isSameAs(failure);
    }

    @Test
    void requestsThatSucceedAreUntouched() throws Exception {
        filter.doFilter(request, response, (req, res) -> response.setStatus(200));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    private static FilterChain failingWith(RuntimeException e) {
        return (req, res) -> {
            throw e;
        };
    }

    private void assertUnavailable() throws Exception {
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .contains("\"status\":503")
                .contains("\"code\":\"UNAVAILABLE\"");
        assertThat(RequestOutcome.code(request)).isEqualTo("UNAVAILABLE");
    }
}
