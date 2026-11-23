package gr.pnet.camara.locationverification.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
import gr.pnet.camara.locationverification.generated.status.model.AreaType;
import gr.pnet.camara.locationverification.generated.status.model.Circle;
import gr.pnet.camara.locationverification.generated.status.model.Device;
import gr.pnet.camara.locationverification.generated.status.model.DeviceIpv4Addr;
import gr.pnet.camara.locationverification.generated.status.model.Point;
import gr.pnet.camara.locationverification.generated.status.model.VerificationResult;
import gr.pnet.camara.locationverification.generated.status.model.VerifyLocationRequest;
import gr.pnet.camara.locationverification.generated.status.model.VerifyLocationResponse;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepGeoPoint;
import gr.pnet.tepclient.model.TepSubscriberLocation;
import gr.pnet.tepclient.model.TepSubscriberStatus;

@ExtendWith(OutputCaptureExtension.class)
class LocationVerificationDelegateTest {

    private static final String MSISDN = "+306912345678";
    // Athens; the requested area is a 2 km circle around it
    private static final double LAT = 37.9838;
    private static final double LON = 23.7275;

    private SubscribersApi subscriberApi;
    private AccessTokenSubject tokenSubject;
    private LocationVerificationDelegate delegate;

    @BeforeEach
    void setUp() {
        subscriberApi = mock(SubscribersApi.class);
        // Two-legged unless a test says otherwise: Mockito answers Optional.empty()
        tokenSubject = mock(AccessTokenSubject.class);
        delegate = new LocationVerificationDelegate(subscriberApi, tokenSubject);
    }

