package gr.pnet.camara.reachability.web;

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

import gr.pnet.camara.reachability.generated.status.DeviceReachabilityStatusApiController;
import gr.pnet.camara.reachability.generated.status.DeviceReachabilityStatusApiDelegate;
import gr.pnet.camara.reachability.generated.status.model.ReachabilityStatusResponse;
import gr.pnet.camara.reachability.security.SecurityConfig;

@WebMvcTest(controllers = DeviceReachabilityStatusApiController.class, properties = "camara.auth.enabled=false")
@Import(SecurityConfig.class)
class ReachabilityAuthDisabledWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DeviceReachabilityStatusApiDelegate delegate;

    @Test
    void apiNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(delegate.getReachabilityStatus(any(), any())).thenReturn(ResponseEntity.ok(
                new ReachabilityStatusResponse(OffsetDateTime.parse("2026-10-20T10:00:00Z"), false)));

        mvc.perform(post("/device-reachability-status/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device\":{\"phoneNumber\":\"+306912345678\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(false));
    }
}
