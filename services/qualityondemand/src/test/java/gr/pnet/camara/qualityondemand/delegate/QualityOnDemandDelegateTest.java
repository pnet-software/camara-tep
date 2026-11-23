package gr.pnet.camara.qualityondemand.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.AccessTokenSubject;
import gr.pnet.camara.qualityondemand.generated.status.model.ApplicationServer;
import gr.pnet.camara.qualityondemand.generated.status.model.CreateSession;
import gr.pnet.camara.qualityondemand.generated.status.model.Device;
import gr.pnet.camara.qualityondemand.generated.status.model.ExtendSessionDuration;
import gr.pnet.camara.qualityondemand.generated.status.model.QosStatus;
import gr.pnet.camara.qualityondemand.generated.status.model.RetrieveSessionsInput;
import gr.pnet.camara.qualityondemand.generated.status.model.SessionInfo;
import gr.pnet.camara.qualityondemand.generated.status.model.StatusInfo;
import gr.pnet.camara.qualityondemand.session.Caller;
import gr.pnet.camara.qualityondemand.session.Ports;
import gr.pnet.camara.qualityondemand.session.QodSession;
import gr.pnet.camara.qualityondemand.session.SessionService;

class QualityOnDemandDelegateTest {

    private static final String MSISDN = "+306912345678";
    private static final Instant STARTED = Instant.parse("2026-11-06T10:00:00Z");

    private SessionService sessions;
    private AccessTokenSubject tokenSubject;
    private QualityOnDemandDelegate delegate;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        // Two-legged unless a test says otherwise: Mockito answers Optional.empty()
        tokenSubject = mock(AccessTokenSubject.class);
        when(tokenSubject.clientId()).thenReturn(Optional.of("acme-backend"));
        delegate = new QualityOnDemandDelegate(sessions, tokenSubject);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/quality-on-demand/v1/sessions");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createAnswers201WithTheSessionAndItsLocation() {
        QodSession session = session(false, QosStatus.AVAILABLE, null);
        when(sessions.create(any(), eq(MSISDN), eq(false), eq(new Caller("acme-backend", null)))).thenReturn(session);

