package gr.pnet.camara.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import gr.pnet.camara.common.web.CorrelatorFilter;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The shared error handling and correlator filter, exercised through a stand-in controller the way every
 * generated CAMARA controller uses them.
 */
class CamaraErrorHandlingTest {

    private final TestController controller = new TestController();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CamaraExceptionHandler())
                .addFilters(new CorrelatorFilter())
                .build();
    }

    static Stream<Arguments> camaraErrors() {
        return Stream.of(
                Arguments.of(CamaraApiException.identifierNotFound(), 404, "IDENTIFIER_NOT_FOUND"),
                Arguments.of(CamaraApiException.missingIdentifier(), 422, "MISSING_IDENTIFIER"),
                Arguments.of(CamaraApiException.unsupportedIdentifier(), 422, "UNSUPPORTED_IDENTIFIER"),
                Arguments.of(CamaraApiException.serviceNotApplicable(), 422, "SERVICE_NOT_APPLICABLE"),
                Arguments.of(CamaraApiException.unavailable(new RuntimeException("GET /subscriber/%2B306912345678")),
                        503, "UNAVAILABLE"),
                Arguments.of(CamaraApiException.unavailable(null), 503, "UNAVAILABLE"),
                Arguments.of(CamaraApiException.notImplemented(), 501, "NOT_IMPLEMENTED"),
                Arguments.of(CamaraApiException.unauthenticated(), 401, "UNAUTHENTICATED"),
                Arguments.of(CamaraApiException.permissionDenied(), 403, "PERMISSION_DENIED"),
                Arguments.of(CamaraApiException.unnecessaryIdentifier(), 422, "UNNECESSARY_IDENTIFIER"));
    }

    @ParameterizedTest
    @MethodSource("camaraErrors")
    void camaraErrorsRenderAsErrorInfoAndKeepCorrelator(CamaraApiException error, int status, String code)
            throws Exception {
        controller.failure = () -> error;

        mvc.perform(get("/fail").header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().is(status))
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(error.getMessage()));
    }

    @Test
    void unexpectedErrorIsInternal() throws Exception {
        controller.failure = () -> new IllegalStateException("boom");

        mvc.perform(get("/fail"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL"))
                .andExpect(jsonPath("$.message").value("Unknown server error. Typically a server bug."));
    }

    @Test
    void constraintViolationIsInvalidArgument() throws Exception {
        controller.failure = () -> new ConstraintViolationException(
                Validation.buildDefaultValidatorFactory().getValidator().validate(new Body("")));

        mvc.perform(get("/fail"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name:")));
    }

    @Test
    void invalidBodyIsInvalidArgumentNamingTheField() throws Exception {
        mvc.perform(post("/things").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name:")));
    }

    @Test
    void malformedJsonIsInvalidArgument() throws Exception {
        mvc.perform(post("/things").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void invalidCorrelatorIsRejectedAndNotEchoed() throws Exception {
        mvc.perform(get("/correlated").header(CorrelatorFilter.HEADER, "bad value!"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(CorrelatorFilter.HEADER))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void validCorrelatorIsEchoedAndPutInMdc() throws Exception {
        mvc.perform(get("/correlated").header(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelatorFilter.HEADER, "abc-123"))
                .andExpect(jsonPath("$.mdc").value("abc-123"));
    }

    @Test
    void missingCorrelatorGetsAGeneratedMdcValueButNoHeader() throws Exception {
        mvc.perform(get("/correlated"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(CorrelatorFilter.HEADER))
                .andExpect(jsonPath("$.mdc").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mvc.perform(get("/things"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void wrongMediaTypeIsUnsupportedMediaType() throws Exception {
        mvc.perform(post("/things").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(post("/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    record Body(@NotBlank String name) {
    }

    @RestController
    static class TestController {

        volatile Supplier<RuntimeException> failure;

        @GetMapping("/fail")
        void fail() {
            throw failure.get();
        }

        @PostMapping("/things")
        Body create(@Valid @RequestBody Body body) {
            return body;
        }

        // Same constraint the generated controllers put on the x-correlator header
        @GetMapping("/correlated")
        Map<String, String> correlated(@RequestHeader(value = CorrelatorFilter.HEADER, required = false)
                @Pattern(regexp = "^[a-zA-Z0-9-_:;./<>{}]{0,256}$") String correlator) {
            return Map.of("mdc", MDC.get(CorrelatorFilter.MDC_KEY));
        }
    }
}
