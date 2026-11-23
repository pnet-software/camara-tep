package gr.pnet.camara.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.TransformedResource;

/**
 * Serves each service's CAMARA spec ({@code static/*.yaml}) with the {@code openId} scheme's
 * {@code openIdConnectUrl} set to the issuer's discovery document, so Swagger UI's Authorize gets tokens from the
 * authorization server the service checks them against, wherever it is deployed. Without an issuer the spec is
 * served as written, pointing at the dev/keycloak realm.
 */
@Component
public class SpecOpenIdConnectUrl implements WebMvcConfigurer {

    static final String SPEC_PATTERN = "/*.yaml";

    // The openId scheme's line; each spec has one
    private static final Pattern OPEN_ID_CONNECT_URL = Pattern.compile("(?m)^([ \\t]*openIdConnectUrl:).*$");

    private final String issuerUri;

    public SpecOpenIdConnectUrl(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        this.issuerUri = issuerUri;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!StringUtils.hasText(issuerUri)) {
            return;
        }
        String url = discoveryUrl(issuerUri);
        // More specific than Boot's /** static handler, so the specs, and only they, come through here.
        // The chain caches the result: the URL is fixed for the service's lifetime
        registry.addResourceHandler(SPEC_PATTERN)
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addTransformer((request, resource, chain) ->
                        withOpenIdConnectUrl(chain.transform(request, resource), url));
    }

    // OpenID Connect Discovery: the issuer, with any trailing slash removed, + /.well-known/openid-configuration
    static String discoveryUrl(String issuerUri) {
        return StringUtils.trimTrailingCharacter(issuerUri, '/') + "/.well-known/openid-configuration";
    }

    static Resource withOpenIdConnectUrl(Resource spec, String url) throws IOException {
        String content = spec.getContentAsString(StandardCharsets.UTF_8);
        String rewritten = OPEN_ID_CONNECT_URL.matcher(content).replaceAll("$1 " + Matcher.quoteReplacement(url));
        return new TransformedResource(spec, rewritten.getBytes(StandardCharsets.UTF_8));
    }
}