    @Test
    void cellWithinTheAreaIsTrue() {
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(cell(LAT, LON, 500));

        ResponseEntity<VerifyLocationResponse> response = delegate.verifyLocation(
                request(new Device().phoneNumber(MSISDN)), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        VerifyLocationResponse body = response.getBody();
        assertThat(body.getVerificationResult()).isEqualTo(VerificationResult.TRUE);
        assertThat(body.getMatchRate()).isNull();
        assertThat(body.getLastLocationTime()).isNotNull();
        assertThat(body.getDevice()).isNull();
        verify(subscriberApi, never()).getSubscriberStatus(any(TepDevice.class), any());
    }

    @Test
    void areaWithinALargerCellIsPartial() {
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(cell(LAT, LON, 5000));

        VerifyLocationResponse body = delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)), null)
                .getBody();

        assertThat(body.getVerificationResult()).isEqualTo(VerificationResult.PARTIAL);
        assertThat(body.getMatchRate()).isEqualTo(16);
    }

    @Test
    void cellElsewhereIsFalse() {
        // Thessaloniki
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(cell(40.6401, 22.9444, 500));

        VerifyLocationResponse body = delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)), null)
                .getBody();

        assertThat(body.getVerificationResult()).isEqualTo(VerificationResult.FALSE);
        assertThat(body.getMatchRate()).isNull();
    }

    @Test
    void threeLeggedTokenIdentifiesTheDevice() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(cell(LAT, LON, 500));

        VerifyLocationResponse body = delegate.verifyLocation(request(null), null).getBody();

        assertThat(body.getVerificationResult()).isEqualTo(VerificationResult.TRUE);
        assertThat(body.getDevice()).isNull();
    }

    @Test
    void threeLeggedTokenWithDeviceInBodyIsUnnecessaryIdentifier() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));

        assertCamaraError(() -> delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)), null),
                "UNNECESSARY_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void missingDeviceIsMissingIdentifier() {
        assertCamaraError(() -> delegate.verifyLocation(null, null), "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.verifyLocation(request(null), null), "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.verifyLocation(request(new Device()), null), "MISSING_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void identifiersOtherThanPhoneNumberAreUnsupported() {
        assertCamaraError(() -> delegate.verifyLocation(request(new Device().ipv6Address("2001:db8::1")), null),
                "UNSUPPORTED_IDENTIFIER");
        assertCamaraError(() -> delegate.verifyLocation(
                request(new Device().ipv4Address(new DeviceIpv4Addr().publicAddress("203.0.113.1"))), null),
                "UNSUPPORTED_IDENTIFIER");
        assertCamaraError(() -> delegate.verifyLocation(
                request(new Device().networkAccessIdentifier("user@example.org")), null), "UNSUPPORTED_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void deviceIsEchoedWhenSeveralIdentifiersWereSent() {
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(cell(LAT, LON, 500));

        VerifyLocationResponse body = delegate.verifyLocation(request(new Device()
                .phoneNumber(MSISDN)
                .ipv6Address("2001:db8::1")), null).getBody();

        assertThat(body.getDevice()).isNotNull();
        assertThat(body.getDevice().getPhoneNumber()).isEqualTo(MSISDN);
        assertThat(body.getDevice().getIpv6Address()).isNull();
    }

    @Test
    void lastLocationTimeIsWhenTheUeLastReported() {
        OffsetDateTime reported = OffsetDateTime.parse("2026-11-05T09:40:42Z");
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(cell(LAT, LON, 500).lastUpdated(reported));

        assertThat(delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)), null).getBody()
                .getLastLocationTime()).isEqualTo(reported);
    }

    @Test
    void locationWithinMaxAgeIsUsed() {
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(cellReportedSecondsAgo(60));

        assertThat(delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)).maxAge(120), null)
                .getBody().getVerificationResult()).isEqualTo(VerificationResult.TRUE);
    }

    // maxAge 0 asks for a fresh location, which TEP can't trigger
    @Test
    void locationOlderThanMaxAgeCannotBeMet(CapturedOutput output) {
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(cellReportedSecondsAgo(600));
        assertCamaraError(() -> delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)).maxAge(120), null),
                "LOCATION_VERIFICATION.UNABLE_TO_FULFILL_MAX_AGE");

        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(cellReportedSecondsAgo(60));
        assertCamaraError(() -> delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)).maxAge(0), null),
                "LOCATION_VERIFICATION.UNABLE_TO_FULFILL_MAX_AGE");
        // A second may pass between the report and the check
        assertThat(output).containsPattern("UNABLE_TO_FULFILL_MAX_AGE: the location is 60\\ds old, maxAge 120s")
                .containsPattern("UNABLE_TO_FULFILL_MAX_AGE: the location is 6\\ds old, maxAge 0s")
                .doesNotContain("306912345678");
    }

    // An unknown age can't meet a maxAge, but without one any age is fine: the time is then this call's
    @Test
    void locationWithoutATimestamp(CapturedOutput output) {
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(cell(LAT, LON, 500).lastUpdated(null));

        assertCamaraError(() -> delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)).maxAge(3600), null),
                "LOCATION_VERIFICATION.UNABLE_TO_FULFILL_MAX_AGE");
        assertThat(output).contains("UNABLE_TO_FULFILL_MAX_AGE: TEP gives no location time, maxAge 3600s");
        OffsetDateTime before = OffsetDateTime.now();
        assertThat(delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)), null).getBody()
                .getLastLocationTime()).isAfterOrEqualTo(before);
    }

    @Test
    void negativeMaxAgeCannotBeMet() {
        assertCamaraError(() -> delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)).maxAge(-1), null),
                "LOCATION_VERIFICATION.UNABLE_TO_FULFILL_MAX_AGE");
        verifyNoInteractions(subscriberApi);
    }

    // TEP answers 404 for both; its subscriber status tells them apart
    @Test
    void knownSubscriberTepCannotPlaceIsUnableToLocate(CapturedOutput output) {
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenThrow(tepError(HttpStatus.NOT_FOUND));
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(new TepSubscriberStatus());

        assertCamaraError(() -> delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)), null),
                "LOCATION_VERIFICATION.UNABLE_TO_LOCATE");
        assertThat(output).contains("UNABLE_TO_LOCATE: TEP knows the subscriber but has no location for them")
                .doesNotContain("306912345678");
    }

    @Test
    void unknownSubscriberIsIdentifierNotFound() {
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenThrow(tepError(HttpStatus.NOT_FOUND));
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenThrow(tepError(HttpStatus.NOT_FOUND));

        assertCamaraError(() -> delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)), null),
                "IDENTIFIER_NOT_FOUND");
    }

    @Test
    void tepFailingToConfirmTheSubscriberIsUnavailable() {
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenThrow(tepError(HttpStatus.NOT_FOUND));
        when(subscriberApi.getSubscriberStatus(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenThrow(tepError(HttpStatus.INTERNAL_SERVER_ERROR));

        assertCamaraError(() -> delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)), null),
                "UNAVAILABLE");
    }

    @Test
    void cellWithoutCoverageIsUnableToLocate(CapturedOutput output) {
        // A cell not registered with TEP: no centre or radius
        when(subscriberApi.getSubscriberLocation(eq(new TepDevice().phoneNumber(MSISDN)), any()))
                .thenReturn(new TepSubscriberLocation().cellId("99950-000000210"));

        assertCamaraError(() -> delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)), null),
                "LOCATION_VERIFICATION.UNABLE_TO_LOCATE");
        // Neither the cell nor the subscriber: the line says why, not where
        assertThat(output).contains("UNABLE_TO_LOCATE: TEP's location has no usable cell coverage area")
                .doesNotContain("99950-000000210").doesNotContain("306912345678");
    }

    @Test
    void tepErrorsAndTimeoutsAreUnavailable() {
        for (RuntimeException failure : new RuntimeException[] { tepError(HttpStatus.INTERNAL_SERVER_ERROR),
                tepError(HttpStatus.BAD_REQUEST), new ResourceAccessException("Read timed out") }) {
            // doThrow, since when(...) would call the mock and hit the previous failure
            doThrow(failure).when(subscriberApi).getSubscriberLocation(any(TepDevice.class), any());

            assertCamaraError(() -> delegate.verifyLocation(request(new Device().phoneNumber(MSISDN)), null),
                    "UNAVAILABLE");
        }
    }

    private static VerifyLocationRequest request(Device device) {
        return new VerifyLocationRequest(new Circle(new Point(LAT, LON), BigDecimal.valueOf(2000), AreaType.CIRCLE))
                .device(device);
    }

    // Reported by the UE a minute ago
    private static TepSubscriberLocation cell(double latitude, double longitude, double radius) {
        return new TepSubscriberLocation().cellId("99950-000000210")
                .center(new TepGeoPoint().lat(latitude).lon(longitude))
                .radius(radius)
                .lastUpdated(OffsetDateTime.now().minusSeconds(60));
    }

    private static TepSubscriberLocation cellReportedSecondsAgo(int seconds) {
        return cell(LAT, LON, 500).lastUpdated(OffsetDateTime.now().minusSeconds(seconds));
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
