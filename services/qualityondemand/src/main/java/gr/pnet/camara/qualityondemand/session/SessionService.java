package gr.pnet.camara.qualityondemand.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.qualityondemand.config.QodProperties;
import gr.pnet.camara.qualityondemand.generated.status.model.AccessTokenCredential;
import gr.pnet.camara.qualityondemand.generated.status.model.ApplicationServer;
import gr.pnet.camara.qualityondemand.generated.status.model.CreateSession;
import gr.pnet.camara.qualityondemand.generated.status.model.PortsSpec;
import gr.pnet.camara.qualityondemand.generated.status.model.QosStatus;
import gr.pnet.camara.qualityondemand.generated.status.model.SinkCredential;
import gr.pnet.camara.qualityondemand.generated.status.model.StatusInfo;
import gr.pnet.camara.qualityondemand.notify.SinkNotifier;
import gr.pnet.camara.qualityondemand.tep.TepQosGateway;
import gr.pnet.camara.qualityondemand.tep.TepQosGateway.Profile;
import gr.pnet.tepclient.model.TepQualityProfile;

/**
 * The life of a QoS session: created as a TEP AS session with QoS, then ended by its consumer (delete), by its
 * duration running out (expiry) or by the network (TEP's notification). An ended session is still answered, as
 * UNAVAILABLE, for {@code camara.qod.terminated-retention}.
 */
@Service
public class SessionService {

    // Long enough for the TEP calls of one create, short enough to free a device a crashed replica held
    static final Duration CREATE_LOCK = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionStore store;
    private final TepQosGateway tep;
    private final SinkNotifier notifier;
    private final QodProperties properties;
    private final Clock clock;

