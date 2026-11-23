package gr.pnet.camara.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

class SpecOpenIdConnectUrlTest {

    @TempDir
    private Path tempDir;

    private static final String SPEC = """
            openapi: 3.0.3
            components:
              securitySchemes:
                openId:
                  type: openIdConnect
                  # The dev/keycloak realm
                  openIdConnectUrl: http://localhost:8180/realms/camara/.well-known/openid-configuration
                  description: OpenID Connect authentication via discovery metadata.
            """;

    // Keycloak before 17, and some deployments since, serve the realms under /auth
    @ParameterizedTest
    @ValueSource(strings = { "http://192.0.2.10:30667/auth/realms/camara", "http://192.0.2.10:30667/auth/realms/camara/" })
    void discoveryUrlIsTheIssuersWellKnownDocument(String issuerUri) {
        assertThat(SpecOpenIdConnectUrl.discoveryUrl(issuerUri))
                .isEqualTo("http://192.0.2.10:30667/auth/realms/camara/.well-known/openid-configuration");
    }

    @Test
    void replacesOnlyTheOpenIdConnectUrl() throws IOException {
        String url = "http://keycloak.example/auth/realms/camara/.well-known/openid-configuration";

        String served = SpecOpenIdConnectUrl.withOpenIdConnectUrl(spec(), url)
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(served).isEqualTo(SPEC.replace(
                "http://localhost:8180/realms/camara/.well-known/openid-configuration", url));
    }

    // $ and \ mean something in a regex replacement; a URL could carry them
    @Test
    void urlIsInsertedLiterally() throws IOException {
        String served = SpecOpenIdConnectUrl.withOpenIdConnectUrl(spec(), "http://k.example/$1/a\\b")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(served).contains("      openIdConnectUrl: http://k.example/$1/a\\b\n");
    }

    @Test
    void withAnIssuerTheSpecsGetTheirOwnHandler() {
        assertThat(registryAfter(new SpecOpenIdConnectUrl("http://keycloak.example/realms/camara"))
                .hasMappingForPattern(SpecOpenIdConnectUrl.SPEC_PATTERN)).isTrue();
    }

    // Boot's static handler then serves the spec as written
    @Test
    void withoutAnIssuerNothingIsRegistered() {
        assertThat(registryAfter(new SpecOpenIdConnectUrl(""))
                .hasMappingForPattern(SpecOpenIdConnectUrl.SPEC_PATTERN)).isFalse();
    }

    // A file, like the specs on the classpath: the served copy keeps its last-modified time
    private Resource spec() throws IOException {
        return new FileSystemResource(Files.writeString(tempDir.resolve("spec.yaml"), SPEC));
    }

    private static ResourceHandlerRegistry registryAfter(SpecOpenIdConnectUrl configurer) {
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry(new GenericWebApplicationContext(),
                new MockServletContext());
        configurer.addResourceHandlers(registry);
        return registry;
    }
}
