package gr.pnet.camara.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ResourceMetadataTest {

    // The API is the first path segment, whatever follows it
    @ParameterizedTest
    @CsvSource({
            "/device-reachability-status/v1/retrieve, http://localhost/.well-known/oauth-protected-resource/device-reachability-status",
            "/quality-on-demand/v1/sessions/3fa85f64-5717-4562-b3fc-2c963f66afa6, http://localhost/.well-known/oauth-protected-resource/quality-on-demand",
            "/device-swap, http://localhost/.well-known/oauth-protected-resource/device-swap",
            "/, http://localhost/.well-known/oauth-protected-resource" })
    void urlIsTheEndpointWithTheApisPath(String path, String url) {
        assertThat(ResourceMetadata.url(new MockHttpServletRequest("POST", path))).isEqualTo(url);
    }

    // As the client addressed the service: scheme, host and port, never the query
    @Test
    void urlKeepsTheAddressTheClientUsed() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/sim-swap/v2/check");
        request.setScheme("https");
        request.setServerName("camara.example");
        request.setServerPort(8443);
        request.setQueryString("a=b");

        assertThat(ResourceMetadata.url(request))
                .isEqualTo("https://camara.example:8443/.well-known/oauth-protected-resource/sim-swap");
    }

    @Test
    void urlKeepsTheContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/camara/device-roaming-status/v1/retrieve");
        request.setContextPath("/camara");

        assertThat(ResourceMetadata.url(request))
                .isEqualTo("http://localhost/camara/.well-known/oauth-protected-resource/device-roaming-status");
    }
}