        ResponseEntity<SessionInfo> response = delegate.createSession(request(new Device().phoneNumber(MSISDN)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasToString(
                "http://localhost/quality-on-demand/v1/sessions/" + session.id());
        SessionInfo info = response.getBody();
        assertThat(info.getSessionId()).isEqualTo(session.id());
        assertThat(info.getQosStatus()).isEqualTo(QosStatus.AVAILABLE);
        assertThat(info.getApplicationServer().getIpv4Address()).isEqualTo("200.24.24.0/24");
        assertThat(info.getDevicePorts().getPorts()).containsExactly(5060);
        assertThat(info.getApplicationServerPorts()).isNull();
        assertThat(info.getSink()).isEqualTo("https://app.example.com/notify");
        // Never echoed: it carries the consumer's access token
        assertThat(info.getSinkCredential()).isNull();
        assertThat(info.getStartedAt()).isEqualTo(OffsetDateTime.parse("2026-11-06T10:00:00Z"));
        assertThat(info.getExpiresAt()).isEqualTo(OffsetDateTime.parse("2026-11-06T11:00:00Z"));
        assertThat(info.getDuration()).isEqualTo(3600);
        assertThat(info.getStatusInfo()).isNull();
        assertThat(info.getDevice()).isNull();
    }

    @Test
    void deviceIsEchoedWhenSeveralIdentifiersWereSent() {
        when(sessions.create(any(), eq(MSISDN), eq(true), any())).thenReturn(session(true, QosStatus.AVAILABLE, null));

        SessionInfo info = delegate.createSession(request(new Device().phoneNumber(MSISDN)
                .ipv6Address("2001:db8::1")), null).getBody();

        assertThat(info.getDevice().getPhoneNumber()).isEqualTo(MSISDN);
        assertThat(info.getDevice().getIpv6Address()).isNull();
    }

    @Test
    void threeLeggedTokenIdentifiesTheDeviceAndTheSubscriber() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));
        when(sessions.create(any(), eq(MSISDN), eq(false), eq(new Caller("acme-backend", MSISDN))))
                .thenReturn(session(false, QosStatus.AVAILABLE, null));

        assertThat(delegate.createSession(request(null), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertCamaraError(() -> delegate.createSession(request(new Device().phoneNumber(MSISDN)), null),
                "UNNECESSARY_IDENTIFIER");
    }

    @Test
    void deviceMustBeAPhoneNumber() {
        assertCamaraError(() -> delegate.createSession(request(null), null), "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.createSession(request(new Device()), null), "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.createSession(request(new Device().ipv6Address("2001:db8::1")), null),
                "UNSUPPORTED_IDENTIFIER");
        verifyNoInteractions(sessions);
    }

    @Test
    void getDeleteAndExtendActForTheCaller() {
        UUID id = UUID.randomUUID();
        Caller caller = new Caller("acme-backend", null);
        QodSession ended = session(false, QosStatus.UNAVAILABLE, StatusInfo.NETWORK_TERMINATED);
        when(sessions.get(id, caller)).thenReturn(ended);
        when(sessions.extend(id, 60, caller)).thenReturn(session(false, QosStatus.AVAILABLE, null));

        assertThat(delegate.getSession(id, null).getBody().getStatusInfo()).isEqualTo(StatusInfo.NETWORK_TERMINATED);
        assertThat(delegate.deleteSession(id, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delegate.extendQosSessionDuration(id, new ExtendSessionDuration(60), null).getBody()
                .getQosStatus()).isEqualTo(QosStatus.AVAILABLE);
        verify(sessions).delete(id, caller);
    }

    @Test
    void retrieveAnswersTheDevicesSessions() {
        when(sessions.retrieve(MSISDN, new Caller("acme-backend", null)))
                .thenReturn(List.of(session(false, QosStatus.AVAILABLE, null)));

        assertThat(delegate.retrieveSessionsByDevice(new RetrieveSessionsInput().device(new Device()
                .phoneNumber(MSISDN)), null).getBody()).hasSize(1);
        assertCamaraError(() -> delegate.retrieveSessionsByDevice(null, null), "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.retrieveSessionsByDevice(new RetrieveSessionsInput(), null),
                "MISSING_IDENTIFIER");
    }

    @Test
    void sessionErrorsPassThrough() {
        UUID id = UUID.randomUUID();
        when(sessions.get(any(), any())).thenThrow(CamaraApiException.notFound());

        assertCamaraError(() -> delegate.getSession(id, null), "NOT_FOUND");
    }

    // Redis unreachable is the service being unavailable, not a bug
    @Test
    void storeFailureIsUnavailable() {
        when(sessions.create(any(), anyString(), anyBoolean(), any()))
                .thenThrow(new QueryTimeoutException("Redis timed out"));

        assertCamaraError(() -> delegate.createSession(request(new Device().phoneNumber(MSISDN)), null),
                "UNAVAILABLE");
    }

    private static CreateSession request(Device device) {
        return new CreateSession(new ApplicationServer().ipv4Address("200.24.24.0/24"), "QOS_E", 3600).device(device);
    }

    private static QodSession session(boolean echoDevice, QosStatus status, StatusInfo statusInfo) {
        return new QodSession(UUID.randomUUID(), MSISDN, "acme-backend", echoDevice, "10.45.0.9", "200.24.24.0/24",
                new Ports(List.of(5060), List.of()), new Ports(null, null), "QOS_E", "https://app.example.com/notify",
                "secret-token", 3600, STARTED, STARTED.plusSeconds(3600), status, statusInfo, "sub-1");
    }

    private static void assertCamaraError(Runnable call, String expectedCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CamaraApiException.class)
                .extracting(e -> ((CamaraApiException) e).getCode())
                .isEqualTo(expectedCode);
    }
}
