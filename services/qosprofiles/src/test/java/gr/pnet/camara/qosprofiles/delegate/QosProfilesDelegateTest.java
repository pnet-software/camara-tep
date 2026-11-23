package gr.pnet.camara.qosprofiles.delegate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.AccessTokenSubject;
import gr.pnet.camara.qosprofiles.generated.status.model.Device;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfile;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfileDeviceRequest;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfileStatusEnum;
import gr.pnet.tepclient.QualityProfilesApi;
import gr.pnet.tepclient.model.TepQualityProfile;
import gr.pnet.tepclient.model.TepQualityProfilePage;

class QosProfilesDelegateTest {

    private static final String MSISDN = "+306912345678";

    private QualityProfilesApi qualityProfileApi;
    private AccessTokenSubject tokenSubject;
    private QosProfilesDelegate delegate;

    @BeforeEach
    void setUp() {
        qualityProfileApi = mock(QualityProfilesApi.class);
        // Two-legged unless a test says otherwise: Mockito answers Optional.empty()
        tokenSubject = mock(AccessTokenSubject.class);
        delegate = new QosProfilesDelegate(qualityProfileApi, tokenSubject);
    }

    @Test
    void retrieveReturnsEveryProfile() {
        onePage(profile("voice", TepQualityProfile.StatusEnum.ACTIVE),
                profile("video", TepQualityProfile.StatusEnum.INACTIVE));

        ResponseEntity<List<QosProfile>> response = delegate.retrieveQoSProfiles(new QosProfileDeviceRequest(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(QosProfile::getName).containsExactly("voice", "video");
        verify(qualityProfileApi).listQualityProfiles(any(), eq(QosProfilesDelegate.PAGE_SIZE), eq(0));
    }

    @Test
    void retrieveFetchesEveryPage() {
        List<TepQualityProfile> firstPage = IntStream.range(0, QosProfilesDelegate.PAGE_SIZE)
                .mapToObj(i -> profile("profile-" + i, TepQualityProfile.StatusEnum.ACTIVE))
                .toList();
        long total = QosProfilesDelegate.PAGE_SIZE + 1;
        when(qualityProfileApi.listQualityProfiles(any(), eq(QosProfilesDelegate.PAGE_SIZE), eq(0)))
                .thenReturn(page(total, firstPage));
        when(qualityProfileApi.listQualityProfiles(any(), eq(QosProfilesDelegate.PAGE_SIZE), eq(QosProfilesDelegate.PAGE_SIZE)))
                .thenReturn(page(total, List.of(profile("last", TepQualityProfile.StatusEnum.ACTIVE))));

        List<QosProfile> profiles = delegate.retrieveQoSProfiles(new QosProfileDeviceRequest(), null).getBody();

        assertThat(profiles).hasSize(QosProfilesDelegate.PAGE_SIZE + 1);
        assertThat(profiles.get(profiles.size() - 1).getName()).isEqualTo("last");
    }

    // A total that overstates the items must not loop forever
    @Test
    void retrieveStopsAtAnEmptyPage() {
        when(qualityProfileApi.listQualityProfiles(any(), eq(QosProfilesDelegate.PAGE_SIZE), eq(0)))
                .thenReturn(page(10, List.of(profile("voice", TepQualityProfile.StatusEnum.ACTIVE))));
        when(qualityProfileApi.listQualityProfiles(any(), eq(QosProfilesDelegate.PAGE_SIZE), eq(1))).thenReturn(page(10, List.of()));

        assertThat(delegate.retrieveQoSProfiles(new QosProfileDeviceRequest(), null).getBody()).hasSize(1);
    }

    @Test
    void retrieveFiltersByNameAndStatus() {
        onePage(profile("voice", TepQualityProfile.StatusEnum.ACTIVE),
                profile("video", TepQualityProfile.StatusEnum.ACTIVE),
                profile("legacy", TepQualityProfile.StatusEnum.DEPRECATED));

        assertThat(delegate.retrieveQoSProfiles(new QosProfileDeviceRequest().name("video"), null).getBody())
                .extracting(QosProfile::getName).containsExactly("video");
        assertThat(delegate.retrieveQoSProfiles(
                new QosProfileDeviceRequest().status(QosProfileStatusEnum.ACTIVE), null).getBody())
                .extracting(QosProfile::getName).containsExactly("voice", "video");
        assertThat(delegate.retrieveQoSProfiles(
                new QosProfileDeviceRequest().name("legacy").status(QosProfileStatusEnum.ACTIVE), null).getBody())
                .isEmpty();
    }

    // Every profile is available to every device
    @Test
    void deviceDoesNotNarrowTheProfiles() {
        onePage(profile("voice", TepQualityProfile.StatusEnum.ACTIVE),
                profile("video", TepQualityProfile.StatusEnum.ACTIVE));

        assertThat(delegate.retrieveQoSProfiles(
                new QosProfileDeviceRequest().device(new Device().phoneNumber(MSISDN)), null).getBody())
                .hasSize(2);
        assertThat(delegate.retrieveQoSProfiles(
                new QosProfileDeviceRequest().device(new Device().ipv6Address("2001:db8::1")), null).getBody())
                .hasSize(2);

        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));
        assertThat(delegate.retrieveQoSProfiles(new QosProfileDeviceRequest(), null).getBody()).hasSize(2);
    }

