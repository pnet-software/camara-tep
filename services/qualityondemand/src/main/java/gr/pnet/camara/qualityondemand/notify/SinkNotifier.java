package gr.pnet.camara.qualityondemand.notify;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import gr.pnet.camara.common.security.ClientIdFilter;
import gr.pnet.camara.common.web.CorrelatorFilter;
import gr.pnet.camara.qualityondemand.config.QodProperties;
import gr.pnet.camara.qualityondemand.session.QodSession;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells a session's {@code sink} that its QoS status changed, with the spec's {@code qos-status-changed} CloudEvent.
 * Each notification is sent once, on its own thread, and logged; a failure is not retried.
 */
@Component
public class SinkNotifier {

    static final String TYPE = "org.camaraproject.quality-on-demand.v1.qos-status-changed";
    static final MediaType CLOUDEVENTS_JSON = MediaType.parseMediaType("application/cloudevents+json");

    private static final Logger log = LoggerFactory.getLogger(SinkNotifier.class);

    private final RestClient restClient;
    private final Executor executor;
    private final JsonMapper json;
    private final Clock clock;
    private final String apiRoot;

    public SinkNotifier(QodProperties properties, @Qualifier("sinkNotificationExecutor") Executor executor,
            JsonMapper json, Clock clock) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.sinkTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.sinkTimeout());
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.executor = executor;
        this.json = json;
        this.clock = clock;
        this.apiRoot = properties.apiRoot().toString().replaceAll("/+$", "");
    }

    /** Queues the notification of the session's current status; nothing when it has no sink */
    public void statusChanged(QodSession session) {
        if (session.sink() == null) {
            return;
        }
        String body = json.writeValueAsString(event(session));
        // The request's correlator, if any, as this runs on another thread
        String correlator = MDC.get(CorrelatorFilter.MDC_KEY);
        executor.execute(() -> send(session, body, correlator));
    }

    private Map<String, Object> event(QodSession session) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.id().toString());
        data.put("qosStatus", session.qosStatus().getValue());
        if (session.statusInfo() != null) {
            data.put("statusInfo", session.statusInfo().getValue());
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("source", apiRoot + "/quality-on-demand/v1/sessions/" + session.id());
        event.put("type", TYPE);
        event.put("specversion", "1.0");
        event.put("datacontenttype", "application/json");
        event.put("time", clock.instant().toString());
        event.put("data", data);
        return event;
    }

    private void send(QodSession session, String body, String correlator) {
        // This thread's log lines carry the request's correlator and the session's client, as the request's did
        String previousCorrelator = MDC.get(CorrelatorFilter.MDC_KEY);
        String previousClientId = MDC.get(ClientIdFilter.MDC_KEY);
        put(CorrelatorFilter.MDC_KEY, correlator);
        put(ClientIdFilter.MDC_KEY, session.clientId());
        try {
            post(session, body, correlator);
        } finally {
            put(CorrelatorFilter.MDC_KEY, previousCorrelator);
            put(ClientIdFilter.MDC_KEY, previousClientId);
        }
    }

    private void post(QodSession session, String body, String correlator) {
        try {
            restClient.post()
                    .uri(URI.create(session.sink()))
                    .contentType(CLOUDEVENTS_JSON)
                    .headers(headers -> {
                        if (session.sinkAccessToken() != null) {
                            headers.setBearerAuth(session.sinkAccessToken());
                        }
                        if (correlator != null) {
                            headers.set(CorrelatorFilter.HEADER, correlator);
                        }
                    })
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Notified session {} {} to sink host {}", session.id(), session.qosStatus(), host(session.sink()));
        } catch (RestClientException | IllegalArgumentException e) {
            // The sink's host and the HTTP status or error type only: an exception's message can carry the whole URL,
            // whose path or query could hold secrets, and the token never appears
            String reason = e instanceof RestClientResponseException response
                    ? "HTTP " + response.getStatusCode().value()
                    : e.getClass().getSimpleName();
            log.warn("Notification of session {} to sink host {} failed: {}", session.id(), host(session.sink()),
                    reason);
        }
    }

    private static void put(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    private static String host(String sink) {
        try {
            return URI.create(sink).getHost();
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }
}
