package gr.pnet.camara.reachability.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import gr.pnet.camara.common.security.CamaraAuthErrorHandler;
import gr.pnet.camara.reachability.generated.status.DeviceReachabilityStatusApiController;
import gr.pnet.camara.reachability.generated.status.DeviceReachabilityStatusApiDelegate;
import gr.pnet.camara.reachability.security.SecurityConfig;

/**
 * Swagger UI's Authorize must get tokens from the issuer the service checks them against, not the dev realm the
 * spec ships with.
 */
@WebMvcTest(DeviceReachabilityStatusApiController.class)
@Import({ SecurityConfig.class, CamaraAuthErrorHandler.class })
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri="
        + "http://192.0.2.10:30667/auth/realms/camara")
class ReachabilitySpecWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DeviceReachabilityStatusApiDelegate delegate;

    // Stands in for the decoder Boot would build from the issuer
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void specPointsAuthorizeAtTheIssuer() throws Exception {
        mvc.perform(get("/device-reachability-status.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "openIdConnectUrl: http://192.0.2.10:30667/auth/realms/camara/.well-known/openid-configuration")))
                .andExpect(content().string(not(containsString("localhost:8180"))))
                // The rest of the spec is served as written
                .andExpect(content().string(containsString("title: Device Reachability Status")));
    }
}
