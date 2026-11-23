package gr.pnet.camara.qualityondemand.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import gr.pnet.camara.common.security.CamaraAuthErrorHandler;
import gr.pnet.camara.common.security.ResourceMetadata;

/**
 * The spec's {@code openId} security: every API call needs a valid access token carrying the scope of the called
 * operation, {@code quality-on-demand:sessions:<create|read|delete|update|retrieve-by-device>}; no scope opens
 * another operation. {@code camara.auth.enabled=false} turns this off for labs.
 */
@Configuration
public class SecurityConfig {

    static final String CREATE_SCOPE = "quality-on-demand:sessions:create";
    static final String READ_SCOPE = "quality-on-demand:sessions:read";
    static final String DELETE_SCOPE = "quality-on-demand:sessions:delete";
    static final String UPDATE_SCOPE = "quality-on-demand:sessions:update";
    static final String RETRIEVE_BY_DEVICE_SCOPE = "quality-on-demand:sessions:retrieve-by-device";
    /** The scopes as Spring Security names them in the token's authorities */
    public static final String CREATE_AUTHORITY = "SCOPE_" + CREATE_SCOPE;
    public static final String READ_AUTHORITY = "SCOPE_" + READ_SCOPE;
    public static final String DELETE_AUTHORITY = "SCOPE_" + DELETE_SCOPE;
    public static final String UPDATE_AUTHORITY = "SCOPE_" + UPDATE_SCOPE;
    public static final String RETRIEVE_BY_DEVICE_AUTHORITY = "SCOPE_" + RETRIEVE_BY_DEVICE_SCOPE;

    static final String SESSIONS_PATH = "/quality-on-demand/v1/sessions";
    static final String SESSION_PATH = "/quality-on-demand/v1/sessions/*";
    static final String EXTEND_PATH = "/quality-on-demand/v1/sessions/*/extend";
    static final String RETRIEVE_SESSIONS_PATH = "/quality-on-demand/v1/retrieve-sessions";
    static final String API_PATHS = "/quality-on-demand/**";

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
                        // One operation per path: any method, so a wrong one reaches MVC and gets 405, not 403
                        .requestMatchers(SESSIONS_PATH).hasAuthority(CREATE_AUTHORITY)
                        .requestMatchers(EXTEND_PATH).hasAuthority(UPDATE_AUTHORITY)
                        .requestMatchers(RETRIEVE_SESSIONS_PATH).hasAuthority(RETRIEVE_BY_DEVICE_AUTHORITY)
                        // A session has two: DELETE with its own scope, GET with read. Every other method needs read
                        // too, so HEAD (which MVC answers as GET) can't skip the check, and a wrong one still gets 405
                        .requestMatchers(HttpMethod.DELETE, SESSION_PATH).hasAuthority(DELETE_AUTHORITY)
                        .requestMatchers(SESSION_PATH).hasAuthority(READ_AUTHORITY)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(decoder))
                        // Tells a client that got a 401 where to get a token, and with which scope
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(m -> {
                            if (StringUtils.hasText(issuerUri)) {
                                m.authorizationServer(issuerUri);
                            }
                            m.scope(CREATE_SCOPE)
                                    .scope(READ_SCOPE)
                                    .scope(DELETE_SCOPE)
                                    .scope(UPDATE_SCOPE)
                                    .scope(RETRIEVE_BY_DEVICE_SCOPE)
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
