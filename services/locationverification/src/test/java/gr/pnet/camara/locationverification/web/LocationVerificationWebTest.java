package gr.pnet.camara.locationverification.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.CamaraAuthErrorHandler;
import gr.pnet.camara.common.web.CorrelatorFilter;
import gr.pnet.camara.locationverification.generated.status.LocationVerificationApiController;
import gr.pnet.camara.locationverification.generated.status.LocationVerificationApiDelegate;
import gr.pnet.camara.locationverification.generated.status.model.Circle;
import gr.pnet.camara.locationverification.generated.status.model.VerificationResult;
import gr.pnet.camara.locationverification.generated.status.model.VerifyLocationResponse;
import gr.pnet.camara.locationverification.security.SecurityConfig;

@WebMvcTest(LocationVerificationApiController.class)
@Import({ SecurityConfig.class, CamaraAuthErrorHandler.class })
class LocationVerificationWebTest {

    private static final String URL = "/location-verification/v3/verify";
    private static final String AREA =
            "\"area\":{\"areaType\":\"CIRCLE\",\"center\":{\"latitude\":37.9838,\"longitude\":23.7275},\"radius\":2000}";
    private static final String VALID_BODY = "{\"device\":{\"phoneNumber\":\"+306912345678\"}," + AREA + "}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LocationVerificationApiDelegate delegate;

