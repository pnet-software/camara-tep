package gr.pnet.camara.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.common.KeyValue;

class CamaraCodeObservationConventionTest {

    private final CamaraCodeObservationConvention convention = new CamaraCodeObservationConvention();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/things");

    @Test
    void errorIsTaggedWithItsCamaraCode() {
        RequestOutcome.error(request, "INVALID_ARGUMENT", null);

        assertThat(convention.getLowCardinalityKeyValues(context()))
                .contains(KeyValue.of("code", "INVALID_ARGUMENT"))
                .anySatisfy(keyValue -> assertThat(keyValue.getKey()).isEqualTo("status"));
    }

    @Test
    void successIsTaggedNone() {
        assertThat(convention.getLowCardinalityKeyValues(context())).contains(KeyValue.of("code", "none"));
    }

    private ServerRequestObservationContext context() {
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }
}
