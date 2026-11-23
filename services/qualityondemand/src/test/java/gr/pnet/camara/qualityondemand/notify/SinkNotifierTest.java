package gr.pnet.camara.qualityondemand.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import gr.pnet.camara.common.web.CorrelatorFilter;
import gr.pnet.camara.qualityondemand.config.QodProperties;
import gr.pnet.camara.qualityondemand.generated.status.model.QosStatus;
import gr.pnet.camara.qualityondemand.generated.status.model.StatusInfo;
import gr.pnet.camara.qualityondemand.session.Ports;
import gr.pnet.camara.qualityondemand.session.QodSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class SinkNotifierTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final AtomicReference<HttpExchange> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int responseStatus = 204;
    private SinkNotifier notifier;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastRequest.set(exchange);
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        QodProperties properties = new QodProperties("camara-qod", URI.create("http://qod:8080/tep-notifications"),
                URI.create("https://api.example.com/"), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(360), Duration.ofSeconds(2));
        // Sent on the calling thread, so each test sees the request when statusChanged returns
        notifier = new SinkNotifier(properties, Runnable::run, json, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void sendsTheQosStatusChangedCloudEvent(CapturedOutput output) {
        QodSession session = session(sink(), "a-token", QosStatus.UNAVAILABLE, StatusInfo.DURATION_EXPIRED);
        MDC.put(CorrelatorFilter.MDC_KEY, "abc-123");

        notifier.statusChanged(session);

        HttpExchange request = lastRequest.get();
        assertThat(request.getRequestMethod()).isEqualTo("POST");
        assertThat(request.getRequestURI().getPath()).isEqualTo("/notify");
        assertThat(request.getRequestHeaders().getFirst("Content-Type")).startsWith("application/cloudevents+json");
        assertThat(request.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer a-token");
        assertThat(request.getRequestHeaders().getFirst(CorrelatorFilter.HEADER)).isEqualTo("abc-123");
        JsonNode event = json.readTree(lastBody.get());
        assertThat(event.get("type").asString()).isEqualTo("org.camaraproject.quality-on-demand.v1.qos-status-changed");
        assertThat(event.get("specversion").asString()).isEqualTo("1.0");
        assertThat(event.get("source").asString())
                .isEqualTo("https://api.example.com/quality-on-demand/v1/sessions/" + session.id());
        assertThat(event.get("time").asString()).isEqualTo("2026-11-06T12:00:00Z");
        assertThat(event.get("id").asString()).isNotBlank();
        assertThat(event.get("data").get("sessionId").asString()).isEqualTo(session.id().toString());
        assertThat(event.get("data").get("qosStatus").asString()).isEqualTo("UNAVAILABLE");
        assertThat(event.get("data").get("statusInfo").asString()).isEqualTo("DURATION_EXPIRED");
        assertThat(output).contains("Notified session " + session.id() + " UNAVAILABLE to sink host 127.0.0.1")
                .doesNotContain("a-token").doesNotContain("306912345678");
        // Sent on this thread here, so the request's correlator must survive the send
        assertThat(MDC.get(CorrelatorFilter.MDC_KEY)).isEqualTo("abc-123");
    }

    @Test
    void availableHasNoStatusInfoAndNoTokenNoAuthorization() {
        notifier.statusChanged(session(sink(), null, QosStatus.AVAILABLE, null));

        assertThat(lastRequest.get().getRequestHeaders().containsKey("Authorization")).isFalse();
        assertThat(lastRequest.get().getRequestHeaders().containsKey(CorrelatorFilter.HEADER)).isFalse();
        JsonNode data = json.readTree(lastBody.get()).get("data");
        assertThat(data.get("qosStatus").asString()).isEqualTo("AVAILABLE");
        assertThat(data.has("statusInfo")).isFalse();
    }

    @Test
    void sessionWithoutASinkIsNotNotified() {
        SinkNotifier failing = new SinkNotifier(new QodProperties("camara-qod", URI.create("http://qod"),
                URI.create("https://api.example.com"), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(360), Duration.ofSeconds(2)), task -> {
                    throw new AssertionError("nothing should be queued");
                }, json, Clock.systemUTC());

        failing.statusChanged(session(null, null, QosStatus.AVAILABLE, null));
    }

    @Test
    void failuresAreLoggedNotThrown(CapturedOutput output) {
        responseStatus = 500;
        notifier.statusChanged(session(sink(), null, QosStatus.AVAILABLE, null));

        server.stop(0);
        notifier.statusChanged(session(sink(), null, QosStatus.AVAILABLE, null));

        assertThat(output).contains("to sink host 127.0.0.1 failed: HTTP 500")
                .contains("to sink host 127.0.0.1 failed: ResourceAccessException");
    }

    private String sink() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/notify";
    }

    private static QodSession session(String sink, String token, QosStatus status, StatusInfo statusInfo) {
        return new QodSession(UUID.randomUUID(), "+306912345678", "acme-backend", false, "10.45.0.9", "1.2.3.4",
                Ports.from(null), Ports.from(null), "QOS_E", sink, token, 60, NOW, NOW.plusSeconds(60), status,
                statusInfo, "sub-1");
    }
}
