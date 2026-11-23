package gr.pnet.camara.common.error;

import org.springframework.http.HttpStatus;

/**
 * An error that maps one-to-one onto a CAMARA {@code ErrorInfo} response.
 */
public class CamaraApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CamaraApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public CamaraApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static CamaraApiException identifierNotFound() {
        return new CamaraApiException(HttpStatus.NOT_FOUND, "IDENTIFIER_NOT_FOUND",
                "Device identifier not found.");
    }

    // A resource named in the path, e.g. a QoS profile, rather than a device
    public static CamaraApiException notFound() {
        return new CamaraApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "The specified resource is not found.");
    }

    public static CamaraApiException missingIdentifier() {
        return new CamaraApiException(HttpStatus.UNPROCESSABLE_CONTENT, "MISSING_IDENTIFIER",
                "The device cannot be identified.");
    }

    public static CamaraApiException unsupportedIdentifier() {
        return new CamaraApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_IDENTIFIER",
                "The identifier provided is not supported.");
    }

    public static CamaraApiException serviceNotApplicable() {
        return new CamaraApiException(HttpStatus.UNPROCESSABLE_CONTENT, "SERVICE_NOT_APPLICABLE",
                "The service is not available for the provided identifier.");
    }

    public static CamaraApiException unavailable(Throwable cause) {
        return new CamaraApiException(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE",
                "Service Unavailable.", cause);
    }

    // For a service whose delegate isn't written yet
    public static CamaraApiException notImplemented() {
        return new CamaraApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED",
                "This functionality is not implemented yet.");
    }

    // Reserved for the authentication phase (release plan 6.x)

    public static CamaraApiException unauthenticated() {
        return new CamaraApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Request not authenticated due to missing, invalid, or expired credentials.");
    }

    public static CamaraApiException permissionDenied() {
        return new CamaraApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                "Client does not have sufficient permissions to perform this action.");
    }

    public static CamaraApiException unnecessaryIdentifier() {
        return new CamaraApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNNECESSARY_IDENTIFIER",
                "The device is already identified by the access token.");
    }
}
