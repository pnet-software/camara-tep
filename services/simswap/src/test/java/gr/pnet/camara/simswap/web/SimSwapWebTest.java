package gr.pnet.camara.simswap.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
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

import java.time.OffsetDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
import gr.pnet.camara.simswap.generated.status.CheckSimSwapApiController;
import gr.pnet.camara.simswap.generated.status.CheckSimSwapApiDelegate;
import gr.pnet.camara.simswap.generated.status.RetrieveSimSwapDateApiController;
import gr.pnet.camara.simswap.generated.status.RetrieveSimSwapDateApiDelegate;
import gr.pnet.camara.simswap.generated.status.model.CheckSimSwapInfo;
import gr.pnet.camara.simswap.generated.status.model.SimSwapInfo;
import gr.pnet.camara.simswap.security.SecurityConfig;

@WebMvcTest({ RetrieveSimSwapDateApiController.class, CheckSimSwapApiController.class })
@Import({ SecurityConfig.class, CamaraAuthErrorHandler.class })
class SimSwapWebTest {

    private static final String RETRIEVE_DATE_URL = "/sim-swap/v2/retrieve-date";
    private static final String CHECK_URL = "/sim-swap/v2/check";
    // Valid for both operations: check's maxAge is optional
    private static final String VALID_BODY = "{\"phoneNumber\":\"+306912345678\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RetrieveSimSwapDateApiDelegate retrieveDate;

    @MockitoBean
    private CheckSimSwapApiDelegate check;

    // Only reached for a real Bearer header; jwt() below sets the authentication directly
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor tokenWith(String authority) {
        return jwt().authorities(new SimpleGrantedAuthority(authority));
    }

    /** A valid access token carrying the API-wide scope, which covers both operations */
    private static RequestPostProcessor scopedToken() {
        return tokenWith(SecurityConfig.SIM_SWAP_AUTHORITY);
    }

