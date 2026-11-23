package gr.pnet.camara.qualityondemand.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.CamaraAuthErrorHandler;
import gr.pnet.camara.common.web.CorrelatorFilter;
import gr.pnet.camara.qualityondemand.generated.status.QoSSessionsApiController;
import gr.pnet.camara.qualityondemand.generated.status.QoSSessionsApiDelegate;
import gr.pnet.camara.qualityondemand.generated.status.model.AccessTokenCredential;
import gr.pnet.camara.qualityondemand.generated.status.model.ApplicationServer;
import gr.pnet.camara.qualityondemand.generated.status.model.QosStatus;
import gr.pnet.camara.qualityondemand.generated.status.model.SessionInfo;
import gr.pnet.camara.qualityondemand.security.SecurityConfig;

@WebMvcTest(QoSSessionsApiController.class)
@Import({ SecurityConfig.class, CamaraAuthErrorHandler.class })
class QualityOnDemandWebTest {

    private static final String SESSIONS_URL = "/quality-on-demand/v1/sessions";
    private static final String SESSION_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
    private static final String SESSION_URL = SESSIONS_URL + "/" + SESSION_ID;
    private static final String EXTEND_URL = SESSION_URL + "/extend";
    private static final String RETRIEVE_URL = "/quality-on-demand/v1/retrieve-sessions";

    private static final String DEVICE = "\"device\":{\"phoneNumber\":\"+306912345678\"}";
    private static final String APPLICATION_SERVER = "\"applicationServer\":{\"ipv4Address\":\"198.51.100.0/24\"}";
    private static final String QOS_PROFILE = "\"qosProfile\":\"QOS_E\"";
    private static final String DURATION = "\"duration\":3600";
    private static final String CREATE_BODY = body(DEVICE, APPLICATION_SERVER, QOS_PROFILE, DURATION);

    /** The five operations, each with the one scope the spec gives it */
    enum Operation {
        CREATE_SESSION(SecurityConfig.CREATE_AUTHORITY) {
            MockHttpServletRequestBuilder request() {
                return post(SESSIONS_URL).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY);
            }
        },
        GET_SESSION(SecurityConfig.READ_AUTHORITY) {
            MockHttpServletRequestBuilder request() {
                return get(SESSION_URL);
            }
        },
        DELETE_SESSION(SecurityConfig.DELETE_AUTHORITY) {
            MockHttpServletRequestBuilder request() {
                return delete(SESSION_URL);
            }
        },
        EXTEND_SESSION(SecurityConfig.UPDATE_AUTHORITY) {
            MockHttpServletRequestBuilder request() {
                return post(EXTEND_URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedAdditionalDuration\":1800}");
            }
        },
        RETRIEVE_SESSIONS(SecurityConfig.RETRIEVE_BY_DEVICE_AUTHORITY) {
            MockHttpServletRequestBuilder request() {
                return post(RETRIEVE_URL).contentType(MediaType.APPLICATION_JSON).content(body(DEVICE));
            }
        };

        final String authority;

        Operation(String authority) {
            this.authority = authority;
        }

        abstract MockHttpServletRequestBuilder request();
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QoSSessionsApiDelegate delegate;

    // Only reached for a real Bearer header; jwt() below sets the authentication directly
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static String body(String... fields) {
        return "{" + String.join(",", fields) + "}";
    }

