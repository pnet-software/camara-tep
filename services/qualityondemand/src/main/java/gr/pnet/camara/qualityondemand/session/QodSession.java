package gr.pnet.camara.qualityondemand.session;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import gr.pnet.camara.qualityondemand.generated.status.model.QosStatus;
import gr.pnet.camara.qualityondemand.generated.status.model.StatusInfo;

/**
 * A QoS session as stored in Redis.
 *
 * @param phoneNumber the device's
 * @param clientId the API client that created it, null when auth is off
 * @param echoDevice whether responses name the device: only when the request sent several identifiers
 * @param ueIpv4Addr the device's address the TEP AS session was created for
 * @param applicationServer the server's IPv4 address or network, as requested
 * @param sinkAccessToken the bearer token for notifications to {@code sink}, if any
 * @param duration seconds: granted while AVAILABLE, effective once UNAVAILABLE
 * @param expiresAt when it ends while AVAILABLE, when it ended once UNAVAILABLE
 * @param subscriptionId the TEP AS session with QoS
 */
public record QodSession(
        UUID id,
        String phoneNumber,
        String clientId,
        boolean echoDevice,
        String ueIpv4Addr,
        String applicationServer,
        Ports devicePorts,
        Ports applicationServerPorts,
        String qosProfile,
        String sink,
        String sinkAccessToken,
        int duration,
        Instant startedAt,
        Instant expiresAt,
        QosStatus qosStatus,
        StatusInfo statusInfo,
        String subscriptionId) {

    public boolean available() {
        return qosStatus == QosStatus.AVAILABLE;
    }

    QodSession withDuration(int newDuration) {
        return new QodSession(id, phoneNumber, clientId, echoDevice, ueIpv4Addr, applicationServer, devicePorts,
                applicationServerPorts, qosProfile, sink, sinkAccessToken, newDuration, startedAt,
                startedAt.plusSeconds(newDuration), qosStatus, statusInfo, subscriptionId);
    }

    // Ended now: expiresAt becomes the end, and duration what the session actually lasted
    QodSession terminated(StatusInfo reason, Instant now) {
        Instant end = now.isBefore(expiresAt) ? now : expiresAt;
        int lasted = (int) Math.max(1, Duration.between(startedAt, end).toSeconds());
        return new QodSession(id, phoneNumber, clientId, echoDevice, ueIpv4Addr, applicationServer, devicePorts,
                applicationServerPorts, qosProfile, sink, sinkAccessToken, lasted, startedAt, end,
                QosStatus.UNAVAILABLE, reason, subscriptionId);
    }
}
