package gr.pnet.camara.qualityondemand.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.qualityondemand.config.QodProperties;
import gr.pnet.camara.qualityondemand.generated.status.model.AccessTokenCredential;
import gr.pnet.camara.qualityondemand.generated.status.model.ApplicationServer;
import gr.pnet.camara.qualityondemand.generated.status.model.CreateSession;
import gr.pnet.camara.qualityondemand.generated.status.model.PortsSpec;
import gr.pnet.camara.qualityondemand.generated.status.model.PortsSpecRangesInner;
import gr.pnet.camara.qualityondemand.generated.status.model.QosStatus;
import gr.pnet.camara.qualityondemand.generated.status.model.SinkCredential;
import gr.pnet.camara.qualityondemand.generated.status.model.StatusInfo;
import gr.pnet.camara.qualityondemand.notify.SinkNotifier;
import gr.pnet.camara.qualityondemand.tep.TepQosGateway;
import gr.pnet.camara.qualityondemand.tep.TepQosGateway.Profile;
import gr.pnet.tepclient.model.TepQualityProfile;

class SessionServiceTest {

    private static final String PHONE = "+306912345678";
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final Caller ACME = new Caller("acme-backend", null);
    private static final Profile ACTIVE = new Profile(TepQualityProfile.StatusEnum.ACTIVE, OptionalLong.of(60),
            OptionalLong.of(7200));

    private InMemorySessionStore store;
    private TepQosGateway tep;
    private SinkNotifier notifier;
    private SessionService service;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
        tep = mock(TepQosGateway.class);
        notifier = mock(SinkNotifier.class);
        QodProperties properties = new QodProperties("camara-qod", URI.create("http://qod:8080/tep-notifications"),
                URI.create("https://api.example.com"), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(360), Duration.ofSeconds(5));
        service = new SessionService(store, tep, notifier, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(tep.profile("QOS_E")).thenReturn(ACTIVE);
        when(tep.deviceAddress(PHONE)).thenReturn("10.45.0.9");
        when(tep.createSession(anyString(), any(), anyString())).thenReturn("sub-1");
    }

