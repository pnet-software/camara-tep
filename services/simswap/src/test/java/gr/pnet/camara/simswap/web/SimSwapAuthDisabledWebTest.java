package gr.pnet.camara.simswap.web;

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

import gr.pnet.camara.simswap.generated.status.CheckSimSwapApiController;
import gr.pnet.camara.simswap.generated.status.CheckSimSwapApiDelegate;
import gr.pnet.camara.simswap.generated.status.RetrieveSimSwapDateApiController;
import gr.pnet.camara.simswap.generated.status.RetrieveSimSwapDateApiDelegate;
import gr.pnet.camara.simswap.generated.status.model.CheckSimSwapInfo;
import gr.pnet.camara.simswap.generated.status.model.SimSwapInfo;
import gr.pnet.camara.simswap.security.SecurityConfig;

@WebMvcTest(controllers = { RetrieveSimSwapDateApiController.class, CheckSimSwapApiController.class },
        properties = "camara.auth.enabled=false")
@Import(SecurityConfig.class)
class SimSwapAuthDisabledWebTest {

    private static final String BODY = "{\"phoneNumber\":\"+306912345678\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RetrieveSimSwapDateApiDelegate retrieveDate;

    @MockitoBean
    private CheckSimSwapApiDelegate check;

    @Test
    void retrieveDateNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(retrieveDate.retrieveSimSwapDate(any(), any())).thenReturn(ResponseEntity.ok(
                new SimSwapInfo(OffsetDateTime.parse("2026-10-20T10:00:00Z"))));

        mvc.perform(post("/sim-swap/v2/retrieve-date").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestSimChange").value("2026-10-20T10:00:00Z"));
    }

    @Test
    void checkNeedsNoTokenWhenAuthIsDisabled() throws Exception {
        when(check.checkSimSwap(any(), any())).thenReturn(ResponseEntity.ok(new CheckSimSwapInfo(true)));

        mvc.perform(post("/sim-swap/v2/check").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.swapped").value(true));
    }
}
