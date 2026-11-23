package gr.pnet.camara.deviceswap.web;

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

import gr.pnet.camara.deviceswap.generated.status.CheckDeviceSwapApiController;
import gr.pnet.camara.deviceswap.generated.status.CheckDeviceSwapApiDelegate;
import gr.pnet.camara.deviceswap.generated.status.RetrieveDeviceSwapDateApiController;
import gr.pnet.camara.deviceswap.generated.status.RetrieveDeviceSwapDateApiDelegate;
import gr.pnet.camara.deviceswap.generated.status.model.CheckDeviceSwapInfo;
import gr.pnet.camara.deviceswap.generated.status.model.DeviceSwapInfo;
import gr.pnet.camara.deviceswap.security.SecurityConfig;

@WebMvcTest(controllers = { RetrieveDeviceSwapDateApiController.class, CheckDeviceSwapApiController.class },
        properties = "camara.auth.enabled=false")
@Import(SecurityConfig.class)
class DeviceSwapAuthDisabledWebTest {

    private static final String BODY = "{\"phoneNumber\":\"+306912345678\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RetrieveDeviceSwapDateApiDelegate retrieveDate;

    @MockitoBean
    private CheckDeviceSwapApiDelegate check;

    @Test
    void retrieveDateNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(retrieveDate.retrieveDeviceSwapDate(any(), any())).thenReturn(ResponseEntity.ok(
                new DeviceSwapInfo(OffsetDateTime.parse("2026-10-20T10:00:00Z"))));

        mvc.perform(post("/device-swap/v1/retrieve-date").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestDeviceChange").value("2026-10-20T10:00:00Z"));
    }

    @Test
    void checkNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(check.checkDeviceSwap(any(), any())).thenReturn(ResponseEntity.ok(new CheckDeviceSwapInfo(true)));

        mvc.perform(post("/device-swap/v1/check").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.swapped").value(true));
    }
}
