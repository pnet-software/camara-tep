package gr.pnet.camara.qualityondemand.web;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import gr.pnet.camara.qualityondemand.session.SessionService;
import gr.pnet.camara.qualityondemand.tep.TepQosGateway;

/**
 * Where TEP notifies this service of its AS sessions with QoS, at the {@code notificationDestination} sessions are
 * created with plus {@code /notifications}. TEP calls without a token, so this path is outside the API's security
 * and must not be routed through the public ingress.
 */
@RestController
public class TepNotificationsController {

    static final String PATH = "/tep-notifications/notifications";
    // The events by which the network ends the QoS of a session
    static final Set<String> TERMINATING_EVENTS = Set.of("SESSION_TERMINATION", "FAILED_RESOURCES_ALLOCATION");

    private static final Logger log = LoggerFactory.getLogger(TepNotificationsController.class);

    private final SessionService sessions;

    public TepNotificationsController(SessionService sessions) {
        this.sessions = sessions;
    }

    /**
     * 3GPP's UserPlaneNotificationData, read with the events as strings: the generated enum rejects any 3GPP adds.
     */
    record Notification(String transaction, List<EventReport> eventReports) {
    }

    record EventReport(String event) {
    }

    // Always 204: TEP has nothing to do about a notification this service can't use
    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> notification(@RequestBody Notification notification) {
        String subscriptionId = TepQosGateway.subscriptionId(notification.transaction());
        boolean terminating = notification.eventReports() != null && notification.eventReports().stream()
                .anyMatch(report -> report != null && TERMINATING_EVENTS.contains(report.event()));
        if (subscriptionId == null) {
            log.info("TEP notification without a subscription in its transaction, ignored");
        } else if (terminating) {
            sessions.networkTerminated(subscriptionId);
        }
        return ResponseEntity.noContent().build();
    }
}
