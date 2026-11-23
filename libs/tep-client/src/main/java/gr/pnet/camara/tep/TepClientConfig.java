package gr.pnet.camara.tep;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import gr.pnet.camara.common.support.Masking;
import gr.pnet.camara.common.web.CorrelatorFilter;
import gr.pnet.tepclient.AsSessionWithRequiredQoSSubscriptionsApi;
import gr.pnet.tepclient.IndividualAsSessionWithRequiredQoSSubscriptionApi;
import gr.pnet.tepclient.QualityProfilesApi;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.invoker.ApiClient;
import io.micrometer.observation.ObservationRegistry;

@Configuration
@EnableConfigurationProperties(TepProperties.class)
public class TepClientConfig {

    // A logger of its own, so TEP call lines can be turned down without losing the rest
    private static final Logger tepCalls = LoggerFactory.getLogger("gr.pnet.camara.tep.TepCalls");

    @Bean
    public ApiClient tepApiClient(TepProperties properties, ObservationRegistry observationRegistry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        // Keep the generated client's Jackson setup; only add transport settings and interceptors.
        // Outbound auth to TEP, if it ever needs a token, is one more interceptor here.
        RestClient restClient = ApiClient.buildRestClientBuilder()
                .requestFactory(requestFactory)
                .requestInterceptor(correlatorForwarding())
                .requestInterceptor(callLogging())
                // http.client.requests metrics; the uri tag is the template, never the MSISDN
                .observationRegistry(observationRegistry)
                .build();

        ApiClient client = new ApiClient(restClient);
        client.setBasePath(properties.baseUrl().toString());
        return client;
    }

    @Bean
    public SubscribersApi subscribersApi(ApiClient tepApiClient) {
        return new SubscribersApi(tepApiClient);
    }

    @Bean
    public QualityProfilesApi qualityProfilesApi(ApiClient tepApiClient) {
        return new QualityProfilesApi(tepApiClient);
    }

    @Bean
    public AsSessionWithRequiredQoSSubscriptionsApi asSessionsApi(ApiClient tepApiClient) {
        return new AsSessionWithRequiredQoSSubscriptionsApi(tepApiClient);
    }

    @Bean
    public IndividualAsSessionWithRequiredQoSSubscriptionApi asSessionApi(ApiClient tepApiClient) {
        return new IndividualAsSessionWithRequiredQoSSubscriptionApi(tepApiClient);
    }

    private static ClientHttpRequestInterceptor correlatorForwarding() {
        return (request, body, execution) -> {
            String correlator = MDC.get(CorrelatorFilter.MDC_KEY);
            if (correlator != null) {
                request.getHeaders().set(CorrelatorFilter.HEADER, correlator);
            }
            return execution.execute(request, body);
        };
    }

    /**
     * One line per TEP call: method, path with any phone number masked, status and duration. Never the query string or
     * a body. A call that gets no answer (connect or read timeout) is a WARN.
     */
    private static ClientHttpRequestInterceptor callLogging() {
        return (request, body, execution) -> {
            String method = request.getMethod().name();
            String path = Masking.maskAll(request.getURI().getRawPath());
            long start = System.nanoTime();
            try {
                ClientHttpResponse response = execution.execute(request, body);
                long nanos = System.nanoTime() - start;
                int status = response.getStatusCode().value();
                tepCalls.atInfo()
                        .addKeyValue("tep.method", method)
                        .addKeyValue("tep.status_code", status)
                        .addKeyValue("event.duration", nanos)
                        .log("TEP {} {} {} {}ms", method, path, status, TimeUnit.NANOSECONDS.toMillis(nanos));
                return response;
            } catch (IOException e) {
                long nanos = System.nanoTime() - start;
                tepCalls.atWarn()
                        .addKeyValue("tep.method", method)
                        .addKeyValue("error.type", e.getClass().getSimpleName())
                        .addKeyValue("event.duration", nanos)
                        .log("TEP {} {} failed: {} after {}ms", method, path, e.getClass().getSimpleName(),
                                TimeUnit.NANOSECONDS.toMillis(nanos));
                throw e;
            }
        };
    }
}
