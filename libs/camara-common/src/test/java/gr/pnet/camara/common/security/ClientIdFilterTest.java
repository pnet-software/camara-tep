package gr.pnet.camara.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import gr.pnet.camara.common.web.RequestOutcome;

class ClientIdFilterTest {

    private final ClientIdFilter filter = new ClientIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final AtomicReference<String> mdcDuringChain = new AtomicReference<>();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void clientOfTheTokenIsInTheMdcDuringTheRequestAndRecorded() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("azp", "acme-backend")
                .build()));

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> mdcDuringChain.set(MDC.get(ClientIdFilter.MDC_KEY)));

        assertThat(mdcDuringChain.get()).isEqualTo("acme-backend");
        assertThat(MDC.get(ClientIdFilter.MDC_KEY)).isNull();
        assertThat(RequestOutcome.clientId(request)).isEqualTo("acme-backend");
    }

    @Test
    void noTokenNoClient() throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> mdcDuringChain.set(MDC.get(ClientIdFilter.MDC_KEY)));

        assertThat(mdcDuringChain.get()).isNull();
        assertThat(RequestOutcome.clientId(request)).isNull();
    }
}
