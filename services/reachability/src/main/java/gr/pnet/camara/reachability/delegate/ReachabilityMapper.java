package gr.pnet.camara.reachability.delegate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import gr.pnet.camara.reachability.generated.status.model.ConnectivityType;
import gr.pnet.tepclient.model.TepSubscriberStatus;
import gr.pnet.tepclient.model.TepSubscriberStatusPduSession;

/**
 * Maps the TEP core-network view of a subscriber onto CAMARA connectivity types.
 */
final class ReachabilityMapper {

    private static final String IMS_DNN = "ims";

    private ReachabilityMapper() {
    }

    // A UE that isn't registered with the core isn't reachable. A registered one, CONNECTED or IDLE (the
    // network pages it), is reachable through its PDU sessions: one to the IMS data network means SMS (over
    // IMS), one to any other data network means DATA. No sessions means not reachable.
    static List<ConnectivityType> toConnectivity(TepSubscriberStatus status) {
        if (status.getConnectionState() == TepSubscriberStatus.ConnectionStateEnum.DEREGISTERED) {
            return new ArrayList<>();
        }
        List<TepSubscriberStatusPduSession> sessions = status.getPduSessions() == null
                ? List.of()
                : status.getPduSessions().stream().filter(Objects::nonNull).toList();
        List<ConnectivityType> connectivity = new ArrayList<>();
        if (sessions.stream().anyMatch(s -> !isIms(s))) {
            connectivity.add(ConnectivityType.DATA);
        }
        if (sessions.stream().anyMatch(ReachabilityMapper::isIms)) {
            connectivity.add(ConnectivityType.SMS);
        }
        return connectivity;
    }

    // DNNs are case-insensitive (3GPP TS 23.003)
    private static boolean isIms(TepSubscriberStatusPduSession session) {
        return IMS_DNN.equalsIgnoreCase(session.getDnn());
    }
}
