package gr.pnet.camara.qualityondemand.delegate;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.AccessTokenSubject;
import gr.pnet.camara.qualityondemand.generated.status.QoSSessionsApiDelegate;
import gr.pnet.camara.qualityondemand.generated.status.model.ApplicationServer;
import gr.pnet.camara.qualityondemand.generated.status.model.CreateSession;
import gr.pnet.camara.qualityondemand.generated.status.model.Device;
import gr.pnet.camara.qualityondemand.generated.status.model.DeviceResponse;
import gr.pnet.camara.qualityondemand.generated.status.model.ExtendSessionDuration;
import gr.pnet.camara.qualityondemand.generated.status.model.RetrieveSessionsInput;
import gr.pnet.camara.qualityondemand.generated.status.model.SessionInfo;
import gr.pnet.camara.qualityondemand.session.Caller;
import gr.pnet.camara.qualityondemand.session.QodSession;
import gr.pnet.camara.qualityondemand.session.SessionService;

@Service
public class QualityOnDemandDelegate implements QoSSessionsApiDelegate {

    private final SessionService sessions;
    private final AccessTokenSubject tokenSubject;

    public QualityOnDemandDelegate(SessionService sessions, AccessTokenSubject tokenSubject) {
        this.sessions = sessions;
        this.tokenSubject = tokenSubject;
    }

    @Override
    public ResponseEntity<SessionInfo> createSession(CreateSession createSession, String correlator) {
        return storeUnavailableAsCamara(() -> {
            ResolvedDevice device = resolveDevice(createSession.getDevice());
            QodSession session = sessions.create(createSession, device.phoneNumber(), device.echoInResponse(),
                    caller());
            URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                    .buildAndExpand(session.id()).toUri();
            return ResponseEntity.created(location).body(toSessionInfo(session));
        });
    }

    @Override
    public ResponseEntity<SessionInfo> getSession(UUID sessionId, String correlator) {
        return storeUnavailableAsCamara(() -> ResponseEntity.ok(toSessionInfo(sessions.get(sessionId, caller()))));
    }

    @Override
    public ResponseEntity<Void> deleteSession(UUID sessionId, String correlator) {
        return storeUnavailableAsCamara(() -> {
            sessions.delete(sessionId, caller());
            return ResponseEntity.noContent().build();
        });
    }

    @Override
    public ResponseEntity<SessionInfo> extendQosSessionDuration(UUID sessionId,
            ExtendSessionDuration extendSessionDuration, String correlator) {
        return storeUnavailableAsCamara(() -> ResponseEntity.ok(toSessionInfo(sessions.extend(sessionId,
                extendSessionDuration.getRequestedAdditionalDuration(), caller()))));
    }

    @Override
    public ResponseEntity<List<SessionInfo>> retrieveSessionsByDevice(RetrieveSessionsInput retrieveSessionsInput,
            String correlator) {
        return storeUnavailableAsCamara(() -> {
            ResolvedDevice device = resolveDevice(retrieveSessionsInput == null ? null
                    : retrieveSessionsInput.getDevice());
            return ResponseEntity.ok(sessions.retrieve(device.phoneNumber(), caller()).stream()
                    .map(QualityOnDemandDelegate::toSessionInfo)
                    .toList());
        });
    }

    // Redis being unreachable is the service being unavailable, not a bug
    private static <T> ResponseEntity<T> storeUnavailableAsCamara(Supplier<ResponseEntity<T>> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            throw CamaraApiException.unavailable(e);
        }
    }

    private Caller caller() {
        return new Caller(tokenSubject.clientId().orElse(null), tokenSubject.phoneNumber().orElse(null));
    }

    /**
     * Single place that decides which subscriber the request is about: the three-legged token's subscriber, or, with a
     * two-legged token, the device in the body.
     */
    private ResolvedDevice resolveDevice(Device device) {
        Optional<String> tokenPhoneNumber = tokenSubject.phoneNumber();
        if (tokenPhoneNumber.isPresent()) {
            // CAMARA: the body must not name a device the token already identifies, even the same one
            if (device != null) {
                throw CamaraApiException.unnecessaryIdentifier();
            }
            return new ResolvedDevice(tokenPhoneNumber.get(), false);
        }
        if (device == null) {
            throw CamaraApiException.missingIdentifier();
        }
        long identifiers = Stream.of(device.getPhoneNumber(), device.getNetworkAccessIdentifier(),
                device.getIpv4Address(), device.getIpv6Address())
                .filter(id -> id != null)
                .count();
        if (identifiers == 0) {
            throw CamaraApiException.missingIdentifier();
        }
        if (device.getPhoneNumber() == null) {
            throw CamaraApiException.unsupportedIdentifier();
        }
        // CAMARA returns the identifier used only when the consumer sent several
        return new ResolvedDevice(device.getPhoneNumber(), identifiers > 1);
    }

    // sinkCredential is never echoed: it carries the consumer's access token
    static SessionInfo toSessionInfo(QodSession session) {
        SessionInfo info = new SessionInfo(new ApplicationServer().ipv4Address(session.applicationServer()),
                session.qosProfile(), session.id(), session.duration(), session.qosStatus())
                .devicePorts(session.devicePorts().toSpec())
                .applicationServerPorts(session.applicationServerPorts().toSpec())
                .sink(session.sink())
                .startedAt(utc(session.startedAt()))
                .expiresAt(utc(session.expiresAt()))
                .statusInfo(session.statusInfo());
        if (session.echoDevice()) {
            info.device(new DeviceResponse().phoneNumber(session.phoneNumber()));
        }
        return info;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private record ResolvedDevice(String phoneNumber, boolean echoInResponse) {
    }
}
