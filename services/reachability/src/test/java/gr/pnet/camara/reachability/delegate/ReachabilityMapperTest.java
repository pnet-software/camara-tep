package gr.pnet.camara.reachability.delegate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import gr.pnet.camara.reachability.generated.status.model.ConnectivityType;
import gr.pnet.tepclient.model.TepSubscriberStatus;
import gr.pnet.tepclient.model.TepSubscriberStatusPduSession;

class ReachabilityMapperTest {

    @Test
    void nonImsSessionMeansData() {
        assertThat(ReachabilityMapper.toConnectivity(status(session("internet"))))
                .containsExactly(ConnectivityType.DATA);
    }

    @Test
    void imsSessionMeansSms() {
        assertThat(ReachabilityMapper.toConnectivity(status(session("ims"))))
                .containsExactly(ConnectivityType.SMS);
    }

    // DATA first, as in the spec's example, whatever the order of the sessions
    @Test
    void imsAndOtherSessionsMeanDataAndSms() {
        assertThat(ReachabilityMapper.toConnectivity(status(session("ims"), session("internet"))))
                .containsExactly(ConnectivityType.DATA, ConnectivityType.SMS);
    }

    @Test
    void eachTypeIsListedOnce() {
        TepSubscriberStatus status = status(session("internet"), session("mms"), session("ims"), session("ims"));

        assertThat(ReachabilityMapper.toConnectivity(status))
                .containsExactly(ConnectivityType.DATA, ConnectivityType.SMS);
    }

    @Test
    void imsDnnIsCaseInsensitive() {
        assertThat(ReachabilityMapper.toConnectivity(status(session("IMS"))))
                .containsExactly(ConnectivityType.SMS);
    }

    @Test
    void sessionWithoutDnnIsData() {
        assertThat(ReachabilityMapper.toConnectivity(status(session(null))))
                .containsExactly(ConnectivityType.DATA);
    }

    @Test
    void noSessionsMeansNotReachable() {
        assertThat(ReachabilityMapper.toConnectivity(new TepSubscriberStatus().pduSessions(null))).isEmpty();
        assertThat(ReachabilityMapper.toConnectivity(new TepSubscriberStatus().pduSessions(List.of()))).isEmpty();
    }

    @Test
    void nullSessionEntryIsIgnored() {
        assertThat(ReachabilityMapper.toConnectivity(new TepSubscriberStatus().pduSessions(Arrays.asList(null,
                session("ims"))))).containsExactly(ConnectivityType.SMS);
    }

    @Test
    void deregisteredUeIsNotReachableWhateverItsSessions() {
        TepSubscriberStatus status = status(session("internet"), session("ims"))
                .connectionState(TepSubscriberStatus.ConnectionStateEnum.DEREGISTERED);

        assertThat(ReachabilityMapper.toConnectivity(status)).isEmpty();
    }

    // The network pages an IDLE UE, so it stays reachable through its sessions
    @Test
    void idleUeIsReachableThroughItsSessions() {
        TepSubscriberStatus status = status(session("internet"), session("ims"))
                .connectionState(TepSubscriberStatus.ConnectionStateEnum.IDLE);

        assertThat(ReachabilityMapper.toConnectivity(status))
                .containsExactly(ConnectivityType.DATA, ConnectivityType.SMS);
    }

    @Test
    void registeredUeWithoutSessionsIsNotReachable() {
        TepSubscriberStatus status = new TepSubscriberStatus()
                .connectionState(TepSubscriberStatus.ConnectionStateEnum.CONNECTED);

        assertThat(ReachabilityMapper.toConnectivity(status)).isEmpty();
    }

    // CONNECTED, as TEP reports for a UE with a signalling connection
    private static TepSubscriberStatus status(TepSubscriberStatusPduSession... sessions) {
        return new TepSubscriberStatus().connectionState(TepSubscriberStatus.ConnectionStateEnum.CONNECTED)
                .pduSessions(List.of(sessions));
    }

    private static TepSubscriberStatusPduSession session(String dnn) {
        return new TepSubscriberStatusPduSession().pduSessionId("1").dnn(dnn);
    }
}
