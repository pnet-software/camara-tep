package gr.pnet.camara.reachability.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import gr.pnet.camara.common.security.CamaraAuthErrorHandler;
import gr.pnet.camara.common.security.ResourceMetadata;

/**
 * The spec's {@code openId} security: every API call needs a valid access token carrying the
 * {@code device-reachability-status:read} scope. {@code camara.auth.enabled=false} turns this off for labs.
 */
@Configuration
public class SecurityConfig {

    static final String SCOPE = "device-reachability-status:read";
    /** The scope as Spring Security names it in the token's authorities */
    public static final String READ_SCOPE = "SCOPE_" + SCOPE;

    static final String API_PATHS = "/device-reachability-status/**";

    @Bean
    @ConditionalOnBooleanProperty(name = "camara.auth.enabled", matchIfMissing = true)
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectProvider<JwtDecoder> jwtDecoder,
            CamaraAuthErrorHandler errors,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) throws Exception {
        // Boot builds the decoder from spring.security.oauth2.resourceserver.jwt.issuer-uri
        JwtDecoder decoder = jwtDecoder.getIfAvailable(() -> {
            throw new IllegalStateException(
                    "Authentication is enabled but AUTH_ISSUER_URI is not set; set it, or set AUTH_ENABLED=false");
        });
        return http
                // Only the API needs a token: Swagger UI, the spec file and actuator stay open
                .securityMatcher(API_PATHS, ResourceMetadata.PATHS)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(ResourceMetadata.PATHS).permitAll()
                        .anyRequest().hasAuthority(READ_SCOPE))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(decoder))
                        // Tells a client that got a 401 where to get a token, and with which scope
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(m -> {
                            if (StringUtils.hasText(issuerUri)) {
                                m.authorizationServer(issuerUri);
                            }
                            m.scope(SCOPE).tlsClientCertificateBoundAccessTokens(false);
                        }))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    // Without a chain of our own, Boot's default would lock everything behind a generated password
    @Bean
    @ConditionalOnBooleanProperty(name = "camara.auth.enabled", havingValue = false)
    SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
