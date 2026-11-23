package gr.pnet.camara.common.error;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import gr.pnet.camara.common.support.Masking;
import gr.pnet.camara.common.web.RequestOutcome;
import jakarta.validation.ConstraintViolationException;

/**
 * Renders every error as a CAMARA {@code ErrorInfo} body, including the ones Spring MVC raises itself
 * (malformed JSON, unknown route, wrong method or media type).
 */
@RestControllerAdvice
public class CamaraExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CamaraExceptionHandler.class);

    private static final String INVALID_ARGUMENT_MESSAGE =
            "Client specified an invalid argument, request body or query param.";

    @ExceptionHandler(CamaraApiException.class)
    public ResponseEntity<Object> handleCamara(CamaraApiException ex) {
        if (ex.getStatus().is5xxServerError()) {
            // No stack trace: upstream messages carry the TEP URL, which contains the MSISDN
            Throwable cause = ex.getCause();
            log.warn("{}: {} cause={}: {}", ex.getCode(), ex.getMessage(),
                    cause == null ? "-" : cause.getClass().getSimpleName(),
                    cause == null ? "-" : Masking.maskAll(cause.getMessage()));
        }
        // The code says what went wrong; the type is only worth having when something lower down failed
        return body(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getCause());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", INVALID_ARGUMENT_MESSAGE + " " + detail, ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Unknown server error. Typically a server bug.",
                ex);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", INVALID_ARGUMENT_MESSAGE + " " + detail, ex);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", INVALID_ARGUMENT_MESSAGE, ex);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return body(status, codeFor(status), messageFor(status), ex);
    }

    // Every answer goes through here, so the access log and metrics always learn its code
    private static ResponseEntity<Object> body(HttpStatus status, String code, String message, Throwable cause) {
        RequestOutcome.error(code, cause);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorInfo(status.value(), code, message));
    }

    // CAMARA API design guidelines, generic error codes
    private static String codeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "INVALID_ARGUMENT";
            case NOT_FOUND -> "NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case NOT_ACCEPTABLE -> "NOT_ACCEPTABLE";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            case SERVICE_UNAVAILABLE -> "UNAVAILABLE";
            default -> status.is5xxServerError() ? "INTERNAL" : status.name();
        };
    }

    private static String messageFor(HttpStatus status) {
        return status == HttpStatus.BAD_REQUEST ? INVALID_ARGUMENT_MESSAGE : status.getReasonPhrase();
    }
}
