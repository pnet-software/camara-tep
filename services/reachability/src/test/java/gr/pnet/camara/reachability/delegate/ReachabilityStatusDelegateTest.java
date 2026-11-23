package gr.pnet.camara.reachability.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.AccessTokenSubject;
import gr.pnet.camara.reachability.generated.status.model.ConnectivityType;
import gr.pnet.camara.reachability.generated.status.model.Device;
import gr.pnet.camara.reachability.generated.status.model.DeviceIpv4Addr;
import gr.pnet.camara.reachability.generated.status.model.ReachabilityStatusResponse;
import gr.pnet.camara.reachability.generated.status.model.RequestReachabilityStatus;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepSubscriberStatus;
import gr.pnet.tepclient.model.TepSubscriberStatusPduSession;

class ReachabilityStatusDelegateTest {

    private static final String MSISDN = "+306912345678";

    private SubscribersApi subscriberApi;
    private AccessTokenSubject tokenSubject;
    private ReachabilityStatusDelegate delegate;

    @BeforeEach
    void setUp() {
        subscriberApi = mock(SubscribersApi.class);
        // Two-legged unless a test says otherwise: Mockito answers Optional.empty()
        tokenSubject = mock(AccessTokenSubject.class);
        delegate = new ReachabilityStatusDelegate(subscriberApi, tokenSubject);
    }

    @Test
    void threeLeggedTokenIdentifiesTheDevice() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(withSessions("internet"));

        ReachabilityStatusResponse body = delegate.getReachabilityStatus(new RequestReachabilityStatus(), null)
                .getBody();
        delegate.getReachabilityStatus(null, null);

        assertThat(body.getReachable()).isTrue();
        assertThat(body.getDevice()).isNull();
        verify(subscriberApi, times(2)).getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any());
    }

    @Test
    void threeLeggedTokenWithDeviceInBodyIsUnnecessaryIdentifier() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));

        assertCamaraError(() -> delegate.getReachabilityStatus(request(new Device().phoneNumber(MSISDN)), null),
                "UNNECESSARY_IDENTIFIER");
        assertCamaraError(() -> delegate.getReachabilityStatus(request(new Device()), null),
                "UNNECESSARY_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void reachableDeviceIsReportedWithoutEchoingDevice() {
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(withSessions("internet", "ims"));

        ResponseEntity<ReachabilityStatusResponse> response = delegate.getReachabilityStatus(
                request(new Device().phoneNumber(MSISDN)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReachabilityStatusResponse body = response.getBody();
        assertThat(body.getReachable()).isTrue();
        assertThat(body.getConnectivity()).containsExactly(ConnectivityType.DATA, ConnectivityType.SMS);
        assertThat(body.getLastStatusTime()).isNotNull();
        assertThat(body.getDevice()).isNull();
        verify(subscriberApi).getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any());
    }

    @Test
    void unreachableDeviceIsReportedAsNotReachable() {
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(new TepSubscriberStatus());

        ReachabilityStatusResponse body = delegate.getReachabilityStatus(
                request(new Device().phoneNumber(MSISDN)), null).getBody();

        assertThat(body.getReachable()).isFalse();
        assertThat(body.getConnectivity()).isEmpty();
    }

    @Test
    void deviceIsEchoedWhenSeveralIdentifiersWereSent() {
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(new TepSubscriberStatus());

        ReachabilityStatusResponse body = delegate.getReachabilityStatus(request(new Device()
                .phoneNumber(MSISDN)
                .ipv6Address("2001:db8::1")), null).getBody();

        assertThat(body.getDevice()).isNotNull();
        assertThat(body.getDevice().getPhoneNumber()).isEqualTo(MSISDN);
        assertThat(body.getDevice().getIpv6Address()).isNull();
    }

    @Test
    void missingBodyOrDeviceIsMissingIdentifier() {
        assertCamaraError(() -> delegate.getReachabilityStatus(null, null), "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.getReachabilityStatus(new RequestReachabilityStatus(), null),
                "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.getReachabilityStatus(request(new Device()), null),
                "MISSING_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void identifiersOtherThanPhoneNumberAreUnsupported() {
        assertCamaraError(() -> delegate.getReachabilityStatus(
                request(new Device().ipv6Address("2001:db8::1")), null), "UNSUPPORTED_IDENTIFIER");
        assertCamaraError(() -> delegate.getReachabilityStatus(
                request(new Device().ipv4Address(new DeviceIpv4Addr().publicAddress("203.0.113.1"))), null),
                "UNSUPPORTED_IDENTIFIER");
        assertCamaraError(() -> delegate.getReachabilityStatus(
                request(new Device().networkAccessIdentifier("user@example.org")), null),
                "UNSUPPORTED_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void tepNotFoundIsIdentifierNotFound() {
        when(subscriberApi.getSubscriberStatus(any(TepDevice.class), any())).thenThrow(tepError(HttpStatus.NOT_FOUND));

        assertCamaraError(() -> delegate.getReachabilityStatus(request(new Device().phoneNumber(MSISDN)), null),
                "IDENTIFIER_NOT_FOUND");
    }

    @Test
    void tepServerErrorIsUnavailable() {
        when(subscriberApi.getSubscriberStatus(any(TepDevice.class), any())).thenThrow(tepError(HttpStatus.INTERNAL_SERVER_ERROR));

        assertCamaraError(() -> delegate.getReachabilityStatus(request(new Device().phoneNumber(MSISDN)), null),
                "UNAVAILABLE");
    }

    @Test
    void tepOtherClientErrorIsUnavailable() {
        when(subscriberApi.getSubscriberStatus(any(TepDevice.class), any())).thenThrow(tepError(HttpStatus.BAD_REQUEST));

        assertCamaraError(() -> delegate.getReachabilityStatus(request(new Device().phoneNumber(MSISDN)), null),
                "UNAVAILABLE");
    }

    @Test
    void tepTimeoutIsUnavailable() {
        when(subscriberApi.getSubscriberStatus(any(TepDevice.class), any())).thenThrow(new ResourceAccessException("Read timed out"));

        assertCamaraError(() -> delegate.getReachabilityStatus(request(new Device().phoneNumber(MSISDN)), null),
                "UNAVAILABLE");
    }

    private static TepSubscriberStatus withSessions(String... dnns) {
        TepSubscriberStatus status = new TepSubscriberStatus()
                .connectionState(TepSubscriberStatus.ConnectionStateEnum.CONNECTED);
        for (String dnn : dnns) {
            status.addPduSessionsItem(new TepSubscriberStatusPduSession().dnn(dnn));
        }
        return status;
    }

    private static RequestReachabilityStatus request(Device device) {
        return new RequestReachabilityStatus().device(device);
    }

    private static RuntimeException tepError(HttpStatus status) {
        HttpStatusCode code = HttpStatusCode.valueOf(status.value());
        if (status.is4xxClientError()) {
            return HttpClientErrorException.create(code, status.getReasonPhrase(), new HttpHeaders(), new byte[0],
                    StandardCharsets.UTF_8);
        }
        return HttpServerErrorException.create(code, status.getReasonPhrase(), new HttpHeaders(), new byte[0],
                StandardCharsets.UTF_8);
    }

    private static void assertCamaraError(Runnable call, String expectedCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CamaraApiException.class)
                .extracting(e -> ((CamaraApiException) e).getCode())
                .isEqualTo(expectedCode);
    }
}
