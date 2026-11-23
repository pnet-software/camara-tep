package gr.pnet.camara.qualityondemand.tep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.qualityondemand.config.QodProperties;
import gr.pnet.tepclient.AsSessionWithRequiredQoSSubscriptionsApi;
import gr.pnet.tepclient.IndividualAsSessionWithRequiredQoSSubscriptionApi;
import gr.pnet.tepclient.QualityProfilesApi;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.AsSessionWithQoSSubscription;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepQualityProfile;
import gr.pnet.tepclient.model.TepQualityProfileDuration;
import gr.pnet.tepclient.model.TepSubscriberStatus;
import gr.pnet.tepclient.model.TepSubscriberStatusPduSession;

class TepQosGatewayTest {

    private static final String PHONE = "+306912345678";

    private QualityProfilesApi profilesApi;
    private SubscribersApi subscribersApi;
    private AsSessionWithRequiredQoSSubscriptionsApi asSessionsApi;
    private IndividualAsSessionWithRequiredQoSSubscriptionApi asSessionApi;
    private TepQosGateway gateway;

    @BeforeEach
    void setUp() {
        profilesApi = mock(QualityProfilesApi.class);
        subscribersApi = mock(SubscribersApi.class);
        asSessionsApi = mock(AsSessionWithRequiredQoSSubscriptionsApi.class);
        asSessionApi = mock(IndividualAsSessionWithRequiredQoSSubscriptionApi.class);
        QodProperties properties = new QodProperties("camara-qod", URI.create("http://qod:8080/tep-notifications"),
                URI.create("https://api.example.com"), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(360), Duration.ofSeconds(5));
        gateway = new TepQosGateway(profilesApi, subscribersApi, asSessionsApi, asSessionApi, properties);
    }

    @Test
    void profileDurationsAreInSeconds() {
        when(profilesApi.getQualityProfile(eq("QOS_E"), any())).thenReturn(new TepQualityProfile().name("QOS_E")
                .status(TepQualityProfile.StatusEnum.ACTIVE)
                .minDuration(duration(1, TepQualityProfileDuration.UnitEnum.MINUTES))
                .maxDuration(duration(2, TepQualityProfileDuration.UnitEnum.HOURS)));

        TepQosGateway.Profile profile = gateway.profile("QOS_E");

        assertThat(profile.status()).isEqualTo(TepQualityProfile.StatusEnum.ACTIVE);
        assertThat(profile.minDuration()).hasValue(60);
        assertThat(profile.maxDuration()).hasValue(7200);
    }

    @Test
    void profileWithoutDurationsHasNoBounds() {
        when(profilesApi.getQualityProfile(eq("QOS_E"), any())).thenReturn(new TepQualityProfile().name("QOS_E")
                .status(TepQualityProfile.StatusEnum.ACTIVE)
                .maxDuration(new TepQualityProfileDuration().value(5)));

        TepQosGateway.Profile profile = gateway.profile("QOS_E");

        assertThat(profile.minDuration()).isEmpty();
        assertThat(profile.maxDuration()).isEmpty();
    }

    @Test
    void unknownProfileIsAnInvalidArgument() {
        when(profilesApi.getQualityProfile(eq("nope"), any())).thenThrow(tepError(HttpStatus.NOT_FOUND));
        when(profilesApi.getQualityProfile(eq("down"), any())).thenThrow(tepError(HttpStatus.INTERNAL_SERVER_ERROR));

        assertCamaraError(() -> gateway.profile("nope"), "INVALID_ARGUMENT");
        assertCamaraError(() -> gateway.profile("down"), "UNAVAILABLE");
    }

