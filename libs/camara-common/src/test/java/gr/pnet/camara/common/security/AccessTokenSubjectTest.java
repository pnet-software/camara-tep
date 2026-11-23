package gr.pnet.camara.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AccessTokenSubjectTest {

    private final AccessTokenSubject subject = new AccessTokenSubject("phone_number");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void threeLeggedTokenNamesThePhoneNumber() {
        authenticate(token(Map.of("phone_number", "+306912345678")));

        assertThat(subject.phoneNumber()).contains("+306912345678");
    }

    @Test
    void twoLeggedTokenNamesNoSubscriber() {
        authenticate(token(Map.of("azp", "acme-backend")));

        assertThat(subject.phoneNumber()).isEmpty();
    }

    @Test
    void blankClaimNamesNoSubscriber() {
        authenticate(token(Map.of("phone_number", " ")));

        assertThat(subject.phoneNumber()).isEmpty();
    }

    @Test
    void noTokenNamesNoSubscriber() {
        assertThat(subject.phoneNumber()).isEmpty();

        authenticate(new TestingAuthenticationToken("user", "password"));
        assertThat(subject.phoneNumber()).isEmpty();
    }

    @Test
    void claimNameIsConfigurable() {
        authenticate(token(Map.of("msisdn", "+306912345678")));

        assertThat(new AccessTokenSubject("msisdn").phoneNumber()).contains("+306912345678");
        assertThat(subject.phoneNumber()).isEmpty();
    }

    @Test
    void clientIsTheAuthorizedParty() {
        authenticate(token(Map.of("azp", "acme-backend", "client_id", "other")));

        assertThat(subject.clientId()).contains("acme-backend");
    }

    @Test
    void clientFallsBackToClientIdClaim() {
        authenticate(token(Map.of("client_id", "acme-backend")));

        assertThat(subject.clientId()).contains("acme-backend");
    }

    @Test
    void noTokenNamesNoClient() {
        assertThat(subject.clientId()).isEmpty();

        authenticate(token(Map.of("azp", " ")));
        assertThat(subject.clientId()).isEmpty();
    }

    private static Authentication token(Map<String, Object> claims) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("subject")
                .claims(c -> c.putAll(claims))
                .build());
    }

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
