package gr.pnet.camara.roaming.delegate;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.AccessTokenSubject;
import gr.pnet.camara.roaming.generated.status.model.Device;
import gr.pnet.camara.roaming.generated.status.model.DeviceIpv4Addr;
import gr.pnet.camara.roaming.generated.status.model.RoamingStatusRequest;
import gr.pnet.camara.roaming.generated.status.model.RoamingStatusResponse;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepSubscriberStatus;

@ExtendWith(OutputCaptureExtension.class)
class RoamingStatusDelegateTest {

    private static final String MSISDN = "+306912345678";

    private SubscribersApi subscriberApi;
    private AccessTokenSubject tokenSubject;
    private RoamingStatusDelegate delegate;

    @BeforeEach
    void setUp() {
        subscriberApi = mock(SubscribersApi.class);
        // Two-legged unless a test says otherwise: Mockito answers Optional.empty()
        tokenSubject = mock(AccessTokenSubject.class);
        delegate = new RoamingStatusDelegate(subscriberApi, tokenSubject);
    }

    @Test
    void threeLeggedTokenIdentifiesTheDevice() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(atHome());

        RoamingStatusResponse body = delegate.getRoamingStatus(new RoamingStatusRequest(), null).getBody();
        delegate.getRoamingStatus(null, null);

        assertThat(body.getRoaming()).isFalse();
        assertThat(body.getDevice()).isNull();
        verify(subscriberApi, times(2)).getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any());
    }

    @Test
    void threeLeggedTokenWithDeviceInBodyIsUnnecessaryIdentifier() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));

        assertCamaraError(() -> delegate.getRoamingStatus(request(new Device().phoneNumber(MSISDN)), null),
                "UNNECESSARY_IDENTIFIER");
        assertCamaraError(() -> delegate.getRoamingStatus(request(new Device()), null),
                "UNNECESSARY_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void homeNetworkIsNotRoamingAndHasNoCountry() {
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(atHome());

        ResponseEntity<RoamingStatusResponse> response = delegate.getRoamingStatus(
                request(new Device().phoneNumber(MSISDN)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RoamingStatusResponse body = response.getBody();
        assertThat(body.getRoaming()).isFalse();
        assertThat(body.getCountryCode()).isNull();
        assertThat(body.getCountryName()).isNull();
        assertThat(body.getLastStatusTime()).isNotNull();
        assertThat(body.getDevice()).isNull();
        verify(subscriberApi).getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any());
    }

    @Test
    void foreignNetworkIsRoamingWithTheVisitedCountry() {
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(visiting("262", "01"));

        RoamingStatusResponse body = delegate.getRoamingStatus(request(new Device().phoneNumber(MSISDN)), null)
                .getBody();

        assertThat(body.getRoaming()).isTrue();
        assertThat(body.getCountryCode()).isEqualTo(262);
        assertThat(body.getCountryName()).containsExactly("DE");
        assertThat(body.getLastStatusTime()).isNotNull();
    }

    @Test
    void networkWithNoCountryHasAnEmptyCountryName() {
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(visiting("901", "03"));

        RoamingStatusResponse body = delegate.getRoamingStatus(request(new Device().phoneNumber(MSISDN)), null)
                .getBody();

        assertThat(body.getRoaming()).isTrue();
        assertThat(body.getCountryCode()).isEqualTo(901);
        assertThat(body.getCountryName()).isEmpty();
    }

    @Test
    void unknownServingNetworkIsServiceNotApplicable(CapturedOutput output) {
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(new TepSubscriberStatus());

        assertCamaraError(() -> delegate.getRoamingStatus(request(new Device().phoneNumber(MSISDN)), null),
                "SERVICE_NOT_APPLICABLE");
        assertThat(output).contains("SERVICE_NOT_APPLICABLE: TEP can't tell whether the device is roaming (mcc null, roaming null)")
                .doesNotContain("306912345678");
    }

    @Test
    void aTepWithoutHomePlmnsIsServiceNotApplicable() {
        // TEP knows the serving network, but not which networks are home
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any()))
                .thenReturn(new TepSubscriberStatus().mcc("202").mnc("01"));

        assertCamaraError(() -> delegate.getRoamingStatus(request(new Device().phoneNumber(MSISDN)), null),
                "SERVICE_NOT_APPLICABLE");
    }

    @Test
    void deviceIsEchoedWhenSeveralIdentifiersWereSent() {
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(atHome());

        RoamingStatusResponse body = delegate.getRoamingStatus(request(new Device()
                .phoneNumber(MSISDN)
                .ipv6Address("2001:db8::1")), null).getBody();

        assertThat(body.getDevice()).isNotNull();
        assertThat(body.getDevice().getPhoneNumber()).isEqualTo(MSISDN);
        assertThat(body.getDevice().getIpv6Address()).isNull();
    }

    @Test
    void missingBodyOrDeviceIsMissingIdentifier() {
        assertCamaraError(() -> delegate.getRoamingStatus(null, null), "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.getRoamingStatus(new RoamingStatusRequest(), null),
                "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.getRoamingStatus(request(new Device()), null),
                "MISSING_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void identifiersOtherThanPhoneNumberAreUnsupported() {
        assertCamaraError(() -> delegate.getRoamingStatus(
                request(new Device().ipv6Address("2001:db8::1")), null), "UNSUPPORTED_IDENTIFIER");
        assertCamaraError(() -> delegate.getRoamingStatus(
                request(new Device().ipv4Address(new DeviceIpv4Addr().publicAddress("203.0.113.1"))), null),
                "UNSUPPORTED_IDENTIFIER");
        assertCamaraError(() -> delegate.getRoamingStatus(
                request(new Device().networkAccessIdentifier("user@example.org")), null),
                "UNSUPPORTED_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void tepNotFoundIsIdentifierNotFound() {
        when(subscriberApi.getSubscriberStatus(any(TepDevice.class), any())).thenThrow(tepError(HttpStatus.NOT_FOUND));

        assertCamaraError(() -> delegate.getRoamingStatus(request(new Device().phoneNumber(MSISDN)), null),
                "IDENTIFIER_NOT_FOUND");
    }

    @Test
    void tepServerErrorIsUnavailable() {
        when(subscriberApi.getSubscriberStatus(any(TepDevice.class), any())).thenThrow(tepError(HttpStatus.INTERNAL_SERVER_ERROR));

        assertCamaraError(() -> delegate.getRoamingStatus(request(new Device().phoneNumber(MSISDN)), null),
                "UNAVAILABLE");
    }

    @Test
    void tepOtherClientErrorIsUnavailable() {
        when(subscriberApi.getSubscriberStatus(any(TepDevice.class), any())).thenThrow(tepError(HttpStatus.BAD_REQUEST));

        assertCamaraError(() -> delegate.getRoamingStatus(request(new Device().phoneNumber(MSISDN)), null),
                "UNAVAILABLE");
    }

    @Test
    void tepTimeoutIsUnavailable() {
        when(subscriberApi.getSubscriberStatus(any(TepDevice.class), any())).thenThrow(new ResourceAccessException("Read timed out"));

        assertCamaraError(() -> delegate.getRoamingStatus(request(new Device().phoneNumber(MSISDN)), null),
                "UNAVAILABLE");
    }

    private static TepSubscriberStatus atHome() {
        return new TepSubscriberStatus().mcc("202").mnc("01").roaming(false);
    }

    private static TepSubscriberStatus visiting(String mcc, String mnc) {
        return new TepSubscriberStatus().mcc(mcc).mnc(mnc).roaming(true);
    }

    private static RoamingStatusRequest request(Device device) {
        return new RoamingStatusRequest().device(device);
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