    @Test
    void threeLeggedTokenWithDeviceInBodyIsUnnecessaryIdentifier() {
        when(tokenSubject.phoneNumber()).thenReturn(Optional.of(MSISDN));

        assertCamaraError(() -> delegate.retrieveQoSProfiles(
                new QosProfileDeviceRequest().device(new Device().phoneNumber(MSISDN)), null),
                "UNNECESSARY_IDENTIFIER");
        verifyNoInteractions(qualityProfileApi);
    }

    @Test
    void getReturnsTheNamedProfile() {
        when(qualityProfileApi.getQualityProfile(eq("voice"), any()))
                .thenReturn(profile("voice", TepQualityProfile.StatusEnum.ACTIVE));

        ResponseEntity<QosProfile> response = delegate.getQosProfile("voice", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getName()).isEqualTo("voice");
        assertThat(response.getBody().getStatus()).isEqualTo(QosProfileStatusEnum.ACTIVE);
    }

    @Test
    void unknownProfileIsNotFound() {
        when(qualityProfileApi.getQualityProfile(eq("nope"), any())).thenThrow(tepError(HttpStatus.NOT_FOUND));

        assertCamaraError(() -> delegate.getQosProfile("nope", null), "NOT_FOUND");
    }

    static Stream<Arguments> tepFailures() {
        return Stream.of(
                Arguments.of(tepError(HttpStatus.BAD_REQUEST)),
                Arguments.of(tepError(HttpStatus.INTERNAL_SERVER_ERROR)),
                Arguments.of(new ResourceAccessException("Read timed out")));
    }

    @ParameterizedTest
    @MethodSource("tepFailures")
    void otherTepFailuresAreUnavailable(RuntimeException failure) {
        when(qualityProfileApi.getQualityProfile(anyString(), any())).thenThrow(failure);
        when(qualityProfileApi.listQualityProfiles(any(), any(), any())).thenThrow(failure);

        assertCamaraError(() -> delegate.getQosProfile("voice", null), "UNAVAILABLE");
        assertCamaraError(() -> delegate.retrieveQoSProfiles(new QosProfileDeviceRequest(), null), "UNAVAILABLE");
    }

    private void onePage(TepQualityProfile... profiles) {
        when(qualityProfileApi.listQualityProfiles(any(), eq(QosProfilesDelegate.PAGE_SIZE), eq(0)))
                .thenReturn(page(profiles.length, List.of(profiles)));
    }

    private static TepQualityProfilePage page(long total, List<TepQualityProfile> items) {
        return new TepQualityProfilePage().total(total).limit(QosProfilesDelegate.PAGE_SIZE).items(items);
    }

    private static TepQualityProfile profile(String name, TepQualityProfile.StatusEnum status) {
        return new TepQualityProfile().name(name).status(status);
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
