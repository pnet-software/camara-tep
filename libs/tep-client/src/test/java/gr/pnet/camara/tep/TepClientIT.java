package gr.pnet.camara.tep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import gr.pnet.camara.common.web.CorrelatorFilter;
import gr.pnet.tepclient.AsSessionWithRequiredQoSSubscriptionsApi;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.invoker.ApiClient;
import gr.pnet.tepclient.model.AsSessionWithQoSSubscription;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepSubscriberLocation;
import gr.pnet.tepclient.model.TepSubscriberStatus;
import gr.pnet.tepclient.model.UserPlaneEvent;
import io.micrometer.observation.ObservationRegistry;

/**
 * Exercises the real TEP client wiring from {@link TepClientConfig} against a local HTTP server.
 */
@ExtendWith(OutputCaptureExtension.class)
class TepClientIT {

    private static final String MSISDN = "+306912345678";
    private static final TepDevice DEVICE = new TepDevice().phoneNumber(MSISDN);

    private HttpServer server;
    private final AtomicReference<HttpExchange> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";
    private volatile long responseDelayMillis;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastRequest.set(exchange);
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(responseDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void postsTheDeviceForTheSubscriberStatusAndForwardsCorrelator(CapturedOutput output) {
        responseBody = """
                {"connectionState":"CONNECTED","mcc":"999","mnc":"50",
                 "pduSessions":[{"pduSessionId":"1","dnn":"internet","sst":"1","pduAddress":"10.45.0.9",
                                 "qosFlows":[{"qfi":1,"fiveQi":9}]}],
                 "unknownField":"ignored"}
                """;
        MDC.put(CorrelatorFilter.MDC_KEY, "abc-123");

        TepSubscriberStatus status = api(Duration.ofSeconds(5)).getSubscriberStatus(DEVICE, TepCorrelator.current());

        HttpExchange request = lastRequest.get();
        assertThat(request.getRequestMethod()).isEqualTo("POST");
        assertThat(request.getRequestURI().getRawPath()).isEqualTo("/tep-api/v1/subscriber/status");
        assertThat(request.getRequestHeaders().getFirst("Content-Type")).startsWith("application/json");
        assertThat(lastRequestBody.get()).isEqualTo("{\"phoneNumber\":\"+306912345678\"}");
        assertThat(request.getRequestHeaders().getFirst(CorrelatorFilter.HEADER)).isEqualTo("abc-123");
        assertThat(status.getConnectionState()).isEqualTo(TepSubscriberStatus.ConnectionStateEnum.CONNECTED);
        assertThat(status.getPduSessions()).singleElement().satisfies(s -> {
            assertThat(s.getDnn()).isEqualTo("internet");
            assertThat(s.getQosFlows()).singleElement().satisfies(f -> assertThat(f.getFiveQi()).isEqualTo(9));
        });
        // The phone number is only in the body, which isn't logged
        assertThat(output).contains("TEP POST /tep-api/v1/subscriber/status 200 ")
                .doesNotContain("306912345678").doesNotContain("3069******78");
    }

    @Test
    void noCorrelatorHeaderWithoutMdcValue() {
        api(Duration.ofSeconds(5)).getSubscriberStatus(DEVICE, TepCorrelator.current());

        assertThat(lastRequest.get().getRequestHeaders().containsKey(CorrelatorFilter.HEADER)).isFalse();
    }

    // TEP declares x-correlator on its operations, so the argument alone sends it
    @Test
    void sendsTheCorrelatorPassedToATepOperation() {
        api(Duration.ofSeconds(5)).getSubscriberStatus(DEVICE, "corr-arg");

        assertThat(lastRequest.get().getRequestHeaders().getFirst(CorrelatorFilter.HEADER)).isEqualTo("corr-arg");
    }

    @Test
    void notFoundSurfacesAsHttpClientErrorNotFound(CapturedOutput output) {
        responseStatus = 404;
        responseBody = "{\"title\":\"Not Found\",\"status\":404}";

        assertThatThrownBy(() -> api(Duration.ofSeconds(5)).getSubscriberStatus(DEVICE, null))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
        assertThat(output).contains("TEP POST /tep-api/v1/subscriber/status 404 ");
    }

    @Test
    void slowTepHitsReadTimeout(CapturedOutput output) {
        responseDelayMillis = 2_000;

        assertThatThrownBy(() -> api(Duration.ofMillis(300)).getSubscriberStatus(DEVICE, null))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(output).contains("WARN").contains("TEP POST /tep-api/v1/subscriber/status failed: ")
                .doesNotContain("306912345678");
    }

    @Test
    void readsSubscriberLocationWithItsCellsCoverage() {
        responseBody = """
                {"cellId":"99950-000000210","center":{"lat":37.9838,"lon":23.7275},"radius":500,
                 "lastUpdated":"2026-11-05T09:40:42Z"}
                """;

        TepSubscriberLocation location = api(Duration.ofSeconds(5)).getSubscriberLocation(DEVICE, null);

        assertThat(lastRequest.get().getRequestMethod()).isEqualTo("POST");
        assertThat(lastRequest.get().getRequestURI().getRawPath()).isEqualTo("/tep-api/v1/subscriber/location");
        assertThat(lastRequestBody.get()).contains("\"phoneNumber\":\"+306912345678\"");
        assertThat(location.getCellId()).isEqualTo("99950-000000210");
        assertThat(location.getCenter().getLat()).isEqualTo(37.9838);
        assertThat(location.getCenter().getLon()).isEqualTo(23.7275);
        assertThat(location.getRadius()).isEqualTo(500.0);
        assertThat(location.getLastUpdated()).isEqualTo(OffsetDateTime.parse("2026-11-05T09:40:42Z"));
    }

    // The 3GPP AsSessionWithQoS API is mounted under the TEP base path. Its spec doesn't declare x-correlator, so the
    // interceptor sends the request's
    @Test
    void createsAnAsSessionWithQoS() {
        MDC.put(CorrelatorFilter.MDC_KEY, "corr-3gpp");
        responseStatus = 201;
        responseBody = """
                {"self":"http://tep/3gpp-as-session-with-qos/v1/scs-1/subscriptions/sub-1",
                 "notificationDestination":"http://camara/notify","qosReference":"voice",
                 "ueIpv4Addr":"10.0.0.1","snssai":{"sst":1,"sd":"000001"},
                 "events":["QOS_GUARANTEED","SESSION_TERMINATION"]}
                """;

        AsSessionWithQoSSubscription created = new AsSessionWithRequiredQoSSubscriptionsApi(
                apiClient(Duration.ofSeconds(5)))
                .createASSessionWithQoSSubscription("scs-1", new AsSessionWithQoSSubscription()
                        .notificationDestination("http://camara/notify")
                        .qosReference("voice")
                        .ueIpv4Addr("10.0.0.1")
                        .addEventsItem(UserPlaneEvent.QOS_GUARANTEED));

        HttpExchange request = lastRequest.get();
        assertThat(request.getRequestMethod()).isEqualTo("POST");
        assertThat(request.getRequestURI().getRawPath())
                .isEqualTo("/tep-api/v1/3gpp-as-session-with-qos/v1/scs-1/subscriptions");
        assertThat(request.getRequestHeaders().getFirst(CorrelatorFilter.HEADER)).isEqualTo("corr-3gpp");
        // UserPlaneEvent is a 3GPP extensible enum: it travels as a plain string
        assertThat(lastRequestBody.get()).contains("\"events\":[\"QOS_GUARANTEED\"]", "\"qosReference\":\"voice\"");
        assertThat(created.getSelf()).endsWith("/subscriptions/sub-1");
        assertThat(created.getSnssai().getSst()).isEqualTo(1);
        assertThat(created.getEvents())
                .containsExactly(UserPlaneEvent.QOS_GUARANTEED, UserPlaneEvent.SESSION_TERMINATION);
    }

    private SubscribersApi api(Duration readTimeout) {
        return new TepClientConfig().subscribersApi(apiClient(readTimeout));
    }

    private ApiClient apiClient(Duration readTimeout) {
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tep-api/v1");
        return new TepClientConfig().tepApiClient(new TepProperties(baseUrl, Duration.ofSeconds(1), readTimeout),
                ObservationRegistry.NOOP);
    }
}
