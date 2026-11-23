package gr.pnet.camara.qualityondemand.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import gr.pnet.camara.common.security.CamaraAuthErrorHandler;
import gr.pnet.camara.qualityondemand.security.SecurityConfig;
import gr.pnet.camara.qualityondemand.session.SessionService;

// With auth on, as deployed: TEP calls without a token
@WebMvcTest(TepNotificationsController.class)
@Import({ SecurityConfig.class, CamaraAuthErrorHandler.class })
class TepNotificationsWebTest {

    private static final String URL = "/tep-notifications/notifications";
    private static final String TRANSACTION =
            "http://tep:8080/tep-api/v1/3gpp-as-session-with-qos/v1/camara-qod/subscriptions/sub-1";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SessionService sessions;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void sessionTerminationNeedsNoTokenAndEndsTheSession() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(notification("SESSION_TERMINATION")))
                .andExpect(status().isNoContent());

        verify(sessions).networkTerminated("sub-1");
    }

    @Test
    void failedResourceAllocationEndsTheSessionToo() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(notification("FAILED_RESOURCES_ALLOCATION")))
                .andExpect(status().isNoContent());

        verify(sessions).networkTerminated("sub-1");
    }

    // An event the generated enum doesn't know must not be a 400
    @Test
    void otherOrUnknownEventsAreAcknowledgedAndIgnored() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(notification("QOS_GUARANTEED")))
                .andExpect(status().isNoContent());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(notification("SOMETHING_NEW")))
                .andExpect(status().isNoContent());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"transaction\":\"" + TRANSACTION
                        + "\"}"))
                .andExpect(status().isNoContent());

        verify(sessions, never()).networkTerminated(anyString());
    }

    @Test
    void notificationWithoutASubscriptionIsIgnored() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transaction\":\"nope\",\"eventReports\":[{\"event\":\"SESSION_TERMINATION\"}]}"))
                .andExpect(status().isNoContent());

        verify(sessions, never()).networkTerminated(anyString());
    }

    private static String notification(String event) {
        return "{\"transaction\":\"" + TRANSACTION + "\",\"eventReports\":[{\"event\":\"" + event + "\"}]}";
    }
}
