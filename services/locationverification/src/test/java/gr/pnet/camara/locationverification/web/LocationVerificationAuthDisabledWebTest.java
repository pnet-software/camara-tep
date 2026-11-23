package gr.pnet.camara.locationverification.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import gr.pnet.camara.locationverification.generated.status.LocationVerificationApiController;
import gr.pnet.camara.locationverification.generated.status.LocationVerificationApiDelegate;
import gr.pnet.camara.locationverification.generated.status.model.VerificationResult;
import gr.pnet.camara.locationverification.generated.status.model.VerifyLocationResponse;
import gr.pnet.camara.locationverification.security.SecurityConfig;

@WebMvcTest(controllers = LocationVerificationApiController.class, properties = "camara.auth.enabled=false")
@Import(SecurityConfig.class)
class LocationVerificationAuthDisabledWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LocationVerificationApiDelegate delegate;

    @Test
    void apiNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(delegate.verifyLocation(any(), any())).thenReturn(ResponseEntity.ok(
                new VerifyLocationResponse(VerificationResult.TRUE, OffsetDateTime.parse("2026-10-20T10:00:00Z"))));

        mvc.perform(post("/location-verification/v3/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device\":{\"phoneNumber\":\"+306912345678\"},\"area\":{\"areaType\":\"CIRCLE\","
                                + "\"center\":{\"latitude\":37.9838,\"longitude\":23.7275},\"radius\":2000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationResult").value("TRUE"));
    }
}
