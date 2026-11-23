package gr.pnet.camara.qualityondemand.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import gr.pnet.camara.qualityondemand.generated.status.QoSSessionsApiController;
import gr.pnet.camara.qualityondemand.generated.status.QoSSessionsApiDelegate;
import gr.pnet.camara.qualityondemand.generated.status.model.ApplicationServer;
import gr.pnet.camara.qualityondemand.generated.status.model.QosStatus;
import gr.pnet.camara.qualityondemand.generated.status.model.SessionInfo;
import gr.pnet.camara.qualityondemand.security.SecurityConfig;

@WebMvcTest(controllers = QoSSessionsApiController.class, properties = "camara.auth.enabled=false")
@Import(SecurityConfig.class)
class QualityOnDemandAuthDisabledWebTest {

    private static final String SESSION_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QoSSessionsApiDelegate delegate;

    private static SessionInfo session(QosStatus status) {
        return new SessionInfo(new ApplicationServer().ipv4Address("198.51.100.0/24"), "QOS_E",
                UUID.fromString(SESSION_ID), 3600, status);
    }

    @Test
    void createNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(delegate.createSession(any(), any())).thenReturn(
                ResponseEntity.status(HttpStatus.CREATED).body(session(QosStatus.REQUESTED)));

        mvc.perform(post("/quality-on-demand/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device\":{\"phoneNumber\":\"+306912345678\"},"
                                + "\"applicationServer\":{\"ipv4Address\":\"198.51.100.0/24\"},"
                                + "\"qosProfile\":\"QOS_E\",\"duration\":3600}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID));
    }

    @Test
    void getNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(delegate.getSession(any(), any())).thenReturn(ResponseEntity.ok(session(QosStatus.AVAILABLE)));

        mvc.perform(get("/quality-on-demand/v1/sessions/" + SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qosStatus").value("AVAILABLE"));
    }
}