    @Test
    void returnsSwapDateAndEchoesCorrelator() throws Exception {
        when(retrieveDate.retrieveSimSwapDate(any(), eq("abc-123"))).thenReturn(ResponseEntity.ok(
                new SimSwapInfo(OffsetDateTime.parse("2026-10-20T10:00:00Z"))));

        mvc.perform(post(RETRIEVE_DATE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY).header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(jsonPath("$.latestSimChange").value("2026-10-20T10:00:00Z"));
    }

    // latestSimChange is required but nullable: null means no swap within the period the operator keeps,
    // so it must be written out, not dropped
    @Test
    void unknownSwapDateIsWrittenAsNull() throws Exception {
        when(retrieveDate.retrieveSimSwapDate(any(), any())).thenReturn(ResponseEntity.ok(
                new SimSwapInfo(null).monitoredPeriod(120)));

        mvc.perform(post(RETRIEVE_DATE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestSimChange").value(nullValue()))
                .andExpect(jsonPath("$.monitoredPeriod").value(120));
    }

    // monitoredPeriod is optional but not nullable, so it is left out rather than sent as null
    @Test
    void swapDateWithoutMonitoredPeriodHasNoMonitoredPeriod() throws Exception {
        when(retrieveDate.retrieveSimSwapDate(any(), any())).thenReturn(ResponseEntity.ok(
                new SimSwapInfo(OffsetDateTime.parse("2026-10-20T10:00:00Z"))));

        mvc.perform(post(RETRIEVE_DATE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"latestSimChange\":\"2026-10-20T10:00:00Z\"}", JsonCompareMode.STRICT));
    }

    @Test
    void returnsCheckResultAndEchoesCorrelator() throws Exception {
        when(check.checkSimSwap(any(), eq("abc-123"))).thenReturn(ResponseEntity.ok(new CheckSimSwapInfo(true)));

        mvc.perform(post(CHECK_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+306912345678\",\"maxAge\":120}")
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(jsonPath("$.swapped").value(true));

        verify(check).checkSimSwap(argThat(request -> request.getMaxAge() == 120), eq("abc-123"));
    }

    // maxAge defaults to 240 hours, so the delegate always gets one
    @Test
    void checkWithoutMaxAgeReachesTheDelegateWith240() throws Exception {
        when(check.checkSimSwap(any(), any())).thenReturn(ResponseEntity.ok(new CheckSimSwapInfo(false)));

        mvc.perform(post(CHECK_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk());

        verify(check).checkSimSwap(argThat(request -> request.getMaxAge() == 240), any());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 2401 })
    void maxAgeOutsideTheSpecRangeIsInvalidArgument(int maxAge) throws Exception {
        mvc.perform(post(CHECK_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+306912345678\",\"maxAge\":" + maxAge + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verify(check, never()).checkSimSwap(any(), any());
    }

    static Stream<Arguments> scopes() {
        return Stream.of(
                Arguments.of(SecurityConfig.SIM_SWAP_AUTHORITY, RETRIEVE_DATE_URL, 200),
                Arguments.of(SecurityConfig.SIM_SWAP_AUTHORITY, CHECK_URL, 200),
                Arguments.of(SecurityConfig.RETRIEVE_DATE_AUTHORITY, RETRIEVE_DATE_URL, 200),
                Arguments.of(SecurityConfig.RETRIEVE_DATE_AUTHORITY, CHECK_URL, 403),
                Arguments.of(SecurityConfig.CHECK_AUTHORITY, CHECK_URL, 200),
                Arguments.of(SecurityConfig.CHECK_AUTHORITY, RETRIEVE_DATE_URL, 403));
    }

    // The spec lets each operation in with sim-swap or with its own scope, but not with the other one's
    @ParameterizedTest
    @MethodSource("scopes")
    void eachOperationTakesTheApiScopeOrItsOwn(String authority, String url, int status) throws Exception {
        when(retrieveDate.retrieveSimSwapDate(any(), any())).thenReturn(ResponseEntity.ok(
                new SimSwapInfo(OffsetDateTime.parse("2026-10-20T10:00:00Z"))));
        when(check.checkSimSwap(any(), any())).thenReturn(ResponseEntity.ok(new CheckSimSwapInfo(false)));

        mvc.perform(post(url).with(tokenWith(authority)).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().is(status));
    }

    @ParameterizedTest
    @ValueSource(strings = { RETRIEVE_DATE_URL, CHECK_URL })
    void missingTokenIsUnauthenticatedAndKeepsCorrelator(String url) throws Exception {
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verify(retrieveDate, never()).retrieveSimSwapDate(any(), any());
        verify(check, never()).checkSimSwap(any(), any());
    }

    @Test
    void invalidTokenIsUnauthenticated() throws Exception {
        when(jwtDecoder.decode("not-a-jwt")).thenThrow(new BadJwtException("Malformed token"));

        mvc.perform(post(RETRIEVE_DATE_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verify(retrieveDate, never()).retrieveSimSwapDate(any(), any());
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

        mvc.perform(post(RETRIEVE_DATE_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer a-token")
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"));

        verify(retrieveDate, never()).retrieveSimSwapDate(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = { RETRIEVE_DATE_URL, CHECK_URL })
    void tokenWithoutAnyScopeIsPermissionDenied(String url) throws Exception {
        mvc.perform(post(url).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("insufficient_scope")))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verify(retrieveDate, never()).retrieveSimSwapDate(any(), any());
        verify(check, never()).checkSimSwap(any(), any());
    }

    @Test
    void specFileNeedsNoToken() throws Exception {
        mvc.perform(get("/sim-swap.yaml"))
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
        String link = "http://localhost/.well-known/oauth-protected-resource/sim-swap";
        mvc.perform(get("/sim-swap/v1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        containsString("resource_metadata=\"" + link + "\"")));

        mvc.perform(get(link))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost/sim-swap"))
                .andExpect(jsonPath("$.scopes_supported",
                        containsInAnyOrder("sim-swap", "sim-swap:retrieve-date", "sim-swap:check")))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
    }

    static Stream<Arguments> camaraErrors() {
        return Stream.of(
                Arguments.of(CamaraApiException.identifierNotFound(), 404, "IDENTIFIER_NOT_FOUND"),
                Arguments.of(CamaraApiException.missingIdentifier(), 422, "MISSING_IDENTIFIER"),
                Arguments.of(CamaraApiException.serviceNotApplicable(), 422, "SERVICE_NOT_APPLICABLE"),
                Arguments.of(CamaraApiException.unavailable(new RuntimeException("down")), 503, "UNAVAILABLE"),
                Arguments.of(CamaraApiException.notImplemented(), 501, "NOT_IMPLEMENTED"),
                Arguments.of(CamaraApiException.unauthenticated(), 401, "UNAUTHENTICATED"),
                Arguments.of(CamaraApiException.permissionDenied(), 403, "PERMISSION_DENIED"),
                Arguments.of(CamaraApiException.unnecessaryIdentifier(), 422, "UNNECESSARY_IDENTIFIER"));
    }

    @ParameterizedTest
    @MethodSource("camaraErrors")
    void camaraErrorsRenderAsErrorInfoAndKeepCorrelator(CamaraApiException error, int status, String code)
            throws Exception {
        when(retrieveDate.retrieveSimSwapDate(any(), any())).thenThrow(error);

        mvc.perform(post(RETRIEVE_DATE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY).header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().is(status))
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void unexpectedErrorIsInternal() throws Exception {
        when(check.checkSimSwap(any(), any())).thenThrow(new IllegalStateException("boom"));

        mvc.perform(post(CHECK_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.message").value("Unknown server error. Typically a server bug."));
    }

    @ParameterizedTest
    @ValueSource(strings = { RETRIEVE_DATE_URL, CHECK_URL })
    void invalidPhoneNumberIsInvalidArgument(String url) throws Exception {
        mvc.perform(post(url).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verify(retrieveDate, never()).retrieveSimSwapDate(any(), any());
        verify(check, never()).checkSimSwap(any(), any());
    }

    @Test
    void malformedJsonIsInvalidArgument() throws Exception {
        mvc.perform(post(RETRIEVE_DATE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void invalidCorrelatorIsRejectedAndNotEchoed() throws Exception {
        mvc.perform(post(RETRIEVE_DATE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY).header(CorrelatorFilter.HEADER, "bad value!"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(CorrelatorFilter.HEADER))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verify(retrieveDate, never()).retrieveSimSwapDate(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = { RETRIEVE_DATE_URL, CHECK_URL })
    void wrongMethodIsMethodNotAllowed(String url) throws Exception {
        mvc.perform(get(url).with(scopedToken()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void wrongMediaTypeIsUnsupportedMediaType() throws Exception {
        mvc.perform(post(RETRIEVE_DATE_URL).with(scopedToken()).contentType(MediaType.TEXT_PLAIN).content("hello"))
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
