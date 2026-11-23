package gr.pnet.camara.qualityondemand.session;

/**
 * Who is asking, from the access token: the API client, and with a three-legged token the subscriber. Both null when
 * auth is off.
 */
public record Caller(String clientId, String phoneNumber) {

    /** A session is only seen by the client that created it, and with a three-legged token, for that subscriber */
    boolean owns(QodSession session) {
        return (clientId == null || clientId.equals(session.clientId()))
                && (phoneNumber == null || phoneNumber.equals(session.phoneNumber()));
    }
}
