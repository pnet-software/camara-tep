package gr.pnet.camara.qosprofiles.web;

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

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.CamaraAuthErrorHandler;
import gr.pnet.camara.common.web.CorrelatorFilter;
import gr.pnet.camara.qosprofiles.generated.status.QoSProfilesApiController;
import gr.pnet.camara.qosprofiles.generated.status.QoSProfilesApiDelegate;
import gr.pnet.camara.qosprofiles.generated.status.model.Duration;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfile;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfileStatusEnum;
import gr.pnet.camara.qosprofiles.generated.status.model.Rate;
import gr.pnet.camara.qosprofiles.generated.status.model.RateUnitEnum;
import gr.pnet.camara.qosprofiles.generated.status.model.TimeUnitEnum;
import gr.pnet.camara.qosprofiles.security.SecurityConfig;

@WebMvcTest(QoSProfilesApiController.class)
@Import({ SecurityConfig.class, CamaraAuthErrorHandler.class })
class QosProfilesWebTest {

    private static final String RETRIEVE_URL = "/qos-profiles/v1/retrieve-qos-profiles";
    private static final String PROFILE_URL = "/qos-profiles/v1/qos-profiles/voice";
    private static final String VALID_BODY = "{\"device\":{\"phoneNumber\":\"+306912345678\"}}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QoSProfilesApiDelegate delegate;