    public SessionService(SessionStore store, TepQosGateway tep, SinkNotifier notifier, QodProperties properties,
            Clock clock) {
        this.store = store;
        this.tep = tep;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    /** Creates the session for the device, AVAILABLE as soon as TEP has applied the QoS */
    public QodSession create(CreateSession request, String phoneNumber, boolean echoDevice, Caller caller) {
        String server = applicationServer(request.getApplicationServer());
        Ports devicePorts = validPorts(request.getDevicePorts());
        Ports serverPorts = validPorts(request.getApplicationServerPorts());
        String sinkAccessToken = sinkAccessToken(request.getSinkCredential());
        checkProfile(tep.profile(request.getQosProfile()), request.getDuration());
        String ueAddress = tep.deviceAddress(phoneNumber);

        // So two creates for the device can't both find no conflict
        if (!store.lockDevice(phoneNumber, CREATE_LOCK)) {
            throw conflict();
        }
        try {
            if (store.findByDevice(phoneNumber).stream()
                    .filter(QodSession::available)
                    .anyMatch(other -> FlowRules.overlap(server, devicePorts, serverPorts, other))) {
                throw conflict();
            }
            String subscriptionId = tep.createSession(ueAddress,
                    FlowRules.flowDescriptions(ueAddress, devicePorts, server, serverPorts), request.getQosProfile());
            Instant now = clock.instant();
            QodSession session = new QodSession(UUID.randomUUID(), phoneNumber, caller.clientId(), echoDevice,
                    ueAddress, server, devicePorts, serverPorts, request.getQosProfile(), request.getSink(),
                    sinkAccessToken, request.getDuration(), now, now.plusSeconds(request.getDuration()),
                    QosStatus.AVAILABLE, null, subscriptionId);
            try {
                store.create(session);
            } catch (RuntimeException e) {
                // Don't leave a QoS in the network that no session knows of
                endQuietly(subscriptionId);
                throw e;
            }
            log.info("Session {} created: profile {} for {}s", session.id(), session.qosProfile(), session.duration());
            notifier.statusChanged(session);
            return session;
        } finally {
            store.unlockDevice(phoneNumber);
        }
    }

    public QodSession get(UUID id, Caller caller) {
        // Someone else's session is as unknown as one that doesn't exist
        return store.find(id).filter(caller::owns).orElseThrow(CamaraApiException::notFound);
    }

    /** Ends the session in the network first, so a failure there keeps it, then forgets it */
    public void delete(UUID id, Caller caller) {
        QodSession session = get(id, caller);
        if (!session.available()) {
            store.delete(session);
            log.info("Session {} deleted, its QoS had already ended", id);
            return;
        }
        tep.deleteSession(session.subscriptionId());
        store.delete(session);
        log.info("Session {} deleted, its QoS ended in TEP", id);
        notifier.statusChanged(session.terminated(StatusInfo.DELETE_REQUESTED, clock.instant()));
    }

    /** Adds to an AVAILABLE session's duration, up to its profile's maximum */
    public QodSession extend(UUID id, int additionalSeconds, Caller caller) {
        QodSession session = get(id, caller);
        if (!session.available()) {
            throw new CamaraApiException(HttpStatus.CONFLICT, "QUALITY_ON_DEMAND.SESSION_EXTENSION_NOT_ALLOWED",
                    "Extending the session duration is not allowed in the current state (" + session.qosStatus()
                            + "). The session must be in the AVAILABLE state.");
        }
        long requested = (long) session.duration() + additionalSeconds;
        long capped = tep.profile(session.qosProfile()).maxDuration().stream()
                .map(max -> Math.min(requested, max))
                .findFirst()
                .orElse(requested);
        // A maximum lowered since creation doesn't shorten the session
        int duration = (int) Math.min(Integer.MAX_VALUE, Math.max(session.duration(), capped));
        QodSession extended = session.withDuration(duration);
        store.update(extended);
        log.info("Session {} extended to {}s", id, duration);
        return extended;
    }

    public List<QodSession> retrieve(String phoneNumber, Caller caller) {
        return store.findByDevice(phoneNumber).stream()
                .filter(caller::owns)
                .sorted(Comparator.comparing(QodSession::startedAt))
                .toList();
    }

    /** Ends the sessions whose duration is over; run on a schedule by every replica */
    public void expireDue() {
        List<UUID> due;
        try {
            due = store.dueForExpiry(clock.instant());
        } catch (RuntimeException e) {
            log.warn("Looking for expired sessions failed: {}", e.getMessage());
            return;
        }
        for (UUID id : due) {
            try {
                expire(id);
            } catch (RuntimeException e) {
                log.warn("Expiring session {} failed: {}", id, e.getMessage());
            }
        }
    }

    private void expire(UUID id) {
        // Only the replica that takes it off the expiry list ends it
        if (!store.claimExpiry(id)) {
            return;
        }
        QodSession session = store.find(id).filter(QodSession::available).orElse(null);
        if (session == null) {
            return;
        }
        Instant now = clock.instant();
        if (session.expiresAt().isAfter(now)) {
            // Extended since it was found due
            store.scheduleExpiry(id, session.expiresAt());
            return;
        }
        try {
            tep.deleteSession(session.subscriptionId());
        } catch (CamaraApiException e) {
            store.scheduleExpiry(id, now.plus(properties.expiryRetryDelay()));
            log.warn("Ending expired session {} in TEP failed, trying again in {}", id, properties.expiryRetryDelay());
            return;
        }
        QodSession ended = session.terminated(StatusInfo.DURATION_EXPIRED, now);
        store.terminate(ended, properties.terminatedRetention());
        log.info("Session {} expired, its QoS ended in TEP", id);
        notifier.statusChanged(ended);
    }

    /** The network ended the TEP AS session with QoS behind a session */
    public void networkTerminated(String subscriptionId) {
        store.findBySubscription(subscriptionId).filter(QodSession::available).ifPresentOrElse(session -> {
            QodSession ended = session.terminated(StatusInfo.NETWORK_TERMINATED, clock.instant());
            store.terminate(ended, properties.terminatedRetention());
            log.info("Session {} ended by the network (TEP subscription {})", session.id(), subscriptionId);
            notifier.statusChanged(ended);
        }, () -> log.info("TEP ended subscription {}, which no available session has", subscriptionId));
    }

    private void endQuietly(String subscriptionId) {
        try {
            tep.deleteSession(subscriptionId);
        } catch (RuntimeException e) {
            log.warn("Ending TEP subscription {} of a session that couldn't be stored failed", subscriptionId);
        }
    }

    // TEP's AS session takes an IPv4 device address, so the flow's other end must be IPv4 too
    private static String applicationServer(ApplicationServer server) {
        if (server == null || server.getIpv4Address() == null) {
            throw invalidArgument("Only an IPv4 applicationServer is supported.");
        }
        try {
            FlowRules.network(server.getIpv4Address());
        } catch (RuntimeException e) {
            throw invalidArgument("applicationServer.ipv4Address is not an IPv4 address or network.");
        }
        return server.getIpv4Address();
    }

    private static Ports validPorts(PortsSpec spec) {
        Ports ports = Ports.from(spec);
        if (ports.ranges().stream().anyMatch(r -> r.from() > r.to())) {
            throw new CamaraApiException(HttpStatus.BAD_REQUEST, "OUT_OF_RANGE",
                    "Client specified an invalid range: a port range's from is above its to.");
        }
        return ports;
    }

    // The spec only takes a bearer access token for now
    private static String sinkAccessToken(SinkCredential credential) {
        if (credential == null) {
            return null;
        }
        if (!(credential instanceof AccessTokenCredential token)) {
            throw new CamaraApiException(HttpStatus.BAD_REQUEST, "INVALID_CREDENTIAL", "Only Access token is supported");
        }
        if (token.getAccessTokenType() != AccessTokenCredential.AccessTokenTypeEnum.BEARER) {
            throw new CamaraApiException(HttpStatus.BAD_REQUEST, "INVALID_TOKEN", "Only bearer token is supported");
        }
        return token.getAccessToken();
    }

    private static void checkProfile(Profile profile, int duration) {
        if (profile.status() != TepQualityProfile.StatusEnum.ACTIVE) {
            throw new CamaraApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "QUALITY_ON_DEMAND.QOS_PROFILE_NOT_APPLICABLE",
                    "The requested QoS Profile is currently not available for session creation.");
        }
        boolean tooShort = profile.minDuration().isPresent() && duration < profile.minDuration().getAsLong();
        boolean tooLong = profile.maxDuration().isPresent() && duration > profile.maxDuration().getAsLong();
        if (tooShort || tooLong) {
            throw new CamaraApiException(HttpStatus.BAD_REQUEST, "QUALITY_ON_DEMAND.DURATION_OUT_OF_RANGE",
                    "The requested duration is out of the allowed range for the specific QoS profile");
        }
    }

    private static CamaraApiException conflict() {
        return new CamaraApiException(HttpStatus.CONFLICT, "CONFLICT",
                "Conflict with an existing session for the same device.");
    }

    private static CamaraApiException invalidArgument(String detail) {
        return new CamaraApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                "Client specified an invalid argument, request body or query param. " + detail);
    }
}
