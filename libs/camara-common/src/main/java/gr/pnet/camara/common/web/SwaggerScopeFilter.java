package gr.pnet.camara.common.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Makes each service's Swagger UI offer only its API's scopes in Authorize, not every scope of the realm, and only
 * the flows Swagger UI can log in with, not every grant type the issuer lists: adds
 * {@code camara/swagger-scope-filter.js} as the {@code responseInterceptor} of the {@code swagger-initializer.js}
 * springdoc generates. springdoc writes its settings as JSON, which can't hold a function, so its transformer is
 * wrapped and the script inserted where springdoc puts its own CSRF interceptor, before {@code presets: [}.
 */
@Component
@ConditionalOnClass(name = "org.springdoc.webmvc.ui.SwaggerIndexTransformer")
public class SwaggerScopeFilter implements BeanPostProcessor {

    static final String INITIALIZER = "swagger-initializer.js";
    static final String ANCHOR = "presets: [";
    static final String SCRIPT = "camara/swagger-scope-filter.js";

    private static final Logger log = LoggerFactory.getLogger(SwaggerScopeFilter.class);

    private final String interceptor = script();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof SwaggerIndexTransformer transformer) {
            return new ScopeFilteringTransformer(transformer, interceptor);
        }
        return bean;
    }

    static String script() {
        try {
            return new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing " + SCRIPT, e);
        }
    }

    /** The initializer with the interceptor among SwaggerUIBundle's settings; null without the anchor */
    static String withInterceptor(String initializer, String interceptor) {
        int at = initializer.indexOf(ANCHOR);
        if (at < 0) {
            return null;
        }
        return initializer.substring(0, at) + "responseInterceptor: " + interceptor + ",\n    "
                + initializer.substring(at);
    }

    record ScopeFilteringTransformer(SwaggerIndexTransformer delegate, String interceptor)
            implements SwaggerIndexTransformer {

        @Override
        public Resource transform(HttpServletRequest request, Resource resource, ResourceTransformerChain chain)
                throws IOException {
            Resource transformed = delegate.transform(request, resource, chain);
            if (!INITIALIZER.equals(resource.getFilename())) {
                return transformed;
            }
            String initializer = withInterceptor(transformed.getContentAsString(StandardCharsets.UTF_8), interceptor);
            if (initializer == null) {
                // A Swagger UI or springdoc upgrade moved the anchor: Authorize then lists every scope again
                log.warn("No '{}' in {}: Swagger UI's Authorize will offer every scope of the realm", ANCHOR,
                        INITIALIZER);
                return transformed;
            }
            return new TransformedResource(resource, initializer.getBytes(StandardCharsets.UTF_8));
        }
    }
}