    private static RequestPostProcessor tokenWith(String... authorities) {
        return jwt().authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    private static RequestPostProcessor allScopes() {
        return tokenWith(Arrays.stream(Operation.values()).map(operation -> operation.authority).toArray(String[]::new));
    }

    private static SessionInfo session(QosStatus status) {
        return new SessionInfo(new ApplicationServer().ipv4Address("198.51.100.0/24"), "QOS_E",
                UUID.fromString(SESSION_ID), 3600, status);
    }

    private void stubAllOperations() throws Exception {
        when(delegate.createSession(any(), any())).thenReturn(
                ResponseEntity.status(HttpStatus.CREATED).body(session(QosStatus.REQUESTED)));
        when(delegate.getSession(any(), any())).thenReturn(ResponseEntity.ok(session(QosStatus.AVAILABLE)));
        when(delegate.deleteSession(any(), any())).thenReturn(ResponseEntity.noContent().build());
        when(delegate.extendQosSessionDuration(any(), any(), any())).thenReturn(
                ResponseEntity.ok(session(QosStatus.AVAILABLE)));
        when(delegate.retrieveSessionsByDevice(any(), any())).thenReturn(
                ResponseEntity.ok(List.of(session(QosStatus.AVAILABLE))));
    }

    @Test
    void createsSessionAndEchoesCorrelator() throws Exception {
        when(delegate.createSession(any(), eq("abc-123"))).thenReturn(
                ResponseEntity.status(HttpStatus.CREATED).body(session(QosStatus.REQUESTED)));

        mvc.perform(Operation.CREATE_SESSION.request().with(allScopes()).header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isCreated())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.qosProfile").value("QOS_E"))
                .andExpect(jsonPath("$.duration").value(3600))
                .andExpect(jsonPath("$.qosStatus").value("REQUESTED"));

        verify(delegate).createSession(argThat(request -> "QOS_E".equals(request.getQosProfile())
                && request.getDuration() == 3600
                && "198.51.100.0/24".equals(request.getApplicationServer().getIpv4Address())
                && "+306912345678".equals(request.getDevice().getPhoneNumber())), eq("abc-123"));
    }

    // PortsSpec needs one of its lists, not both: each port spec here gives only one
    @Test
    void portsReachTheDelegate() throws Exception {
        stubAllOperations();

        mvc.perform(post(SESSIONS_URL).with(allScopes()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(DEVICE, APPLICATION_SERVER, QOS_PROFILE, DURATION,
                                "\"devicePorts\":{\"ports\":[5060,5070]}",
                                "\"applicationServerPorts\":{\"ranges\":[{\"from\":5010,\"to\":5020}]}")))
                .andExpect(status().isCreated());

        verify(delegate).createSession(argThat(request -> request.getDevicePorts().getPorts().equals(List.of(5060, 5070))
                && request.getApplicationServerPorts().getRanges().get(0).getFrom() == 5010
                && request.getApplicationServerPorts().getRanges().get(0).getTo() == 5020), any());
    }

    // credentialType picks the subtype, so the delegate gets an AccessTokenCredential with its token
    @Test
    void sinkAndAccessTokenCredentialReachTheDelegate() throws Exception {
        stubAllOperations();

        mvc.perform(post(SESSIONS_URL).with(allScopes()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(DEVICE, APPLICATION_SERVER, QOS_PROFILE, DURATION,
                                "\"sink\":\"https://endpoint.example.com/sink\"",
                                "\"sinkCredential\":{\"credentialType\":\"ACCESSTOKEN\",\"accessToken\":\"secret\","
                                        + "\"accessTokenExpiresUtc\":\"2026-12-31T00:00:00Z\",\"accessTokenType\":\"bearer\"}")))
                .andExpect(status().isCreated());

        verify(delegate).createSession(argThat(request -> "https://endpoint.example.com/sink".equals(String.valueOf(request.getSink()))
                && request.getSinkCredential() instanceof AccessTokenCredential credential
                && "secret".equals(credential.getAccessToken())), any());
    }

    @Test
    void getsSessionAndEchoesCorrelator() throws Exception {
        when(delegate.getSession(eq(UUID.fromString(SESSION_ID)), eq("abc-123")))
                .thenReturn(ResponseEntity.ok(session(QosStatus.AVAILABLE)));

        mvc.perform(Operation.GET_SESSION.request().with(allScopes()).header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.qosStatus").value("AVAILABLE"));
    }

    @Test
    void deletesSession() throws Exception {
        stubAllOperations();

        mvc.perform(Operation.DELETE_SESSION.request().with(allScopes()))
                .andExpect(status().isNoContent());

        verify(delegate).deleteSession(eq(UUID.fromString(SESSION_ID)), any());
    }

    @Test
    void extendsSession() throws Exception {
        stubAllOperations();

        mvc.perform(Operation.EXTEND_SESSION.request().with(allScopes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID));

        verify(delegate).extendQosSessionDuration(eq(UUID.fromString(SESSION_ID)),
                argThat(request -> request.getRequestedAdditionalDuration() == 1800), any());
    }

    @Test
    void retrievesSessionsOfADevice() throws Exception {
        stubAllOperations();

        mvc.perform(Operation.RETRIEVE_SESSIONS.request().with(allScopes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sessionId").value(SESSION_ID));

        verify(delegate).retrieveSessionsByDevice(
                argThat(request -> "+306912345678".equals(request.getDevice().getPhoneNumber())), any());
    }

    // The three-legged shape: the token names the device, so a body without one must get past validation
    @Test
    void retrieveWithoutDeviceReachesTheDelegate() throws Exception {
        stubAllOperations();

        mvc.perform(post(RETRIEVE_URL).with(allScopes()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        verify(delegate).retrieveSessionsByDevice(argThat(request -> request.getDevice() == null), any());
    }

    static Stream<Arguments> operationsAndScopes() {
        return Arrays.stream(Operation.values()).flatMap(operation -> Arrays.stream(Operation.values())
                .map(scopeOf -> Arguments.of(operation, scopeOf)));
    }

    // Each operation opens with its own scope and no other: e.g. a read token can't delete a session
    @ParameterizedTest(name = "{0} with the scope of {1}")
    @MethodSource("operationsAndScopes")
    void eachOperationTakesOnlyItsOwnScope(Operation operation, Operation scopeOf) throws Exception {
        stubAllOperations();

        mvc.perform(operation.request().with(tokenWith(scopeOf.authority)))
                .andExpect(operation == scopeOf ? status().is2xxSuccessful() : status().isForbidden());
    }

    // MVC answers HEAD with the GET handler, so it must need GET's scope, not just any valid token
    @Test
    void headOnASessionNeedsTheReadScope() throws Exception {
        stubAllOperations();

        mvc.perform(head(SESSION_URL).with(tokenWith(SecurityConfig.DELETE_AUTHORITY)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(delegate);

        mvc.perform(head(SESSION_URL).with(tokenWith(SecurityConfig.READ_AUTHORITY)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void missingTokenIsUnauthenticatedAndKeepsCorrelator(Operation operation) throws Exception {
        mvc.perform(operation.request().header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(delegate);
    }

    @Test
    void invalidTokenIsUnauthenticated() throws Exception {
        when(jwtDecoder.decode("not-a-jwt")).thenThrow(new BadJwtException("Malformed token"));

        mvc.perform(Operation.CREATE_SESSION.request().header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(delegate);
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

        mvc.perform(Operation.CREATE_SESSION.request().header(HttpHeaders.AUTHORIZATION, "Bearer a-token")
                        .header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("UNAVAILABLE"));

        verifyNoInteractions(delegate);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void tokenWithoutAnyScopeIsPermissionDenied(Operation operation) throws Exception {
        mvc.perform(operation.request().with(jwt()).header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("insufficient_scope")))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        verifyNoInteractions(delegate);
    }

    @Test
    void specFileNeedsNoToken() throws Exception {
        mvc.perform(get("/quality-on-demand.yaml"))
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
        String link = "http://localhost/.well-known/oauth-protected-resource/quality-on-demand";
        mvc.perform(get("/quality-on-demand/v1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        containsString("resource_metadata=\"" + link + "\"")));

        mvc.perform(get(link))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost/quality-on-demand"))
                .andExpect(jsonPath("$.scopes_supported", containsInAnyOrder(
                        "quality-on-demand:sessions:create", "quality-on-demand:sessions:read",
                        "quality-on-demand:sessions:delete", "quality-on-demand:sessions:update",
                        "quality-on-demand:sessions:retrieve-by-device")))
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
                Arguments.of(CamaraApiException.unnecessaryIdentifier(), 422, "UNNECESSARY_IDENTIFIER"));
    }

    @ParameterizedTest
    @MethodSource("camaraErrors")
    void camaraErrorsRenderAsErrorInfoAndKeepCorrelator(CamaraApiException error, int status, String code)
            throws Exception {
        when(delegate.createSession(any(), any())).thenThrow(error);

        mvc.perform(Operation.CREATE_SESSION.request().with(allScopes()).header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().is(status))
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void unexpectedErrorIsInternal() throws Exception {
        when(delegate.deleteSession(any(), any())).thenThrow(new IllegalStateException("boom"));

        mvc.perform(Operation.DELETE_SESSION.request().with(allScopes()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.message").value("Unknown server error. Typically a server bug."));
    }

    static Stream<Named<String>> invalidCreateBodies() {
        return Stream.of(
                Named.of("no applicationServer", body(DEVICE, QOS_PROFILE, DURATION)),
                Named.of("no qosProfile", body(DEVICE, APPLICATION_SERVER, DURATION)),
                Named.of("no duration", body(DEVICE, APPLICATION_SERVER, QOS_PROFILE)),
                Named.of("duration 0", body(DEVICE, APPLICATION_SERVER, QOS_PROFILE, "\"duration\":0")),
                Named.of("qosProfile too short", body(DEVICE, APPLICATION_SERVER, "\"qosProfile\":\"QE\"", DURATION)),
                Named.of("qosProfile with a space", body(DEVICE, APPLICATION_SERVER, "\"qosProfile\":\"QOS E\"", DURATION)),
                Named.of("sink not https", body(DEVICE, APPLICATION_SERVER, QOS_PROFILE, DURATION,
                        "\"sink\":\"http://endpoint.example.com/sink\"")),
                Named.of("device port over 65535", body(DEVICE, APPLICATION_SERVER, QOS_PROFILE, DURATION,
                        "\"devicePorts\":{\"ports\":[65536]}")),
                Named.of("server port range below 0", body(DEVICE, APPLICATION_SERVER, QOS_PROFILE, DURATION,
                        "\"applicationServerPorts\":{\"ranges\":[{\"from\":-1,\"to\":10}]}")),
                Named.of("unknown credentialType", body(DEVICE, APPLICATION_SERVER, QOS_PROFILE, DURATION,
                        "\"sinkCredential\":{\"credentialType\":\"UNKNOWN\"}")),
                Named.of("phone number not E.164", body("\"device\":{\"phoneNumber\":\"12345\"}", APPLICATION_SERVER,
                        QOS_PROFILE, DURATION)));
    }

    @ParameterizedTest
    @MethodSource("invalidCreateBodies")
    void invalidCreateRequestIsInvalidArgument(String body) throws Exception {
        mvc.perform(post(SESSIONS_URL).with(allScopes()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(delegate);
    }

    static Stream<Named<String>> invalidExtendBodies() {
        return Stream.of(
                Named.of("no requestedAdditionalDuration", "{}"),
                Named.of("requestedAdditionalDuration 0", "{\"requestedAdditionalDuration\":0}"));
    }

    @ParameterizedTest
    @MethodSource("invalidExtendBodies")
    void invalidExtendRequestIsInvalidArgument(String body) throws Exception {
        mvc.perform(post(EXTEND_URL).with(allScopes()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(delegate);
    }

    static Stream<Named<MockHttpServletRequestBuilder>> requestsForANonUuidSession() {
        return Stream.of(
                Named.of("get", get(SESSIONS_URL + "/not-a-uuid")),
                Named.of("delete", delete(SESSIONS_URL + "/not-a-uuid")),
                Named.of("extend", post(SESSIONS_URL + "/not-a-uuid/extend").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedAdditionalDuration\":1800}")));
    }

    // A sessionId is a UUID
    @ParameterizedTest
    @MethodSource("requestsForANonUuidSession")
    void sessionIdThatIsNotAUuidIsInvalidArgument(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(allScopes()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(delegate);
    }

    @Test
    void malformedJsonIsInvalidArgument() throws Exception {
        mvc.perform(post(SESSIONS_URL).with(allScopes()).contentType(MediaType.APPLICATION_JSON).content("{\"device\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void invalidCorrelatorIsRejectedAndNotEchoed() throws Exception {
        mvc.perform(Operation.CREATE_SESSION.request().with(allScopes()).header(CorrelatorFilter.HEADER, "bad value!"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(CorrelatorFilter.HEADER))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(delegate);
    }

    static Stream<Named<MockHttpServletRequestBuilder>> wrongMethods() {
        return Stream.of(
                Named.of("GET sessions", get(SESSIONS_URL)),
                Named.of("PUT sessions/{id}", put(SESSION_URL).contentType(MediaType.APPLICATION_JSON).content("{}")),
                Named.of("GET sessions/{id}/extend", get(EXTEND_URL)),
                Named.of("GET retrieve-sessions", get(RETRIEVE_URL)));
    }

    @ParameterizedTest
    @MethodSource("wrongMethods")
    void wrongMethodIsMethodNotAllowed(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.with(allScopes()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void wrongMediaTypeIsUnsupportedMediaType() throws Exception {
        mvc.perform(post(SESSIONS_URL).with(allScopes()).contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // Inside the API paths, but no operation: denied even with every scope
    @Test
    void unknownPathUnderTheApiIsPermissionDenied() throws Exception {
        mvc.perform(post("/quality-on-demand/v1/nope").with(allScopes()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
    }

    // Outside the API paths, so no token is involved
    @Test
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(post("/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