    // Only reached for a real Bearer header; jwt() below sets the authentication directly
    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** A valid access token carrying the spec's scope */
    private static RequestPostProcessor scopedToken() {
        return jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.READ_SCOPE));
    }

    private static QosProfile voice() {
        return new QosProfile("voice", QosProfileStatusEnum.ACTIVE)
                .description("QoS profile for high-quality interactive voice");
    }

    /** Both operations, without a token */
    static Stream<Named<MockHttpServletRequestBuilder>> operations() {
        return Stream.of(
                Named.of("retrieve-qos-profiles",
                        post(RETRIEVE_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                Named.of("qos-profiles/{name}", get(PROFILE_URL)));
    }

    private void verifyDelegateNotCalled() throws Exception {
        verify(delegate, never()).retrieveQoSProfiles(any(), any());
        verify(delegate, never()).getQosProfile(any(), any());
    }

    @Test
    void returnsProfilesAndEchoesCorrelator() throws Exception {
        when(delegate.retrieveQoSProfiles(any(), eq("abc-123"))).thenReturn(ResponseEntity.ok(List.of(voice())));

        mvc.perform(post(RETRIEVE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("voice"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void returnsProfileByNameAndEchoesCorrelator() throws Exception {
        when(delegate.getQosProfile(eq("voice"), eq("abc-123"))).thenReturn(ResponseEntity.ok(voice()));

        mvc.perform(get(PROFILE_URL).with(scopedToken()).header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(jsonPath("$.name").value("voice"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.description").value("QoS profile for high-quality interactive voice"));
    }

    // Units in the spec's spelling, and unset optional fields left out: none of them is nullable
    @Test
    void profileHasSpecUnitsAndNoUnsetFields() throws Exception {
        when(delegate.getQosProfile(any(), any())).thenReturn(ResponseEntity.ok(
                new QosProfile("voice", QosProfileStatusEnum.ACTIVE)
                        .maxDownstreamRate(new Rate().value(10).unit(RateUnitEnum.MBPS))
                        .packetDelayBudget(new Duration().value(50).unit(TimeUnitEnum.MILLISECONDS))));

        mvc.perform(get(PROFILE_URL).with(scopedToken()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"name\":\"voice\",\"status\":\"ACTIVE\","
                        + "\"maxDownstreamRate\":{\"value\":10,\"unit\":\"Mbps\"},"
                        + "\"packetDelayBudget\":{\"value\":50,\"unit\":\"Milliseconds\"}}", JsonCompareMode.STRICT));
    }

    @Test
    void filtersReachTheDelegate() throws Exception {
        when(delegate.retrieveQoSProfiles(any(), any())).thenReturn(ResponseEntity.ok(List.of(voice())));

        mvc.perform(post(RETRIEVE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"device\":{\"phoneNumber\":\"+306912345678\"},\"name\":\"voice\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        verify(delegate).retrieveQoSProfiles(argThat(request -> "voice".equals(request.getName())
                && request.getStatus() == QosProfileStatusEnum.ACTIVE
                && "+306912345678".equals(request.getDevice().getPhoneNumber())), any());
    }

    // Every filter is optional: an empty body asks for all profiles (and is the three-legged shape)
    @Test
    void bodyWithoutFiltersReachesTheDelegate() throws Exception {
        when(delegate.retrieveQoSProfiles(any(), any())).thenReturn(ResponseEntity.ok(List.of(voice())));

        mvc.perform(post(RETRIEVE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(delegate).retrieveQoSProfiles(argThat(request -> request.getDevice() == null
                && request.getName() == null && request.getStatus() == null), any());
    }

    @ParameterizedTest
    @MethodSource("operations")
    void missingTokenIsUnauthenticatedAndKeepsCorrelator(MockHttpServletRequestBuilder operation) throws Exception {
        mvc.perform(operation.header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyDelegateNotCalled();
    }

    @Test
    void invalidTokenIsUnauthenticated() throws Exception {
        when(jwtDecoder.decode("not-a-jwt")).thenThrow(new BadJwtException("Malformed token"));

        mvc.perform(post(RETRIEVE_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyDelegateNotCalled();
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

        mvc.perform(post(RETRIEVE_URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer a-token")
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"));

        verifyDelegateNotCalled();
    }

    @ParameterizedTest
    @MethodSource("operations")
    void tokenWithoutTheScopeIsPermissionDenied(MockHttpServletRequestBuilder operation) throws Exception {
        mvc.perform(operation.with(jwt()).header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("insufficient_scope")))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verifyDelegateNotCalled();
    }

    // The scopes the first copy asked for, after sim swap's: none of them is in this spec
    @ParameterizedTest
    @ValueSource(strings = { "SCOPE_qos-profiles", "SCOPE_qos-profiles:retrieve-qos-profiles", "SCOPE_qos-profiles:check" })
    void copiedScopesAreNotTheSpecs(String authority) throws Exception {
        mvc.perform(post(RETRIEVE_URL).with(jwt().authorities(new SimpleGrantedAuthority(authority)))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    @Test
    void specFileNeedsNoToken() throws Exception {
        mvc.perform(get("/qos-profiles.yaml"))
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
        String link = "http://localhost/.well-known/oauth-protected-resource/qos-profiles";
        mvc.perform(get("/qos-profiles/v1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        containsString("resource_metadata=\"" + link + "\"")));

        mvc.perform(get(link))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost/qos-profiles"))
                .andExpect(jsonPath("$.scopes_supported[0]").value("qos-profiles:read"))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
    }

    static Stream<Arguments> camaraErrors() {
        return Stream.of(
                Arguments.of(CamaraApiException.notFound(), 404, "NOT_FOUND"),
                Arguments.of(CamaraApiException.identifierNotFound(), 404, "IDENTIFIER_NOT_FOUND"),
                Arguments.of(CamaraApiException.unsupportedIdentifier(), 422, "UNSUPPORTED_IDENTIFIER"),
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
        when(delegate.retrieveQoSProfiles(any(), any())).thenThrow(error);

        mvc.perform(post(RETRIEVE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
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
        when(delegate.getQosProfile(any(), any())).thenThrow(new IllegalStateException("boom"));

        mvc.perform(get(PROFILE_URL).with(scopedToken()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.message").value("Unknown server error. Typically a server bug."));
    }

    static Stream<String> invalidBodies() {
        return Stream.of(
                "{\"device\":{\"phoneNumber\":\"12345\"}}",
                "{\"device\":{\"ipv4Address\":{\"publicAddress\":\"203.0.113.0\",\"publicPort\":65536}}}",
                // A profile name is 3 to 256 of letters, digits, '_', '.' and '-'
                "{\"name\":\"ab\"}",
                "{\"name\":\"voice!\"}",
                "{\"status\":\"UNKNOWN\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidFilterIsInvalidArgument(String body) throws Exception {
        mvc.perform(post(RETRIEVE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyDelegateNotCalled();
    }

    @ParameterizedTest
    @ValueSource(strings = { "ab", "voice!" })
    void invalidProfileNameIsInvalidArgument(String name) throws Exception {
        mvc.perform(get("/qos-profiles/v1/qos-profiles/" + name).with(scopedToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyDelegateNotCalled();
    }

    @Test
    void malformedJsonIsInvalidArgument() throws Exception {
        mvc.perform(post(RETRIEVE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content("{\"device\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void invalidCorrelatorIsRejectedAndNotEchoed() throws Exception {
        mvc.perform(post(RETRIEVE_URL).with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)
                        .header(CorrelatorFilter.HEADER, "bad value!"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(CorrelatorFilter.HEADER))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyDelegateNotCalled();
    }

    static Stream<Named<MockHttpServletRequestBuilder>> wrongMethods() {
        return Stream.of(
                Named.of("GET retrieve-qos-profiles", get(RETRIEVE_URL)),
                Named.of("POST qos-profiles/{name}", post(PROFILE_URL).contentType(MediaType.APPLICATION_JSON).content("{}")));
    }

    @ParameterizedTest
    @MethodSource("wrongMethods")
    void wrongMethodIsMethodNotAllowed(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(scopedToken()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void wrongMediaTypeIsUnsupportedMediaType() throws Exception {
        mvc.perform(post(RETRIEVE_URL).with(scopedToken()).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // Inside the API paths, so the token is checked first; with the scope, an unknown operation is a 404
    @Test
    void unknownOperationIsNotFound() throws Exception {
        mvc.perform(post("/qos-profiles/v1/nope").with(scopedToken()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // Outside the API paths, so no token is involved
    @Test
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(post("/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
