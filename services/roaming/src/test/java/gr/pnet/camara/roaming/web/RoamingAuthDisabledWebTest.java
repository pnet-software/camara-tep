package gr.pnet.camara.roaming.web;

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

import gr.pnet.camara.roaming.generated.status.RoamingStatusRetrievalApiController;
import gr.pnet.camara.roaming.generated.status.RoamingStatusRetrievalApiDelegate;
import gr.pnet.camara.roaming.generated.status.model.RoamingStatusResponse;
import gr.pnet.camara.roaming.security.SecurityConfig;

@WebMvcTest(controllers = RoamingStatusRetrievalApiController.class, properties = "camara.auth.enabled=false")
@Import(SecurityConfig.class)
class RoamingAuthDisabledWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RoamingStatusRetrievalApiDelegate delegate;

    @Test
    void apiNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(delegate.getRoamingStatus(any(), any())).thenReturn(ResponseEntity.ok(
                new RoamingStatusResponse(OffsetDateTime.parse("2026-10-20T10:00:00Z"), false)));

        mvc.perform(post("/device-roaming-status/v1/retrieve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device\":{\"phoneNumber\":\"+306912345678\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roaming").value(false));
    }
}
