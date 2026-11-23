package gr.pnet.camara.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.error.CamaraExceptionHandler;
import jakarta.servlet.ServletException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@ExtendWith(OutputCaptureExtension.class)
class AccessLogFilterTest {

    private static final List<String> DEFAULT_EXCLUDES =
            List.of("/", "/actuator/**", "/swagger-ui/**", "/v3/api-docs/**", "/*.yaml", "/favicon.ico");

    private final TestController controller = new TestController();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = mvc(new AccessLogFilter(true, DEFAULT_EXCLUDES));
    }

    private MockMvc mvc(AccessLogFilter accessLog) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CamaraExceptionHandler())
                .addFilters(new CorrelatorFilter(), accessLog)
                .build();
    }

    @Test
    void successIsLoggedAtInfoWithTheCorrelator(CapturedOutput output) throws Exception {
        mvc.perform(get("/things/42").header(CorrelatorFilter.HEADER, "abc-123")).andExpect(status().isOk());

        assertThat(accessLines(output)).singleElement().asString()
                .contains("INFO")
                .containsPattern("GET /things/42 200 \\d+ms");
    }

    @Test
    void malformedJsonIsLoggedWithItsCodeButNotItsBody(CapturedOutput output) throws Exception {
        mvc.perform(post("/things").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"+306912345678\",")).andExpect(status().isBadRequest());

        assertThat(accessLines(output)).singleElement().asString()
                .contains("INFO")
                .containsPattern("POST /things 400 INVALID_ARGUMENT \\d+ms");
        assertThat(output).doesNotContain("306912345678");
    }

    @Test
    void camaraErrorIsLoggedWithItsCode(CapturedOutput output) throws Exception {
        controller.failure = CamaraApiException::identifierNotFound;

        mvc.perform(get("/fail")).andExpect(status().isNotFound());

        assertThat(accessLines(output)).singleElement().asString().containsPattern("GET /fail 404 IDENTIFIER_NOT_FOUND");
    }

    @Test
    void serverErrorIsLoggedAtWarn(CapturedOutput output) throws Exception {
        controller.failure = () -> CamaraApiException.unavailable(new RuntimeException("GET /subscriber/%2B306912345678"));

        mvc.perform(get("/fail")).andExpect(status().isServiceUnavailable());

        assertThat(accessLines(output)).singleElement().asString()
                .contains("WARN")
                .containsPattern("GET /fail 503 UNAVAILABLE \\d+ms");
        assertThat(output).doesNotContain("306912345678");
    }

    @Test
    void unhandledErrorIsLoggedAsInternal(CapturedOutput output) throws Exception {
        controller.failure = () -> new IllegalStateException("boom");

        mvc.perform(get("/fail")).andExpect(status().isInternalServerError());

        assertThat(accessLines(output)).singleElement().asString().contains("GET /fail 500 INTERNAL");
    }

    @Test
    void unknownPathIsLoggedWithAMaskedPhoneNumber(CapturedOutput output) throws Exception {
        mvc.perform(get("/subscriber/+306912345678")).andExpect(status().isNotFound());

        assertThat(accessLines(output)).singleElement().asString()
                .contains("GET /subscriber/+3069******78 404 NOT_FOUND")
                .doesNotContain("306912345678");
    }

    @Test
    void queryStringIsNotLogged(CapturedOutput output) throws Exception {
        mvc.perform(get("/things/42").queryParam("secret", "s3cr3t")).andExpect(status().isOk());

        assertThat(accessLines(output)).singleElement().asString().contains("GET /things/42 200");
        assertThat(output).doesNotContain("s3cr3t");
    }

    @Test
    void excludedPathsAreNotLogged(CapturedOutput output) throws Exception {
        mvc.perform(get("/actuator/health"));
        mvc.perform(get("/swagger-ui/index.html"));
        mvc.perform(get("/device-reachability-status.yaml"));
        mvc.perform(get("/"));

        assertThat(accessLines(output)).isEmpty();
    }

    @Test
    void disabledLogsNothing(CapturedOutput output) throws Exception {
        mvc(new AccessLogFilter(false, DEFAULT_EXCLUDES)).perform(get("/things/42")).andExpect(status().isOk());

        assertThat(accessLines(output)).isEmpty();
    }

    @Test
    void exceptionOutOfTheChainIsLoggedAs500AndRethrown(CapturedOutput output) {
        AccessLogFilter filter = new AccessLogFilter(true, DEFAULT_EXCLUDES);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boom");
        ServletException failure = new ServletException("boom");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(accessLines(output)).singleElement().asString().contains("WARN").contains("GET /boom 500");
    }

    @Test
    void clientIdIsLoggedWhenRecorded(CapturedOutput output) throws Exception {
        AccessLogFilter filter = new AccessLogFilter(true, DEFAULT_EXCLUDES);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/things/42");

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> RequestOutcome.clientId(request, "acme-backend"));

        assertThat(accessLines(output)).singleElement().asString().contains("GET /things/42 200");
        assertThat(RequestOutcome.clientId(request)).isEqualTo("acme-backend");
    }

    private static List<String> accessLines(CapturedOutput output) {
        // The access line, not a stack frame of another log line that passes through the filter
        return output.getOut().lines()
                .filter(line -> line.contains(AccessLogFilter.class.getSimpleName()) && !line.strip().startsWith("at "))
                .toList();
    }

    record Body(@NotBlank String phoneNumber) {
    }

    @RestController
    static class TestController {

        volatile Supplier<RuntimeException> failure;

        @GetMapping("/things/{id}")
        String thing(@PathVariable String id) {
            return id;
        }

        @PostMapping("/things")
        Body create(@Valid @RequestBody Body body) {
            return body;
        }

        @GetMapping("/fail")
        void fail() {
            throw failure.get();
        }
    }
}
