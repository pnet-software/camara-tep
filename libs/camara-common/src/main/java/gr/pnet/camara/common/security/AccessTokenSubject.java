package gr.pnet.camara.common.security;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The subscriber a three-legged access token was issued for, and the API client any token was issued to. Two-legged
 * (client credentials) tokens name no subscriber, so an empty phone number means the device has to come from the
 * request body.
 */
@Component
public class AccessTokenSubject {

    private final String phoneNumberClaim;

    public AccessTokenSubject(@Value("${camara.auth.phone-number-claim:phone_number}") String phoneNumberClaim) {
        this.phoneNumberClaim = phoneNumberClaim;
    }

    public Optional<String> phoneNumber() {
        return claim(phoneNumberClaim);
    }

    /** The API client the token was issued to: {@code azp} (OpenID Connect), else {@code client_id} (RFC 9068) */
    public Optional<String> clientId() {
        return clientId(SecurityContextHolder.getContext().getAuthentication());
    }

    /** As {@link #clientId()}, for the authentication given, e.g. a request's principal */
    public static Optional<String> clientId(Object authentication) {
        return claim(authentication, "azp").or(() -> claim(authentication, "client_id"));
    }

    private static Optional<String> claim(String name) {
        return claim(SecurityContextHolder.getContext().getAuthentication(), name);
    }

    private static Optional<String> claim(Object authentication, String name) {
        if (authentication instanceof JwtAuthenticationToken token) {
            return Optional.ofNullable(token.getToken().getClaimAsString(name)).filter(StringUtils::hasText);
        }
        return Optional.empty();
    }
}