    @Test
    void deviceAddressIsThatOfItsFirstNonImsSession() {
        when(subscribersApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(PHONE)), any())).thenReturn(new TepSubscriberStatus()
                .connectionState(TepSubscriberStatus.ConnectionStateEnum.CONNECTED)
                .addPduSessionsItem(session("ims", "10.46.0.2"))
                .addPduSessionsItem(session("internet", " "))
                .addPduSessionsItem(session("internet", "10.45.0.9")));

        assertThat(gateway.deviceAddress(PHONE)).isEqualTo("10.45.0.9");
    }

    @Test
    void deviceWithoutADataSessionIsNotApplicable() {
        when(subscribersApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(PHONE)), any())).thenReturn(new TepSubscriberStatus()
                .connectionState(TepSubscriberStatus.ConnectionStateEnum.CONNECTED)
                .addPduSessionsItem(session("IMS", "10.46.0.2")));
        assertCamaraError(() -> gateway.deviceAddress(PHONE), "SERVICE_NOT_APPLICABLE");

        when(subscribersApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(PHONE)), any())).thenReturn(new TepSubscriberStatus()
                .connectionState(TepSubscriberStatus.ConnectionStateEnum.DEREGISTERED)
                .addPduSessionsItem(session("internet", "10.45.0.9")));
        assertCamaraError(() -> gateway.deviceAddress(PHONE), "SERVICE_NOT_APPLICABLE");

        when(subscribersApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(PHONE)), any())).thenReturn(new TepSubscriberStatus().pduSessions(null));
        assertCamaraError(() -> gateway.deviceAddress(PHONE), "SERVICE_NOT_APPLICABLE");
    }

    @Test
    void unknownDeviceIsIdentifierNotFound() {
        // doThrow: when(...) would call the mock and hit the stubbed failure
        doThrow(tepError(HttpStatus.NOT_FOUND)).when(subscribersApi).getSubscriberStatus(eq(new TepDevice().phoneNumber(PHONE)), any());
        assertCamaraError(() -> gateway.deviceAddress(PHONE), "IDENTIFIER_NOT_FOUND");

        doThrow(new ResourceAccessException("timed out")).when(subscribersApi).getSubscriberStatus(eq(new TepDevice().phoneNumber(PHONE)), any());
        assertCamaraError(() -> gateway.deviceAddress(PHONE), "UNAVAILABLE");
    }

    @Test
    void createSendsOnlyWhatTepTakesAndAnswersTheSubscriptionId() {
        when(asSessionsApi.createASSessionWithQoSSubscription(eq("camara-qod"), any()))
                .thenReturn(new AsSessionWithQoSSubscription().self("http://tep/3gpp-as-session-with-qos/v1/camara-qod"
                        + "/subscriptions/sub-1"));

        String id = gateway.createSession("10.45.0.9", List.of("permit out ip from 1.2.3.4 to 10.45.0.9/32"),
                "QOS_E");

        assertThat(id).isEqualTo("sub-1");
        ArgumentCaptor<AsSessionWithQoSSubscription> sent = ArgumentCaptor.forClass(AsSessionWithQoSSubscription.class);
        verify(asSessionsApi).createASSessionWithQoSSubscription(eq("camara-qod"), sent.capture());
        AsSessionWithQoSSubscription subscription = sent.getValue();
        assertThat(subscription.getUeIpv4Addr()).isEqualTo("10.45.0.9");
        assertThat(subscription.getQosReference()).isEqualTo("QOS_E");
        // TEP passes it to the PCF as suppFeat, which the PCF requires
        assertThat(subscription.getSupportedFeatures()).isEqualTo("12");
        assertThat(subscription.getNotificationDestination()).isEqualTo("http://qod:8080/tep-notifications");
        assertThat(subscription.getFlowInfo()).singleElement().satisfies(flow -> {
            assertThat(flow.getFlowId()).isEqualTo(1);
            assertThat(flow.getFlowDescriptions()).containsExactly("permit out ip from 1.2.3.4 to 10.45.0.9/32");
        });
        // Left out, not sent as []
        assertThat(subscription.getEthFlowInfo()).isNull();
        assertThat(subscription.getEnEthFlowInfo()).isNull();
        assertThat(subscription.getAltQoSReferences()).isNull();
        assertThat(subscription.getAltQosReqs()).isNull();
        assertThat(subscription.getEvents()).isNull();
    }

    @Test
    void createRefusedByTepIsNotApplicableAndAFailureUnavailable() {
        doThrow(tepError(HttpStatus.FORBIDDEN)).when(asSessionsApi)
                .createASSessionWithQoSSubscription(anyString(), any());
        assertCamaraError(() -> gateway.createSession("10.45.0.9", List.of(), "QOS_E"), "SERVICE_NOT_APPLICABLE");

        doThrow(tepError(HttpStatus.BAD_GATEWAY)).when(asSessionsApi)
                .createASSessionWithQoSSubscription(anyString(), any());
        assertCamaraError(() -> gateway.createSession("10.45.0.9", List.of(), "QOS_E"), "UNAVAILABLE");

        doThrow(new ResourceAccessException("timed out")).when(asSessionsApi)
                .createASSessionWithQoSSubscription(anyString(), any());
        assertCamaraError(() -> gateway.createSession("10.45.0.9", List.of(), "QOS_E"), "UNAVAILABLE");
    }

    @Test
    void createWithoutASubscriptionIdIsUnavailable() {
        when(asSessionsApi.createASSessionWithQoSSubscription(anyString(), any()))
                .thenReturn(new AsSessionWithQoSSubscription());

        assertCamaraError(() -> gateway.createSession("10.45.0.9", List.of(), "QOS_E"), "UNAVAILABLE");
    }

    @Test
    void deleteOfASessionTepNoLongerHasSucceeds() {
        doThrow(tepError(HttpStatus.NOT_FOUND)).when(asSessionApi)
                .deleteIndASSessionWithQoSSubscription("camara-qod", "gone");
        doThrow(tepError(HttpStatus.INTERNAL_SERVER_ERROR)).when(asSessionApi)
                .deleteIndASSessionWithQoSSubscription("camara-qod", "broken");

        assertThatCode(() -> gateway.deleteSession("gone")).doesNotThrowAnyException();
        assertThatCode(() -> gateway.deleteSession("sub-1")).doesNotThrowAnyException();
        assertCamaraError(() -> gateway.deleteSession("broken"), "UNAVAILABLE");
    }

    @Test
    void subscriptionIdIsTheLastSegmentOfItsUrl() {
        assertThat(TepQosGateway.subscriptionId("http://tep/x/v1/scs/subscriptions/sub-1")).isEqualTo("sub-1");
        assertThat(TepQosGateway.subscriptionId("/scs/subscriptions/sub-1/")).isEqualTo("sub-1");
        assertThat(TepQosGateway.subscriptionId("/scs/subscriptions/")).isNull();
        assertThat(TepQosGateway.subscriptionId("sub-1")).isNull();
        assertThat(TepQosGateway.subscriptionId(null)).isNull();
    }

    // TEP's reason for refusing a session is logged on one line, and cut short
    @Test
    void refusalBodyIsLoggedOnOneLineAndCutShort() {
        assertThat(TepQosGateway.abbreviate("{\n  \"detail\": \"unknown qosReference\"\n}"))
                .isEqualTo("{ \"detail\": \"unknown qosReference\" }");
        assertThat(TepQosGateway.abbreviate("x".repeat(600))).hasSize(503).endsWith("...");
        assertThat(TepQosGateway.abbreviate("")).isEqualTo("(no body)");
        assertThat(TepQosGateway.abbreviate(null)).isEqualTo("(no body)");
    }

    private static TepQualityProfileDuration duration(int value, TepQualityProfileDuration.UnitEnum unit) {
        return new TepQualityProfileDuration().value(value).unit(unit);
    }

    private static TepSubscriberStatusPduSession session(String dnn, String address) {
        return new TepSubscriberStatusPduSession().dnn(dnn).pduAddress(address);
    }

    private static RuntimeException tepError(HttpStatus status) {
        HttpStatusCode code = HttpStatusCode.valueOf(status.value());
        if (status.is4xxClientError()) {
            return HttpClientErrorException.create(code, status.getReasonPhrase(), new HttpHeaders(), new byte[0],
                    StandardCharsets.UTF_8);
        }
        return HttpServerErrorException.create(code, status.getReasonPhrase(), new HttpHeaders(), new byte[0],
                StandardCharsets.UTF_8);
    }

    private static void assertCamaraError(Runnable call, String expectedCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CamaraApiException.class)
                .extracting(e -> ((CamaraApiException) e).getCode())
                .isEqualTo(expectedCode);
    }
}
