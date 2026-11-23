package gr.pnet.camara.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.TransformedResource;

class SwaggerScopeFilterTest {

    private static final String INTERCEPTOR = "(response) => response";

    // The anchor must be in the initializer the springdoc in use ships: an upgrade that moves it fails here
    @Test
    void theShippedInitializerGetsTheInterceptor() throws IOException {
        Resource[] initializers = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:META-INF/resources/webjars/swagger-ui/*/" + SwaggerScopeFilter.INITIALIZER);
        assertThat(initializers).hasSize(1);

        String served = SwaggerScopeFilter.withInterceptor(
                initializers[0].getContentAsString(StandardCharsets.UTF_8), INTERCEPTOR);

        assertThat(served).containsSubsequence("SwaggerUIBundle({", "responseInterceptor: " + INTERCEPTOR + ",",
                SwaggerScopeFilter.ANCHOR);
    }

    @Test
    void withoutTheAnchorThereIsNothingToInsertInto() {
        assertThat(SwaggerScopeFilter.withInterceptor("window.ui = SwaggerUIBundle({});", INTERCEPTOR)).isNull();
    }

    @Test
    void onlySpringdocsIndexTransformerIsWrapped() {
        SwaggerScopeFilter filter = new SwaggerScopeFilter();
        SwaggerIndexTransformer springdoc = (request, resource, chain) -> resource;
        Object other = new Object();

        assertThat(filter.postProcessAfterInitialization(springdoc, "indexPageTransformer"))
                .isInstanceOf(SwaggerScopeFilter.ScopeFilteringTransformer.class);
        assertThat(filter.postProcessAfterInitialization(other, "other")).isSameAs(other);
    }

    @Test
    void theInitializerIsServedWithTheInterceptorAfterSpringdocsChanges() throws IOException {
        SwaggerIndexTransformer springdoc = (request, resource, chain) -> new TransformedResource(resource,
                "window.ui = SwaggerUIBundle({ url: \"/api.yaml\", presets: [] });".getBytes(StandardCharsets.UTF_8));
        var transformer = new SwaggerScopeFilter.ScopeFilteringTransformer(springdoc, INTERCEPTOR);

        Resource served = transformer.transform(new MockHttpServletRequest(), named("swagger-initializer.js", "x"), null);

        assertThat(served.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(
                "window.ui = SwaggerUIBundle({ url: \"/api.yaml\", responseInterceptor: " + INTERCEPTOR + ",\n    presets: [] });");
    }

    @Test
    void otherSwaggerUiFilesAreLeftAsSpringdocServesThem() throws IOException {
        Resource index = named("index.html", "<html></html>");
        var transformer = new SwaggerScopeFilter.ScopeFilteringTransformer((request, resource, chain) -> resource,
                INTERCEPTOR);

        assertThat(transformer.transform(new MockHttpServletRequest(), index, null)).isSameAs(index);
    }

    // A springdoc or Swagger UI upgrade without the anchor: served as is, Authorize just lists every scope
    @Test
    void anInitializerWithoutTheAnchorIsServedAsIs() throws IOException {
        Resource initializer = named("swagger-initializer.js", "window.ui = SwaggerUIBundle({});");
        var transformer = new SwaggerScopeFilter.ScopeFilteringTransformer((request, resource, chain) -> resource,
                INTERCEPTOR);

        assertThat(transformer.transform(new MockHttpServletRequest(), initializer, null)).isSameAs(initializer);
    }

    @Test
    void theScriptIsAFunctionExpression() {
        assertThat(SwaggerScopeFilter.script()).contains("(response) => {").endsWith("}");
    }

    // The umbrella chart's Swagger UI runs the same script; it can't read it from this jar
    @Test
    void theUmbrellaChartHasTheSameScript() throws IOException {
        Path chartCopy = Path.of("../../charts/camara/files/swagger-scope-filter.js");

        assertThat(Files.readString(chartCopy).strip()).isEqualTo(SwaggerScopeFilter.script());
    }

    // A file of the Swagger UI webjar: named, and with a last-modified time, which TransformedResource keeps
    private static Resource named(String filename, String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public long lastModified() {
                return 1_700_000_000_000L;
            }
        };
    }
}
