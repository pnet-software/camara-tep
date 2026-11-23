package gr.pnet.camara.deviceswap.security;

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
 * The spec's {@code openId} security: every API call needs a valid access token carrying either the
 * {@code device-swap} scope or the scope of the called operation. {@code camara.auth.enabled=false}
 * turns this off for labs.
 */
@Configuration
public class SecurityConfig {

    static final String SCOPE = "device-swap";
    static final String RETRIEVE_DATE_SCOPE = "device-swap:retrieve-date";
    static final String CHECK_SCOPE = "device-swap:check";
    /** The scopes as Spring Security names them in the token's authorities */
    public static final String DEVICE_SWAP_AUTHORITY = "SCOPE_" + SCOPE;
    public static final String RETRIEVE_DATE_AUTHORITY = "SCOPE_" + RETRIEVE_DATE_SCOPE;
    public static final String CHECK_AUTHORITY = "SCOPE_" + CHECK_SCOPE;

    static final String RETRIEVE_DATE_PATH = "/device-swap/v1/retrieve-date";
    static final String CHECK_PATH = "/device-swap/v1/check";
    static final String API_PATHS = "/device-swap/**";

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
                        // Any method: a wrong one then reaches MVC and gets 405, not 403
                        .requestMatchers(RETRIEVE_DATE_PATH).hasAnyAuthority(DEVICE_SWAP_AUTHORITY, RETRIEVE_DATE_AUTHORITY)
                        .requestMatchers(CHECK_PATH).hasAnyAuthority(DEVICE_SWAP_AUTHORITY, CHECK_AUTHORITY)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(decoder))
                        // Tells a client that got a 401 where to get a token, and with which scope
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(m -> {
                            if (StringUtils.hasText(issuerUri)) {
                                m.authorizationServer(issuerUri);
                            }
                            m.scope(SCOPE)
                                    .scope(RETRIEVE_DATE_SCOPE)
                                    .scope(CHECK_SCOPE)
                                    .tlsClientCertificateBoundAccessTokens(false);
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
