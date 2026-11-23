package gr.pnet.camara.qualityondemand.tep;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.support.Masking;
import gr.pnet.camara.qualityondemand.config.QodProperties;
import gr.pnet.camara.tep.TepCorrelator;
import gr.pnet.tepclient.AsSessionWithRequiredQoSSubscriptionsApi;
import gr.pnet.tepclient.IndividualAsSessionWithRequiredQoSSubscriptionApi;
import gr.pnet.tepclient.QualityProfilesApi;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.AsSessionWithQoSSubscription;
import gr.pnet.tepclient.model.FlowInfo;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepQualityProfile;
import gr.pnet.tepclient.model.TepQualityProfileDuration;
import gr.pnet.tepclient.model.TepSubscriberStatus;
import gr.pnet.tepclient.model.TepSubscriberStatusPduSession;

/**
 * Everything this service asks TEP, with TEP's failures turned into CAMARA errors: the quality profile, the device's
 * address, and the AS session with QoS that realizes a CAMARA session.
 */
@Component
public class TepQosGateway {

    private static final Logger log = LoggerFactory.getLogger(TepQosGateway.class);
    private static final String IMS_DNN = "ims";
    // TEP passes it to the PCF as the app session's suppFeat, which the Open5GS PCF requires: without it the PCF
    // can't parse the request ("cannot parse HTTP message"). The value TEP is tested with
    static final String SUPPORTED_FEATURES = "12";
    // Enough of TEP's error body to see why it refused, without flooding the log
    private static final int MAX_LOGGED_BODY = 500;

    private final QualityProfilesApi profilesApi;
    private final SubscribersApi subscribersApi;
    private final AsSessionWithRequiredQoSSubscriptionsApi asSessionsApi;
    private final IndividualAsSessionWithRequiredQoSSubscriptionApi asSessionApi;
    private final QodProperties properties;

    public TepQosGateway(QualityProfilesApi profilesApi, SubscribersApi subscribersApi,
            AsSessionWithRequiredQoSSubscriptionsApi asSessionsApi,
            IndividualAsSessionWithRequiredQoSSubscriptionApi asSessionApi, QodProperties properties) {
        this.profilesApi = profilesApi;
        this.subscribersApi = subscribersApi;
        this.asSessionsApi = asSessionsApi;
        this.asSessionApi = asSessionApi;
        this.properties = properties;
    }

    /** A quality profile's status and allowed durations, in seconds; empty bounds when TEP sets none */
    public record Profile(TepQualityProfile.StatusEnum status, OptionalLong minDuration, OptionalLong maxDuration) {
    }

