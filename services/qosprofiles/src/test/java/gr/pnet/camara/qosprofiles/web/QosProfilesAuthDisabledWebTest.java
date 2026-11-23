package gr.pnet.camara.qosprofiles.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import gr.pnet.camara.qosprofiles.generated.status.QoSProfilesApiController;
import gr.pnet.camara.qosprofiles.generated.status.QoSProfilesApiDelegate;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfile;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfileStatusEnum;
import gr.pnet.camara.qosprofiles.security.SecurityConfig;

@WebMvcTest(controllers = QoSProfilesApiController.class, properties = "camara.auth.enabled=false")
@Import(SecurityConfig.class)
class QosProfilesAuthDisabledWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QoSProfilesApiDelegate delegate;

    @Test
    void retrieveNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(delegate.retrieveQoSProfiles(any(), any())).thenReturn(ResponseEntity.ok(
                List.of(new QosProfile("voice", QosProfileStatusEnum.ACTIVE))));

        mvc.perform(post("/qos-profiles/v1/retrieve-qos-profiles").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device\":{\"phoneNumber\":\"+306912345678\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("voice"));
    }

    @Test
    void getByNameNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(delegate.getQosProfile(eq("voice"), any())).thenReturn(ResponseEntity.ok(
                new QosProfile("voice", QosProfileStatusEnum.ACTIVE)));

        mvc.perform(get("/qos-profiles/v1/qos-profiles/voice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("voice"));
    }
}
