package gr.pnet.camara.simswap.delegate;

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
import gr.pnet.camara.simswap.generated.status.model.CreateCheckSimSwap;
import gr.pnet.camara.simswap.generated.status.model.CreateSimSwapDate;
import gr.pnet.camara.simswap.generated.status.model.SimSwapInfo;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepSwaps;

@ExtendWith(OutputCaptureExtension.class)
class SimSwapDelegateTest {

    private static final String MSISDN = "+306912345678";

    private SubscribersApi subscriberApi;
    private AccessTokenSubject tokenSubject;
    private SimSwapDelegate delegate;

    @BeforeEach
    void setUp() {
        subscriberApi = mock(SubscribersApi.class);
        // Two-legged unless a test says otherwise: Mockito answers Optional.empty()
        tokenSubject = mock(AccessTokenSubject.class);
        delegate = new SimSwapDelegate(subscriberApi, tokenSubject);
    }

    @Test
    void retrieveDateIsWhenTheImsiLastChanged() {
        OffsetDateTime changed = OffsetDateTime.parse("2026-10-20T10:00:00+03:00");
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(simChangedAt(changed));

        ResponseEntity<SimSwapInfo> response = delegate.retrieveSimSwapDate(retrieveDate(MSISDN), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getLatestSimChange()).isEqualTo(changed);
        assertThat(response.getBody().getMonitoredPeriod()).isNull();
        verify(subscriberApi).getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any());
    }

    // The device changing doesn't make a SIM swap
    @Test
    void retrieveDateIgnoresTheImeisv() {
        OffsetDateTime simChanged = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(simChangedAt(simChanged)
                .latestDeviceChange(OffsetDateTime.parse("2026-10-20T10:00:00Z")));

        assertThat(delegate.retrieveSimSwapDate(retrieveDate(MSISDN), null).getBody().getLatestSimChange())
                .isEqualTo(simChanged);
    }

    @Test
    void retrieveDateWithoutAnImsiChangeIsNull() {
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(new TepSwaps().phoneNumber(MSISDN));

        SimSwapInfo body = delegate.retrieveSimSwapDate(retrieveDate(MSISDN), null).getBody();

        assertThat(body.getLatestSimChange()).isNull();
        assertThat(body.getMonitoredPeriod()).isNull();
    }

    // maxAge is in hours: a change 119 hours ago is within 120, one 121 hours ago isn't
    @Test
    void checkIsSwappedOnlyWhenTheImsiChangedWithinMaxAgeHours() {
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(simChangedHoursAgo(119));
        assertThat(delegate.checkSimSwap(check(MSISDN, 120), null).getBody().getSwapped()).isTrue();

        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(simChangedHoursAgo(121));
        assertThat(delegate.checkSimSwap(check(MSISDN, 120), null).getBody().getSwapped()).isFalse();
    }

    // The model defaults maxAge to 240; an explicit null in the body gets the same
    @Test
    void checkWithoutMaxAgeLooksBack240Hours() {
        CreateCheckSimSwap defaulted = new CreateCheckSimSwap().phoneNumber(MSISDN);
        CreateCheckSimSwap explicitNull = check(MSISDN, null);

        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(simChangedHoursAgo(239));
        assertThat(delegate.checkSimSwap(defaulted, null).getBody().getSwapped()).isTrue();
        assertThat(delegate.checkSimSwap(explicitNull, null).getBody().getSwapped()).isTrue();

        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(simChangedHoursAgo(241));
        assertThat(delegate.checkSimSwap(defaulted, null).getBody().getSwapped()).isFalse();
        assertThat(delegate.checkSimSwap(explicitNull, null).getBody().getSwapped()).isFalse();
    }

    @Test
    void checkWithoutAnImsiChangeIsServiceNotApplicable(CapturedOutput output) {
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(new TepSwaps().phoneNumber(MSISDN));

        assertCamaraError(() -> delegate.checkSimSwap(check(MSISDN, 120), null), "SERVICE_NOT_APPLICABLE");
        assertThat(output).contains("SERVICE_NOT_APPLICABLE: TEP has no SIM change date for the subscriber")
                .doesNotContain("306912345678");
    }

    @Test
    void threeLeggedTokenIdentifiesThePhoneNumber() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));
        when(subscriberApi.getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any())).thenReturn(simChangedHoursAgo(1));

        delegate.retrieveSimSwapDate(new CreateSimSwapDate(), null);
        delegate.checkSimSwap(new CreateCheckSimSwap(), null);

        verify(subscriberApi, times(2)).getSwaps(eq(new TepDevice().phoneNumber(MSISDN)), any());
    }

    @Test
    void threeLeggedTokenWithPhoneNumberInBodyIsUnnecessaryIdentifier() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));

        assertCamaraError(() -> delegate.retrieveSimSwapDate(retrieveDate(MSISDN), null),
                "UNNECESSARY_IDENTIFIER");
        assertCamaraError(() -> delegate.checkSimSwap(check(MSISDN, 120), null), "UNNECESSARY_IDENTIFIER");
        verifyNoInteractions(subscriberApi);
    }

    @Test
    void twoLeggedWithoutPhoneNumberIsMissingIdentifier() {
        assertCamaraError(() -> delegate.retrieveSimSwapDate(new CreateSimSwapDate(), null),
                "MISSING_IDENTIFIER");
        assertCamaraError(() -> delegate.checkSimSwap(new CreateCheckSimSwap(), null), "MISSING_IDENTIFIER");
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

        assertCamaraError(() -> delegate.retrieveSimSwapDate(retrieveDate(MSISDN), null), expectedCode);
        assertCamaraError(() -> delegate.checkSimSwap(check(MSISDN, 120), null), expectedCode);
    }

    private static TepSwaps simChangedHoursAgo(int hours) {
        return simChangedAt(OffsetDateTime.now().minusHours(hours));
    }

    private static TepSwaps simChangedAt(OffsetDateTime changed) {
        return new TepSwaps().phoneNumber(MSISDN).imsi("202010123456789").latestSimChange(changed);
    }

    private static CreateSimSwapDate retrieveDate(String phoneNumber) {
        return new CreateSimSwapDate().phoneNumber(phoneNumber);
    }

    private static CreateCheckSimSwap check(String phoneNumber, Integer maxAge) {
        return new CreateCheckSimSwap().phoneNumber(phoneNumber).maxAge(maxAge);
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