    // Only reached for a real Bearer header; jwt() below sets the authentication directly
    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** A valid access token carrying the spec's scope */
    private static RequestPostProcessor scopedToken() {
        return jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.VERIFY_SCOPE));
    }

    @Test
    void returnsVerificationAndEchoesCorrelator() throws Exception {
        when(delegate.verifyLocation(any(), eq("abc-123"))).thenReturn(ResponseEntity.ok(
                new VerifyLocationResponse(VerificationResult.PARTIAL, OffsetDateTime.parse("2026-10-20T10:00:00Z"))
                        .matchRate(74)));

        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(jsonPath("$.verificationResult").value("PARTIAL"))
                .andExpect(jsonPath("$.matchRate").value(74))
                .andExpect(jsonPath("$.lastLocationTime").value("2026-10-20T10:00:00Z"));
    }

    // The spec's TRUE example: no matchRate and no device, not even as null
    @Test
    void trueVerificationSendsNoMatchRate() throws Exception {
        when(delegate.verifyLocation(any(), any())).thenReturn(ResponseEntity.ok(
                new VerifyLocationResponse(VerificationResult.TRUE, OffsetDateTime.parse("2026-10-20T10:00:00Z"))));

        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"verificationResult\":\"TRUE\",\"lastLocationTime\":\"2026-10-20T10:00:00Z\"}",
                        JsonCompareMode.STRICT));
    }

    // areaType picks the subtype, so the delegate gets a Circle with its center and radius
    @Test
    void circleAreaReachesTheDelegateAsCircle() throws Exception {
        when(delegate.verifyLocation(any(), any())).thenReturn(ResponseEntity.ok(
                new VerifyLocationResponse(VerificationResult.TRUE, OffsetDateTime.parse("2026-10-20T10:00:00Z"))));

        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk());

        verify(delegate).verifyLocation(argThat(request -> request.getArea() instanceof Circle circle
                && circle.getCenter().getLatitude() == 37.9838
                && circle.getRadius().compareTo(BigDecimal.valueOf(2000)) == 0), any());
    }

    @Test
    void missingTokenIsUnauthenticatedAndKeepsCorrelator() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verify(delegate, never()).verifyLocation(any(), any());
    }

    @Test
    void invalidTokenIsUnauthenticated() throws Exception {
        when(jwtDecoder.decode("not-a-jwt")).thenThrow(new BadJwtException("Malformed token"));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verify(delegate, never()).verifyLocation(any(), any());
    }

    static Stream<Arguments> issuerFailures() {
        return Stream.of(
                // Wrong AUTH_ISSUER_URI, or Keycloak down when the issuer is first loaded
                Arguments.of(new JwtDecoderInitializationException("Failed to lazily resolve the supplied JwtDecoder instance",
                        new IllegalStateException("The Issuer \"a\" provided in the configuration did not match"))),
                // Keycloak down later, when the signing keys are refetched
                Arguments.of(new JwtException("Couldn't retrieve remote JWK set")));
    }

    // The token can't be checked: that's the service's problem, not the caller's, so not a 401
    @ParameterizedTest
    @MethodSource("issuerFailures")
    void unusableIssuerIsUnavailable(RuntimeException failure) throws Exception {
        when(jwtDecoder.decode("a-token")).thenThrow(failure);

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer a-token")
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"));

        verify(delegate, never()).verifyLocation(any(), any());
    }

    @Test
    void tokenWithoutTheScopeIsPermissionDenied() throws Exception {
        mvc.perform(post(URL).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("insufficient_scope")))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verify(delegate, never()).verifyLocation(any(), any());
    }

    // The scope the junior's copy asked for: the security scheme's name, not a CAMARA scope
    @Test
    void openIdIsNotTheScope() throws Exception {
        mvc.perform(post(URL).with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_location-verification:openId")))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void specFileNeedsNoToken() throws Exception {
        mvc.perform(get("/location-verification.yaml"))
                .andExpect(status().isOk());
    }

    // Calling the service directly, the endpoint without an API's path answers too
    @Test
    void resourceMetadataAnswersWithoutTheApisPath() throws Exception {
        mvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk());
    }

    // A 401 links to this API's own metadata (RFC 9728 3.1), not another's on a shared host, and it must
    // resolve without a token
    @Test
    void resourceMetadataIsLinkedFromA401AndNamesTheScope() throws Exception {
        String link = "http://localhost/.well-known/oauth-protected-resource/location-verification";
        mvc.perform(get("/location-verification/v1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        containsString("resource_metadata=\"" + link + "\"")));

        mvc.perform(get(link))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost/location-verification"))
                .andExpect(jsonPath("$.scopes_supported[0]").value("location-verification:verify"))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
    }

    static Stream<Arguments> camaraErrors() {
        return Stream.of(
                Arguments.of(CamaraApiException.identifierNotFound(), 404, "IDENTIFIER_NOT_FOUND"),
                Arguments.of(CamaraApiException.missingIdentifier(), 422, "MISSING_IDENTIFIER"),
                Arguments.of(CamaraApiException.unsupportedIdentifier(), 422, "UNSUPPORTED_IDENTIFIER"),
                Arguments.of(CamaraApiException.serviceNotApplicable(), 422, "SERVICE_NOT_APPLICABLE"),
                Arguments.of(CamaraApiException.unavailable(new RuntimeException("down")), 503, "UNAVAILABLE"),
                Arguments.of(CamaraApiException.notImplemented(), 501, "NOT_IMPLEMENTED"),
                Arguments.of(CamaraApiException.unauthenticated(), 401, "UNAUTHENTICATED"),
                Arguments.of(CamaraApiException.permissionDenied(), 403, "PERMISSION_DENIED"),
                Arguments.of(CamaraApiException.unnecessaryIdentifier(), 422, "UNNECESSARY_IDENTIFIER"),
                Arguments.of(new CamaraApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "LOCATION_VERIFICATION.UNABLE_TO_LOCATE", "The network is unable to locate the device"),
                        422, "LOCATION_VERIFICATION.UNABLE_TO_LOCATE"),
                Arguments.of(new CamaraApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "LOCATION_VERIFICATION.UNABLE_TO_FULFILL_MAX_AGE",
                        "Unable to provide expected freshness for location"),
                        422, "LOCATION_VERIFICATION.UNABLE_TO_FULFILL_MAX_AGE"));
    }

    @ParameterizedTest
    @MethodSource("camaraErrors")
    void camaraErrorsRenderAsErrorInfoAndKeepCorrelator(CamaraApiException error, int status, String code)
            throws Exception {
        when(delegate.verifyLocation(any(), any())).thenThrow(error);

        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().is(status))
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void unexpectedErrorIsInternal() throws Exception {
        when(delegate.verifyLocation(any(), any())).thenThrow(new IllegalStateException("boom"));

        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.message").value("Unknown server error. Typically a server bug."));
    }

    @Test
    void invalidPhoneNumberIsInvalidArgument() throws Exception {
        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device\":{\"phoneNumber\":\"12345\"}," + AREA + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verify(delegate, never()).verifyLocation(any(), any());
    }

    // area is the one required field: a body without it never reaches the delegate
    @Test
    void missingAreaIsInvalidArgument() throws Exception {
        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device\":{\"phoneNumber\":\"+306912345678\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verify(delegate, never()).verifyLocation(any(), any());
    }

    static Stream<String> invalidAreas() {
        return Stream.of(
                // CIRCLE is the only areaType in v3
                "{\"areaType\":\"POLYGON\",\"boundary\":[]}",
                "{\"areaType\":\"CIRCLE\",\"center\":{\"latitude\":37.9838,\"longitude\":23.7275},\"radius\":0}",
                "{\"areaType\":\"CIRCLE\",\"center\":{\"latitude\":91,\"longitude\":23.7275},\"radius\":2000}",
                "{\"areaType\":\"CIRCLE\",\"radius\":2000}");
    }

    @ParameterizedTest
    @MethodSource("invalidAreas")
    void invalidAreaIsInvalidArgument(String area) throws Exception {
        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device\":{\"phoneNumber\":\"+306912345678\"},\"area\":" + area + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verify(delegate, never()).verifyLocation(any(), any());
    }

    @Test
    void malformedJsonIsInvalidArgument() throws Exception {
        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content("{\"device\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void invalidCorrelatorIsRejectedAndNotEchoed() throws Exception {
        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(CorrelatorFilter.HEADER, "bad value!"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(CorrelatorFilter.HEADER))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verify(delegate, never()).verifyLocation(any(), any());
    }

    @Test
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(get(URL).with(scopedToken()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void wrongMediaTypeIsUnsupportedMediaType() throws Exception {
        mvc.perform(post(URL).with(scopedToken()).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // Outside the API paths, so no token is involved
    @Test
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(post("/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
