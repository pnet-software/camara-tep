package gr.pnet.camara.deviceswap.delegate;

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
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import gr.pnet.camara.deviceswap.generated.status.model.CreateCheckDeviceSwap;
import gr.pnet.camara.deviceswap.generated.status.model.CreateDeviceSwapDate;
import gr.pnet.camara.deviceswap.generated.status.model.DeviceSwapInfo;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepSwaps;

@ExtendWith(OutputCaptureExtension.class)
class DeviceSwapDelegateTest {

    private static final String MSISDN = "+306912345678";

    private SubscribersApi subscriberApi;
    private AccessTokenSubject tokenSubject;
    private DeviceSwapDelegate delegate;

    @BeforeEach
    void setUp() {
        subscriberApi = mock(SubscribersApi.class);
        // Two-legged unless a test says otherwise: Mockito answers Optional.empty()
        tokenSubject = mock(AccessTokenSubject.class);
        delegate = new DeviceSwapDelegate(subscriberApi, tokenSubject);
    }

    @Test
    void retrieveDateIsWhenTheImeisvLastChanged() {
        OffsetDateTime changed = OffsetDateTime.parse("2026-10-20T10:00:00+03:00");
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(deviceChangedAt(changed));

        ResponseEntity<DeviceSwapInfo> response = delegate.retrieveDeviceSwapDate(retrieveDate(MSISDN), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getLatestDeviceChange()).isEqualTo(changed);
        assertThat(response.getBody().getMonitoredPeriod()).isNull();
        verify(subscriberApi).getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any());
    }

    @Test
    void retrieveDateWithoutAnImeisvChangeIsNull() {
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(new TepSwaps().phoneNumber(MSISDN));

        DeviceSwapInfo body = delegate.retrieveDeviceSwapDate(retrieveDate(MSISDN), null).getBody();

        assertThat(body.getLatestDeviceChange()).isNull();
        assertThat(body.getMonitoredPeriod()).isNull();
    }

    // maxAge is in hours: a change 119 hours ago is within 120, one 121 hours ago isn't
    @Test
    void checkIsSwappedOnlyWhenTheImeisvChangedWithinMaxAgeHours() {
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(deviceChangedHoursAgo(119));
        assertThat(delegate.checkDeviceSwap(check(MSISDN, 120), null).getBody().getSwapped()).isTrue();

        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(deviceChangedHoursAgo(121));
        assertThat(delegate.checkDeviceSwap(check(MSISDN, 120), null).getBody().getSwapped()).isFalse();
    }

    // The model defaults maxAge to 240; an explicit null in the body gets the same
    @Test
    void checkWithoutMaxAgeLooksBack240Hours() {
        CreateCheckDeviceSwap defaulted = new CreateCheckDeviceSwap().phoneNumber(MSISDN);
        CreateCheckDeviceSwap explicitNull = check(MSISDN, null);

        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(deviceChangedHoursAgo(239));
        assertThat(delegate.checkDeviceSwap(defaulted, null).getBody().getSwapped()).isTrue();
        assertThat(delegate.checkDeviceSwap(explicitNull, null).getBody().getSwapped()).isTrue();

        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(deviceChangedHoursAgo(241));
        assertThat(delegate.checkDeviceSwap(defaulted, null).getBody().getSwapped()).isFalse();
        assertThat(delegate.checkDeviceSwap(explicitNull, null).getBody().getSwapped()).isFalse();
    }

    @Test
    void checkWithoutAnImeisvChangeIsServiceNotApplicable(CapturedOutput output) {
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(new TepSwaps().phoneNumber(MSISDN));

        assertCamaraError(() -> delegate.checkDeviceSwap(check(MSISDN, 120), null), "SERVICE_NOT_APPLICABLE");
        assertThat(output).contains("SERVICE_NOT_APPLICABLE: TEP has no device change date for the subscriber")
                .doesNotContain("306912345678");
    }

    @Test
    void threeLeggedTokenIdentifiesThePhoneNumber() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(deviceChangedHoursAgo(1));

        delegate.retrieveDeviceSwapDate(new CreateDeviceSwapDate(), null);
        delegate.checkDeviceSwap(new CreateCheckDeviceSwap(), null);

        verify(subscriberApi, times(2)).getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any());
    }

    @Test
    void threeLeggedTokenWithPhoneNumberInBodyIsUnnecessaryIdentifier() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));

        assertCamaraError(() -> delegate.retrieveDeviceSwapDate(retrieveDate(MSISDN), null),
                "UNNECESSARY_IDENTIFIER");
        assertCamaraError(() -> delegate.checkDeviceSwap(check(MSISDN, 120), null), "UNNECESSARY_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void twoLeggedWithoutPhoneNumberIsMissingIdentifier() {
        assertCamaraError(() -> delegate.retrieveDeviceSwapDate(new CreateDeviceSwapDate(), null),
                "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.checkDeviceSwap(new CreateCheckDeviceSwap(), null), "MISSING_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    static Stream<Arguments> tepFailures() {
        return Stream.of(
                Arguments.of(tepError(HttpStatus.NOT_FOUND), "IDENTIFIER_NOT_FOUND"),
                Arguments.of(tepError(HttpStatus.BAD_REQUEST), "UNAVAILABLE"),
                Arguments.of(tepError(HttpStatus.INTERNAL_SERVER_ERROR), "UNAVAILABLE"),
                Arguments.of(new ResourceAccessException("Read timed out"), "UNAVAILABLE"));
    }

    @ParameterizedTest
    @MethodSource("tepFailures")
    void tepFailuresMapToCamaraErrors(RuntimeException failure, String expectedCode) {
        when(subscriberApi.getSwaps(any(TepDevice.class), any())).thenThrow(failure);

        assertCamaraError(() -> delegate.retrieveDeviceSwapDate(retrieveDate(MSISDN), null), expectedCode);
        assertCamaraError(() -> delegate.checkDeviceSwap(check(MSISDN, 120), null), expectedCode);
    }

    private static TepSwaps deviceChangedHoursAgo(int hours) {
        return deviceChangedAt(OffsetDateTime.now().minusHours(hours));
    }

    private static TepSwaps deviceChangedAt(OffsetDateTime changed) {
        return new TepSwaps().phoneNumber(MSISDN).imeisv("3534900612345601").latestDeviceChange(changed);
    }

    private static CreateDeviceSwapDate retrieveDate(String phoneNumber) {
        return new CreateDeviceSwapDate().phoneNumber(phoneNumber);
    }

    private static CreateCheckDeviceSwap check(String phoneNumber, Integer maxAge) {
        return new CreateCheckDeviceSwap().phoneNumber(phoneNumber).maxAge(maxAge);
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
