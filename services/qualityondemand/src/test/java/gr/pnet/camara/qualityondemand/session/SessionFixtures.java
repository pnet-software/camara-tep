package gr.pnet.camara.qualityondemand.session;

import java.time.Instant;
import java.util.UUID;

import gr.pnet.camara.qualityondemand.generated.status.model.QosStatus;

/** Sessions for tests */
public final class SessionFixtures {

    public static final Instant STARTED = Instant.parse("2026-11-06T10:00:00Z");

    private SessionFixtures() {
    }

    public static QodSession available(String phoneNumber, String server, Ports devicePorts, Ports serverPorts) {
        return new QodSession(UUID.randomUUID(), phoneNumber, "acme-backend", false, "10.45.0.9", server, devicePorts,
                serverPorts, "QOS_E", null, null, 3600, STARTED, STARTED.plusSeconds(3600), QosStatus.AVAILABLE,
                null, "sub-" + UUID.randomUUID());
    }

    public static QodSession available(String phoneNumber) {
        return available(phoneNumber, "200.24.24.0/24", Ports.ANY, Ports.ANY);
    }
}