    public Profile profile(String name) {
        TepQualityProfile profile;
        try {
            profile = profilesApi.getQualityProfile(name, TepCorrelator.current());
        } catch (HttpClientErrorException.NotFound e) {
            // The spec has no 404 for an unknown qosProfile on create
            throw new CamaraApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                    "Client specified an invalid argument, request body or query param: unknown qosProfile.");
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }
        return new Profile(profile.getStatus(), seconds(profile.getMinDuration()), seconds(profile.getMaxDuration()));
    }

    private static OptionalLong seconds(TepQualityProfileDuration duration) {
        if (duration == null || duration.getValue() == null || duration.getUnit() == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(ChronoUnit.valueOf(duration.getUnit().name()).getDuration()
                .multipliedBy(duration.getValue()).toSeconds());
    }

    /**
     * The IPv4 address of the device's data session, the one TEP finds its PDU session by: that of its first PDU
     * session to a data network other than IMS.
     */
    public String deviceAddress(String phoneNumber) {
        TepSubscriberStatus status;
        try {
            status = subscribersApi.getSubscriberStatus(new TepDevice().phoneNumber(phoneNumber), TepCorrelator.current());
        } catch (HttpClientErrorException.NotFound e) {
            throw CamaraApiException.identifierNotFound();
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }
        if (status.getConnectionState() == TepSubscriberStatus.ConnectionStateEnum.DEREGISTERED) {
            log.info("No QoS for {}: TEP has it DEREGISTERED", Masking.msisdn(phoneNumber));
            throw CamaraApiException.serviceNotApplicable();
        }
        List<TepSubscriberStatusPduSession> sessions = status.getPduSessions() == null ? List.of()
                : status.getPduSessions().stream().filter(Objects::nonNull).toList();
        return sessions.stream()
                .filter(s -> !IMS_DNN.equalsIgnoreCase(s.getDnn()))
                .map(TepSubscriberStatusPduSession::getPduAddress)
                .filter(address -> address != null && !address.isBlank())
                .findFirst()
                .orElseThrow(() -> {
                    log.info("No QoS for {}: TEP shows no data PDU session with an address (connectionState {}, "
                            + "DNNs {})", Masking.msisdn(phoneNumber), status.getConnectionState(),
                            sessions.stream().map(TepSubscriberStatusPduSession::getDnn).toList());
                    return CamaraApiException.serviceNotApplicable();
                });
    }

    /** Creates the AS session with QoS and answers its subscription id */
    public String createSession(String ueIpv4Addr, List<String> flowDescriptions, String qosReference) {
        FlowInfo flow = new FlowInfo();
        flow.setFlowId(1);
        flow.setFlowDescriptions(flowDescriptions);
        AsSessionWithQoSSubscription subscription = new AsSessionWithQoSSubscription()
                .supportedFeatures(SUPPORTED_FEATURES)
                .ueIpv4Addr(ueIpv4Addr)
                .qosReference(qosReference)
                .notificationDestination(properties.notificationDestination().toString())
                .flowInfo(List.of(flow));
        withoutUnsetLists(subscription);
        AsSessionWithQoSSubscription created;
        try {
            created = asSessionsApi.createASSessionWithQoSSubscription(properties.scsAsId(), subscription);
        } catch (RestClientResponseException e) {
            // TEP refused it for this device, e.g. no PDU session for the address or a profile it can't apply
            if (e.getStatusCode().is4xxClientError()) {
                log.warn("TEP refused the AS session with QoS (qosReference {}, flows {}): {} {}", qosReference,
                        flowDescriptions, e.getStatusCode().value(), abbreviate(e.getResponseBodyAsString()));
                throw CamaraApiException.serviceNotApplicable();
            }
            throw CamaraApiException.unavailable(e);
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }
        String id = subscriptionId(created == null ? null : created.getSelf());
        if (id == null) {
            throw CamaraApiException.unavailable(new IllegalStateException("TEP answered no subscription id"));
        }
        return id;
    }

    /** Ends the AS session with QoS; one TEP no longer has counts as ended */
    public void deleteSession(String subscriptionId) {
        try {
            asSessionApi.deleteIndASSessionWithQoSSubscription(properties.scsAsId(), subscriptionId);
        } catch (HttpClientErrorException.NotFound e) {
            // Already gone
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }
    }

    // The generated model starts every list as [], which is sent; TEP rejects fields it doesn't take
    static void withoutUnsetLists(AsSessionWithQoSSubscription subscription) {
        subscription.setEthFlowInfo(null);
        subscription.setEnEthFlowInfo(null);
        subscription.setAltQoSReferences(null);
        subscription.setAltQosReqs(null);
        subscription.setEvents(null);
    }

    static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        String oneLine = body.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= MAX_LOGGED_BODY ? oneLine : oneLine.substring(0, MAX_LOGGED_BODY) + "...";
    }

    // The id is the last segment of the subscription's URL: .../subscriptions/{id}
    public static String subscriptionId(String url) {
        if (url == null) {
            return null;
        }
        int at = url.lastIndexOf("/subscriptions/");
        String id = at < 0 ? null : url.substring(at + "/subscriptions/".length()).replaceAll("/+$", "");
        return id == null || id.isEmpty() ? null : id;
    }
}