    @Test
    void createStoresAnAvailableSessionAndNotifies() {
        QodSession session = service.create(request().sink("https://app.example.com/notify"), PHONE, true, ACME);

        assertThat(session.qosStatus()).isEqualTo(QosStatus.AVAILABLE);
        assertThat(session.startedAt()).isEqualTo(NOW);
        assertThat(session.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(session.subscriptionId()).isEqualTo("sub-1");
        assertThat(session.ueIpv4Addr()).isEqualTo("10.45.0.9");
        assertThat(session.clientId()).isEqualTo("acme-backend");
        assertThat(session.echoDevice()).isTrue();
        assertThat(store.sessions).containsValue(session);
        assertThat(store.expiry).containsEntry(session.id(), NOW.plusSeconds(600));
        assertThat(store.locks).isEmpty();
        verify(tep).createSession("10.45.0.9", List.of("permit out ip from 200.24.24.0/24 to 10.45.0.9/32",
                "permit in ip from 10.45.0.9/32 to 200.24.24.0/24"), "QOS_E");
        verify(notifier).statusChanged(session);
    }

    @Test
    void onlyAnIpv4ServerIsSupported() {
        assertCamaraError(() -> service.create(request().applicationServer(
                new ApplicationServer().ipv6Address("2001:db8::/64")), PHONE, false, ACME), "INVALID_ARGUMENT");
        assertCamaraError(() -> service.create(request().applicationServer(
                new ApplicationServer().ipv4Address("200.24.24.300")), PHONE, false, ACME), "INVALID_ARGUMENT");
        verifyNoInteractions(tep);
    }

    @Test
    void reversedPortRangeIsOutOfRange() {
        PortsSpec reversed = new PortsSpec().addRangesItem(new PortsSpecRangesInner(6000, 5000));

        assertCamaraError(() -> service.create(request().devicePorts(reversed), PHONE, false, ACME), "OUT_OF_RANGE");
        assertCamaraError(() -> service.create(request().applicationServerPorts(reversed), PHONE, false, ACME),
                "OUT_OF_RANGE");
    }

    @Test
    void onlyABearerAccessTokenIsTakenForTheSink() {
        assertCamaraError(() -> service.create(request().sinkCredential(
                new SinkCredential(SinkCredential.CredentialTypeEnum.PLAIN)), PHONE, false, ACME),
                "INVALID_CREDENTIAL");
        assertCamaraError(() -> service.create(request().sinkCredential(new AccessTokenCredential()
                .accessToken("t")), PHONE, false, ACME), "INVALID_TOKEN");

        QodSession session = service.create(request().sinkCredential(new AccessTokenCredential().accessToken("t")
                .accessTokenType(AccessTokenCredential.AccessTokenTypeEnum.BEARER)), PHONE, false, ACME);
        assertThat(session.sinkAccessToken()).isEqualTo("t");
    }

    @Test
    void profileMustBeActive() {
        when(tep.profile("QOS_E")).thenReturn(new Profile(TepQualityProfile.StatusEnum.DEPRECATED,
                OptionalLong.empty(), OptionalLong.empty()));

        assertCamaraError(() -> service.create(request(), PHONE, false, ACME),
                "QUALITY_ON_DEMAND.QOS_PROFILE_NOT_APPLICABLE");
    }

    @Test
    void durationMustBeWithinTheProfilesRange() {
        assertCamaraError(() -> service.create(request().duration(59), PHONE, false, ACME),
                "QUALITY_ON_DEMAND.DURATION_OUT_OF_RANGE");
        assertCamaraError(() -> service.create(request().duration(7201), PHONE, false, ACME),
                "QUALITY_ON_DEMAND.DURATION_OUT_OF_RANGE");
        verify(tep, never()).deviceAddress(anyString());

        when(tep.profile("QOS_E")).thenReturn(new Profile(TepQualityProfile.StatusEnum.ACTIVE, OptionalLong.empty(),
                OptionalLong.empty()));
        assertThat(service.create(request().duration(100_000), PHONE, false, ACME).duration()).isEqualTo(100_000);
    }

    @Test
    void overlappingFlowIsAConflict() {
        store.sessions.put(UUID.randomUUID(), SessionFixtures.available(PHONE, "200.24.24.64/26", Ports.ANY,
                Ports.ANY));

        assertCamaraError(() -> service.create(request(), PHONE, false, ACME), "CONFLICT");
        verify(tep, never()).createSession(anyString(), any(), anyString());
        assertThat(store.locks).isEmpty();
    }

    @Test
    void separateFlowsOrEndedSessionsAreNoConflict() {
        QodSession otherPort = SessionFixtures.available(PHONE, "200.24.24.0/24", Ports.ANY,
                new Ports(List.of(443), List.of()));
        QodSession ended = SessionFixtures.available(PHONE).terminated(StatusInfo.NETWORK_TERMINATED, NOW);
        store.sessions.put(otherPort.id(), otherPort);
        store.sessions.put(ended.id(), ended);

        assertThat(service.create(request().applicationServerPorts(new PortsSpec().addPortsItem(80)), PHONE, false,
                ACME).qosStatus()).isEqualTo(QosStatus.AVAILABLE);
    }

    @Test
    void deviceBeingCreatedForIsAConflict() {
        store.locks.add(PHONE);

        assertCamaraError(() -> service.create(request(), PHONE, false, ACME), "CONFLICT");
        verify(tep, never()).createSession(anyString(), any(), anyString());
    }

    // The network's QoS is ended again rather than left without a session
    @Test
    void sessionThatCannotBeStoredIsEndedInTep() {
        store.failOnCreate = new QueryTimeoutException("Redis timed out");

        assertThatThrownBy(() -> service.create(request(), PHONE, false, ACME))
                .isInstanceOf(QueryTimeoutException.class);
        verify(tep).deleteSession("sub-1");
        assertThat(store.locks).isEmpty();
        verifyNoInteractions(notifier);
    }

    @Test
    void sessionIsOnlySeenByItsClientAndSubscriber() {
        QodSession session = stored(SessionFixtures.available(PHONE));

        assertThat(service.get(session.id(), ACME)).isEqualTo(session);
        assertThat(service.get(session.id(), new Caller("acme-backend", PHONE))).isEqualTo(session);
        assertThat(service.get(session.id(), new Caller(null, null))).isEqualTo(session);
        assertCamaraError(() -> service.get(session.id(), new Caller("other-client", null)), "NOT_FOUND");
        assertCamaraError(() -> service.get(session.id(), new Caller("acme-backend", "+306900000000")), "NOT_FOUND");
        assertCamaraError(() -> service.get(UUID.randomUUID(), ACME), "NOT_FOUND");
    }

    @Test
    void deleteEndsTheTepSessionFirstThenNotifies() {
        QodSession session = stored(SessionFixtures.available(PHONE));

        service.delete(session.id(), ACME);

        assertThat(store.sessions).doesNotContainKey(session.id());
        InOrder order = inOrder(tep, notifier);
        order.verify(tep).deleteSession(session.subscriptionId());
        ArgumentCaptor<QodSession> notified = ArgumentCaptor.forClass(QodSession.class);
        order.verify(notifier).statusChanged(notified.capture());
        assertThat(notified.getValue().qosStatus()).isEqualTo(QosStatus.UNAVAILABLE);
        assertThat(notified.getValue().statusInfo()).isEqualTo(StatusInfo.DELETE_REQUESTED);
    }

    @Test
    void deleteKeepsTheSessionWhenTepFails() {
        QodSession session = stored(SessionFixtures.available(PHONE));
        org.mockito.Mockito.doThrow(CamaraApiException.unavailable(new RuntimeException("down")))
                .when(tep).deleteSession(session.subscriptionId());

        assertCamaraError(() -> service.delete(session.id(), ACME), "UNAVAILABLE");
        assertThat(store.sessions).containsKey(session.id());
        verifyNoInteractions(notifier);
    }

    // It already ended in the network and was notified then
    @Test
    void deleteOfAnEndedSessionOnlyForgetsIt() {
        QodSession session = stored(SessionFixtures.available(PHONE).terminated(StatusInfo.NETWORK_TERMINATED, NOW));

        service.delete(session.id(), ACME);

        assertThat(store.sessions).doesNotContainKey(session.id());
        verify(tep, never()).deleteSession(anyString());
        verifyNoInteractions(notifier);
    }

    @Test
    void extendAddsUpToTheProfilesMaximum() {
        QodSession session = stored(SessionFixtures.available(PHONE));

        QodSession extended = service.extend(session.id(), 600, ACME);
        assertThat(extended.duration()).isEqualTo(4200);
        assertThat(extended.expiresAt()).isEqualTo(SessionFixtures.STARTED.plusSeconds(4200));
        assertThat(store.expiry).containsEntry(session.id(), extended.expiresAt());

        assertThat(service.extend(session.id(), 100_000, ACME).duration()).isEqualTo(7200);
    }

    @Test
    void extendWithoutAMaximumAddsItAll() {
        when(tep.profile("QOS_E")).thenReturn(new Profile(TepQualityProfile.StatusEnum.ACTIVE, OptionalLong.empty(),
                OptionalLong.empty()));
        QodSession session = stored(SessionFixtures.available(PHONE));

        assertThat(service.extend(session.id(), 100_000, ACME).duration()).isEqualTo(103_600);
    }

    // A maximum lowered after creation leaves the session as it is
    @Test
    void extendNeverShortens() {
        when(tep.profile("QOS_E")).thenReturn(new Profile(TepQualityProfile.StatusEnum.ACTIVE, OptionalLong.empty(),
                OptionalLong.of(60)));
        QodSession session = stored(SessionFixtures.available(PHONE));

        assertThat(service.extend(session.id(), 60, ACME).duration()).isEqualTo(3600);
    }

    @Test
    void onlyAnAvailableSessionIsExtended() {
        QodSession session = stored(SessionFixtures.available(PHONE).terminated(StatusInfo.NETWORK_TERMINATED, NOW));

        assertCamaraError(() -> service.extend(session.id(), 60, ACME),
                "QUALITY_ON_DEMAND.SESSION_EXTENSION_NOT_ALLOWED");
    }

    @Test
    void retrieveAnswersTheCallersSessionsOfTheDevice() {
        QodSession mine = stored(SessionFixtures.available(PHONE));
        stored(new QodSession(UUID.randomUUID(), PHONE, "other-client", false, "10.45.0.9", "1.2.3.4", Ports.ANY,
                Ports.ANY, "QOS_E", null, null, 60, NOW, NOW.plusSeconds(60), QosStatus.AVAILABLE, null, "sub-x"));
        stored(SessionFixtures.available("+306900000000"));

        assertThat(service.retrieve(PHONE, ACME)).containsExactly(mine);
    }

    @Test
    void expiredSessionIsEndedInTepKeptAndNotified() {
        QodSession session = stored(SessionFixtures.available(PHONE));
        store.expiry.put(session.id(), NOW.minusSeconds(1));

        service.expireDue();

        QodSession ended = store.sessions.get(session.id());
        assertThat(ended.qosStatus()).isEqualTo(QosStatus.UNAVAILABLE);
        assertThat(ended.statusInfo()).isEqualTo(StatusInfo.DURATION_EXPIRED);
        assertThat(store.retained).containsEntry(session.id(), Duration.ofSeconds(360));
        assertThat(store.expiry).doesNotContainKey(session.id());
        verify(tep).deleteSession(session.subscriptionId());
        verify(notifier).statusChanged(ended);
    }

    @Test
    void expiryIsRetriedWhenTepFails() {
        QodSession session = stored(SessionFixtures.available(PHONE));
        store.expiry.put(session.id(), NOW.minusSeconds(1));
        org.mockito.Mockito.doThrow(CamaraApiException.unavailable(new RuntimeException("down")))
                .when(tep).deleteSession(session.subscriptionId());

        service.expireDue();

        assertThat(store.sessions.get(session.id()).available()).isTrue();
        assertThat(store.expiry).containsEntry(session.id(), NOW.plusSeconds(30));
        verifyNoInteractions(notifier);
    }

    // Found due before an extend moved it on: put back at its new end
    @Test
    void sessionExtendedSinceItWasDueIsNotEnded() {
        QodSession session = stored(new QodSession(UUID.randomUUID(), PHONE, "acme-backend", false, "10.45.0.9",
                "1.2.3.4", Ports.ANY, Ports.ANY, "QOS_E", null, null, 60, NOW, NOW.plusSeconds(60),
                QosStatus.AVAILABLE, null, "sub-x"));
        store.expiry.put(session.id(), NOW);

        service.expireDue();

        assertThat(store.expiry).containsEntry(session.id(), NOW.plusSeconds(60));
        verify(tep, never()).deleteSession(anyString());
    }

    @Test
    void expiryLeavesASessionAnotherReplicaClaimed() {
        SessionStore claimedElsewhere = mock(SessionStore.class);
        UUID id = UUID.randomUUID();
        when(claimedElsewhere.dueForExpiry(any())).thenReturn(List.of(id));
        when(claimedElsewhere.claimExpiry(id)).thenReturn(false);
        SessionService other = new SessionService(claimedElsewhere, tep, notifier, null,
                Clock.fixed(NOW, ZoneOffset.UTC));

        other.expireDue();

        verify(claimedElsewhere, never()).find(id);
        verify(tep, never()).deleteSession(anyString());
    }

    @Test
    void expiryRunSurvivesTheStoreFailing() {
        SessionStore down = mock(SessionStore.class);
        when(down.dueForExpiry(any())).thenThrow(new QueryTimeoutException("Redis timed out"));
        UUID id = UUID.randomUUID();
        SessionStore failingOne = mock(SessionStore.class);
        when(failingOne.dueForExpiry(any())).thenReturn(List.of(id));
        when(failingOne.claimExpiry(eq(id))).thenThrow(new QueryTimeoutException("Redis timed out"));

        new SessionService(down, tep, notifier, null, Clock.fixed(NOW, ZoneOffset.UTC)).expireDue();
        new SessionService(failingOne, tep, notifier, null, Clock.fixed(NOW, ZoneOffset.UTC)).expireDue();

        verifyNoInteractions(notifier);
    }

    // Started ten minutes ago for an hour: it ends now, having lasted ten minutes
    @Test
    void networkTerminationEndsTheSessionNow() {
        QodSession session = stored(new QodSession(UUID.randomUUID(), PHONE, "acme-backend", false, "10.45.0.9",
                "1.2.3.4", Ports.ANY, Ports.ANY, "QOS_E", null, null, 3600, NOW.minusSeconds(600),
                NOW.plusSeconds(3000), QosStatus.AVAILABLE, null, "sub-x"));

        service.networkTerminated(session.subscriptionId());

        QodSession ended = store.sessions.get(session.id());
        assertThat(ended.qosStatus()).isEqualTo(QosStatus.UNAVAILABLE);
        assertThat(ended.statusInfo()).isEqualTo(StatusInfo.NETWORK_TERMINATED);
        assertThat(ended.expiresAt()).isEqualTo(NOW);
        assertThat(ended.duration()).isEqualTo(600);
        assertThat(store.retained).containsEntry(session.id(), Duration.ofSeconds(360));
        verify(notifier).statusChanged(ended);
        verify(tep, never()).deleteSession(anyString());
    }

    @Test
    void networkTerminationOfAnUnknownOrEndedSessionIsIgnored() {
        QodSession ended = stored(SessionFixtures.available(PHONE).terminated(StatusInfo.DURATION_EXPIRED, NOW));

        service.networkTerminated("sub-unknown");
        service.networkTerminated(ended.subscriptionId());

        verifyNoInteractions(notifier);
    }

    private QodSession stored(QodSession session) {
        store.sessions.put(session.id(), session);
        if (session.available()) {
            store.expiry.put(session.id(), session.expiresAt());
        }
        return session;
    }

    private static CreateSession request() {
        return new CreateSession(new ApplicationServer().ipv4Address("200.24.24.0/24"), "QOS_E", 600);
    }

    private static void assertCamaraError(Runnable call, String expectedCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CamaraApiException.class)
                .extracting(e -> ((CamaraApiException) e).getCode())
                .isEqualTo(expectedCode);
    }
}
